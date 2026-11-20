"""Independent audits of simulator state, point-in-time features and labels.

This module never derives model inputs from private truth.  Private events are
read only to audit physical invariants and the provenance of observed outcomes.
The feature formulas below are recomputed from public events independently of
the feature extraction implementation.
"""
from __future__ import annotations

from bisect import bisect_left
from collections import Counter, defaultdict
from datetime import date, datetime, time, timedelta, timezone
import hashlib
import math

SEOUL = timezone(timedelta(hours=9))
FEATURES = (
    'pastFailureRate90d', 'targetConfirmedBookingCount', 'bookingVolumeGrowth7d',
    'reconfirmationOverdueRate7d', 'inventoryUnavailableRate7d', 'isPeakDay',
)
PUBLIC_TYPES = {
    'BOOKING_STATUS', 'RECONFIRMATION_REQUEST', 'HOTEL_REPLY',
    'INVENTORY_PROBE', 'OBSERVED_OUTCOME',
}


def _at(value: str) -> datetime:
    result = datetime.fromisoformat(value)
    if result.tzinfo is None or result.utcoffset() is None:
        raise ValueError('timestamp_offset: all timestamps require an offset')
    return result


def _kind(event: dict) -> str:
    return event.get('event_type', event.get('type'))


def _grain(row: dict) -> tuple:
    return row['hotelId'], row['targetCheckInDate'], row['asOf']


def _nights(booking: dict) -> list[str]:
    first = date.fromisoformat(booking['check_in_date'])
    last = date.fromisoformat(booking['check_out_date'])
    return [(first + timedelta(days=i)).isoformat() for i in range((last-first).days)]


class _Audit:
    def __init__(self):
        self.counts = Counter()

    def check(self, condition: bool, rule: str, detail: object = '') -> None:
        if not condition:
            raise ValueError(f'{rule}: {detail}')
        self.counts[rule] += 1

    def report(self) -> dict:
        return {'status': 'PASS', 'rule_count': len(self.counts),
                'assertion_count': sum(self.counts.values()),
                'checks': dict(sorted(self.counts.items()))}


class _PublicIndex:
    """Small hotel-level indexes; avoid scanning a full event file per row."""
    def __init__(self, source: dict, audit: _Audit):
        self.bookings = {b['booking_id']: b for b in source['bookings']}
        self.events = {e['event_id']: e for e in source['events']}
        audit.check(len(self.bookings) == len(source['bookings']), 'booking_ids_unique')
        audit.check(len(self.events) == len(source['events']), 'event_ids_unique')
        self.occurred, self.available = {}, {}
        self.statuses, self.replies, self.outcomes = defaultdict(list), defaultdict(list), defaultdict(list)
        self.all_requests, self.replies_by_booking = defaultdict(list), defaultdict(list)
        self.by_checkin, self.probes = defaultdict(list), defaultdict(list)
        self.requests = {}
        self.confirmations = {}
        for bid, booking in self.bookings.items():
            self.by_checkin[booking['check_in_date']].append(bid)
        for e in source['events']:
            eid, bid, kind = e['event_id'], e.get('booking_id'), _kind(e)
            self.occurred[eid], self.available[eid] = _at(e['occurred_at']), _at(e['available_at'])
            audit.check(self.occurred[eid] <= self.available[eid], 'event_occurs_before_available', eid)
            if bid is not None:
                audit.check(bid in self.bookings, 'event_booking_exists', eid)
            p = e['payload']
            if kind == 'BOOKING_STATUS':
                self.statuses[bid].append(e)
                if p['status'] == 'CONFIRMED':
                    previous = self.confirmations.get(bid)
                    if previous is None or self.occurred[eid] < self.occurred[previous['event_id']]:
                        self.confirmations[bid] = e
            elif kind == 'RECONFIRMATION_REQUEST':
                self.all_requests[bid].append(e)
                previous = self.requests.get(bid)
                if previous is None or self.occurred[eid] < self.occurred[previous['event_id']]:
                    self.requests[bid] = e
            elif kind == 'HOTEL_REPLY':
                self.replies[p['request_id']].append(e)
                self.replies_by_booking[bid].append(e)
            elif kind == 'OBSERVED_OUTCOME':
                audit.check(type(p['label']) is int and p['label'] in (0, 1), 'observed_labels_binary', eid)
                self.outcomes[bid].append(e)
            elif kind == 'INVENTORY_PROBE':
                target = p.get('target_check_in_date', p.get('check_in_date'))
                self.probes[target].append(e)
        for values in self.statuses.values():
            values.sort(key=lambda e: (self.occurred[e['event_id']], e['event_id']))
        for values in self.all_requests.values():
            values.sort(key=lambda e: (self.occurred[e['event_id']], e['event_id']))
        for values in self.probes.values():
            values.sort(key=lambda e: (self.occurred[e['event_id']], e['event_id']))
        self.confirmation_nodes = sorted((e for values in self.statuses.values() for e in values if e['payload']['status'] == 'CONFIRMED'),
                                         key=lambda e: self.occurred[e['event_id']])
        self.confirmation_times = [self.occurred[e['event_id']] for e in self.confirmation_nodes]
        self.request_nodes = sorted((e for values in self.all_requests.values() for e in values), key=lambda e: _at(e['payload']['due_at']))
        self.request_times = [_at(e['payload']['due_at']) for e in self.request_nodes]
        self.checkin_dates = sorted(self.by_checkin)

    def known(self, event: dict, at: datetime) -> bool:
        eid = event['event_id']
        return self.occurred[eid] <= at and self.available[eid] <= at

    def status(self, bid: str, at: datetime) -> dict | None:
        known = [e for e in self.statuses.get(bid, []) if self.known(e, at)]
        return known[-1] if known else None

    def cohort(self, target: str, at: datetime) -> list[str]:
        result = []
        for bid in self.by_checkin.get(target, []):
            state = self.status(bid, at)
            if state is not None and state['payload']['status'] == 'CONFIRMED' \
                    and self.bookings[bid]['channel'] == 'PLATFORM':
                result.append(bid)
        return sorted(result)

    def first_confirmation(self, bid: str, at: datetime) -> dict | None:
        return next((e for e in self.statuses.get(bid, []) if e['payload']['status'] == 'CONFIRMED'
                     and self.known(e, at)), None)

    def outcome(self, bid: str, at: datetime) -> dict | None:
        known = [e for e in self.outcomes.get(bid, []) if self.known(e, at)]
        return min(known, key=lambda e: self.available[e['event_id']]) if known else None

    def historical(self, asof: datetime, days: int) -> list[dict]:
        end = asof.astimezone(SEOUL).date()
        start = end - timedelta(days=days)
        days_in_window = self.checkin_dates[bisect_left(self.checkin_dates, start.isoformat()):
                                             bisect_left(self.checkin_dates, end.isoformat())]
        result = []
        for day in days_in_window:
            for bid in self.by_checkin[day]:
                confirmation = self.first_confirmation(bid, asof)
                if confirmation and confirmation['payload']['channel'] == 'PLATFORM':
                    event = self.outcome(bid, asof)
                    if event:
                        result.append(event)
        return result

    def confirmation_window(self, start: datetime, end: datetime, asof: datetime | None = None) -> list[dict]:
        left, right = bisect_left(self.confirmation_times, start), bisect_left(self.confirmation_times, end)
        at = asof or end
        candidate_ids = {e['booking_id'] for e in self.confirmation_nodes[left:right] if self.known(e, at)}
        result = []
        for bid in candidate_ids:
            first = self.first_confirmation(bid, at)
            if first and first['payload']['channel'] == 'PLATFORM' and start <= self.occurred[first['event_id']] < end:
                result.append(first)
        return result

    def overdue(self, asof: datetime, window_days: int) -> tuple[int, int, list[dict]]:
        start = asof - timedelta(days=window_days)
        left, right = bisect_left(self.request_times, start), bisect_left(self.request_times, asof)
        eligible, overdue, used = 0, 0, []
        candidate_ids = {e['booking_id'] for e in self.request_nodes[left:right] if self.known(e, asof)}
        for bid in candidate_ids:
            request = next((e for e in self.all_requests[bid] if self.known(e, asof)), None)
            if request is None or not start <= _at(request['payload']['due_at']) < asof:
                continue
            bid, p = request['booking_id'], request['payload']
            if self.bookings[bid]['channel'] != 'PLATFORM':
                continue
            due = _at(p['due_at'])
            state = self.status(bid, due)
            if state is None or state['payload']['status'] != 'CONFIRMED':
                continue
            eligible += 1
            used.extend((request, state))
            expected = set(_nights(self.bookings[bid]))
            valid = []
            for response in self.replies_by_booking[bid]:
                reply = response['payload']
                night_rows = reply.get('nights', [])
                actual = {n.get('stay_date', n.get('stayDate')) for n in night_rows}
                if response['booking_id'] == bid and self.known(response, due) and reply.get('http_status') == 200 \
                        and reply.get('schema_valid') is True and actual == expected and len(night_rows) == len(expected) \
                        and all(n.get('status', n.get('reportedProvisionStatus')) in {'AVAILABLE', 'UNAVAILABLE', 'UNKNOWN'} for n in night_rows):
                    matching_requests = [e for e in self.all_requests[bid] if e['payload']['request_id'] == reply.get('request_id')
                                         and self.known(e, asof) and self.occurred[e['event_id']] <= self.occurred[response['event_id']]]
                    if matching_requests:
                        valid.append(response)
            if valid:
                used.append(min(valid, key=lambda e: (self.available[e['event_id']], self.occurred[e['event_id']], e['event_id'])))
            overdue += not valid
        return int(overdue), eligible, used

    def inventory(self, target: str, asof: datetime, window_days: int) -> tuple[int, int, int, list[dict]]:
        unavailable, count, invalid, seen, used = 0, 0, 0, set(), []
        next_day = (date.fromisoformat(target) + timedelta(days=1)).isoformat()
        first_visible = {}
        for event in self.probes.get(target, []):
            if self.known(event, asof):
                first_visible.setdefault(event['payload']['request_id'], event)
        for event in first_visible.values():
            eid, p = event['event_id'], event['payload']
            if not self.known(event, asof) or not asof-timedelta(days=window_days) <= self.occurred[eid] < asof:
                continue
            if p.get('room_type') != 'STANDARD' or p.get('adults') != 2 or p.get('rooms') != 1 or p.get('currency') != 'KRW':
                continue
            if p.get('target_check_out_date', p.get('check_out_date')) != next_day:
                continue
            request_id = p['request_id']
            if request_id in seen:
                continue
            seen.add(request_id)
            used.append(event)
            if p.get('http_status') != 200 or type(p.get('available_rooms')) is not int or p['available_rooms'] < 0:
                invalid += 1
                continue
            count += 1
            unavailable += p['available_rooms'] < 1
        return int(unavailable), count, invalid, used


def _validate_source(source: dict, index: _PublicIndex, audit: _Audit, config: dict) -> None:
    hotel = source['hotel']
    hid = hotel.get('id', hotel.get('hotel_id'))
    capacity = hotel['room_capacity']
    audit.check(type(capacity) is int and capacity > 0, 'hotel_capacity_positive', hid)
    by_booking = defaultdict(list)
    for row in source['booking_nights']:
        audit.check(row['booking_id'] in index.bookings, 'night_booking_exists', row['booking_id'])
        by_booking[row['booking_id']].append(row)
    for bid, booking in index.bookings.items():
        required = _nights(booking)
        audit.check(0 < len(required) <= config['simulation']['max_stay_nights'], 'booking_stay_range', bid)
        audit.check(booking['hotel_id'] == hid, 'booking_hotel_matches_source', bid)
        audit.check(len(by_booking[bid]) == len(required) and {r['stay_date'] for r in by_booking[bid]} == set(required),
                    'booking_nights_complete_and_checkout_excluded', bid)
        audit.check(type(booking['rooms']) is int and booking['rooms'] > 0, 'booking_rooms_positive', bid)

    # Replay physical private ledgers independently of the engine's own counters.
    pms_counts, physical_counts = Counter(), Counter()
    active, allocated, terminals = {}, set(), {}
    truth = {t['booking_id']: t for t in source['truth']}
    audit.check(len(truth) == len(source['truth']) and set(truth) == set(index.bookings), 'private_truth_booking_coverage')
    sim_start, sim_end = _at(config['simulation']['start']), _at(config['simulation']['end'])
    for e in source['events']:
        kind, bid = _kind(e), e.get('booking_id')
        at = index.occurred[e['event_id']]
        audit.check(e['hotel_id'] == hid, 'event_hotel_matches_source', e['event_id'])
        audit.check(sim_start <= at <= index.available[e['event_id']] <= sim_end, 'event_within_simulation_clock', e['event_id'])
        if kind in {'NO_SHOW', 'FINAL_REJECTED'} or (kind == 'BOOKING_STATUS' and e['payload'].get('status') == 'CANCELLED'):
            terminals[bid] = min(terminals.get(bid, at), at)
    sorted_events = sorted(source['events'], key=lambda e: (index.occurred[e['event_id']], e['event_id']))
    for event in sorted_events:
        kind, bid, p = _kind(event), event.get('booking_id'), event['payload']
        if kind not in {'PMS_RESERVED', 'PMS_RELEASED', 'PHYSICAL_ALLOCATED'}:
            continue
        rows = p['nights']
        period = {r['stay_date'] for r in rows}
        audit.check(len(rows) == len(period) and period == set(_nights(index.bookings[bid])),
                    'allocation_is_atomic_full_stay', event['event_id'])
        rooms = p.get('rooms', index.bookings[bid]['rooms'])
        at = index.occurred[event['event_id']]
        if kind == 'PMS_RESERVED':
            audit.check(bid not in active, 'pms_reservation_idempotent', bid)
            audit.check(bid not in terminals or at < terminals[bid], 'no_reservation_after_terminal_state', bid)
            active[bid] = period
            for row in rows:
                day = row['stay_date']
                pms_counts[day] += rooms
                audit.check(pms_counts[day] == row['pms_count'] and 0 <= pms_counts[day] <= capacity
                            and row['capacity'] == capacity, 'pms_capacity_and_ledger_balance', (bid, day))
        elif kind == 'PMS_RELEASED':
            audit.check(bid in active and active[bid] == period, 'pms_release_once_for_existing_hold', bid)
            active.pop(bid)
            for row in rows:
                day = row['stay_date']
                pms_counts[day] -= rooms
                audit.check(pms_counts[day] == row['pms_count'] and 0 <= pms_counts[day] <= capacity,
                            'pms_release_balance', (bid, day))
        else:
            audit.check(bid not in allocated, 'physical_allocation_idempotent', bid)
            audit.check(bid not in terminals or at < terminals[bid], 'no_allocation_after_terminal_state', bid)
            allocated.add(bid)
            for row in rows:
                day = row['stay_date']
                physical_counts[day] += rooms
                audit.check(physical_counts[day] == row['physical_count'] and 0 <= physical_counts[day] <= capacity
                            and row['capacity'] == capacity, 'physical_capacity_and_ledger_balance', (bid, day))
    for bid, result in truth.items():
        audit.check(bid in index.bookings, 'truth_booking_exists', bid)
        value = result['truth_label']
        audit.check(value is None or (type(value) is int and value in (0, 1)), 'private_truth_domain', bid)
        nights = by_booking[bid]
        if value == 0:
            audit.check(all(r['provision_status'] == 'AVAILABLE' and r['physical_allocated'] for r in nights),
                        'normal_truth_requires_all_nights_allocated', bid)
        elif value == 1:
            unavailable = [r for r in nights if r['provision_status'] == 'UNAVAILABLE']
            audit.check(len(unavailable) == 1 and unavailable[0]['stay_date'] == result['blocking_night'],
                        'failed_truth_has_only_one_blocking_night', bid)
            audit.check(all(r['provision_status'] in {'UNKNOWN', 'NOT_ATTEMPTED'} for r in nights
                            if r['stay_date'] != result['blocking_night']), 'unattempted_nights_not_marked_failed', bid)
        outcomes = index.outcomes.get(bid, [])
        for observed in outcomes:
            audit.check(value is not None and observed['payload']['label'] == value,
                        'observed_evidence_matches_physical_outcome', observed['event_id'])
            audit.check(result.get('outcome_at') and index.occurred[observed['event_id']] >= _at(result['outcome_at']),
                        'evidence_not_before_final_physical_outcome', observed['event_id'])
    for row in source['booking_nights']:
        day = row['stay_date']
        audit.check(row['physical_allocated'] == (row['booking_id'] in allocated)
                    and row['pms_reserved'] == (row['booking_id'] in active), 'night_flags_match_replayed_ledger', (row['booking_id'], day))
        if 'final_pms_night_count' in row:
            audit.check(row['final_pms_night_count'] == pms_counts[day] and row['physical_night_count'] == physical_counts[day],
                        'final_nightly_counts_match_replayed_ledger', (row['booking_id'], day))


def validate_hotel(source: dict, feature_rows: list[dict], label_rows: list[dict], config: dict) -> dict:
    """Audit one hotel source and its public projections; fail on broken rules."""
    audit = _Audit()
    index = _PublicIndex(source, audit)
    _validate_source(source, index, audit, config)
    feature_map = {_grain(r): r for r in feature_rows}
    label_map = {_grain(r): r for r in label_rows}
    audit.check(len(feature_map) == len(feature_rows) and len(label_map) == len(label_rows)
                and set(feature_map) == set(label_map), 'feature_label_unique_grain_join')
    end = _at(config['simulation']['end'])
    for grain, row in feature_map.items():
        target = date.fromisoformat(row['targetCheckInDate'])
        asof = _at(row['asOf'])
        expected = datetime.combine(target-timedelta(days=config['as_of']['days_before_check_in']),
                                    time.fromisoformat(config['as_of']['local_time']), SEOUL)
        audit.check(asof == expected, 'prediction_clock_d_minus_one', grain)
        audit.check(row['featureSetVersion'] == config['feature_set_version'], 'feature_contract_version', grain)
        for name in FEATURES:
            value = row[name]
            if name == 'isPeakDay':
                audit.check(type(value) is bool, 'feature_boolean_type', grain)
            elif name == 'targetConfirmedBookingCount':
                audit.check(type(value) is int and value >= 0, 'feature_nonnegative_integer', grain)
            else:
                audit.check(type(value) in (int, float) and math.isfinite(value), 'feature_finite_number', (grain, name))
                lower, upper = (-1, 10) if name == 'bookingVolumeGrowth7d' else (0, 1)
                audit.check(lower <= value <= upper, 'feature_range', (grain, name))
        expected_cohort = index.cohort(target.isoformat(), asof)
        audit.check(len(row['cohortBookingIds']) == len(set(row['cohortBookingIds']))
                    and sorted(row['cohortBookingIds']) == expected_cohort
                    and row['targetConfirmedBookingCount'] == len(expected_cohort), 'frozen_observed_confirmed_cohort', grain)
        used = {e['event_id']: e for bid in index.by_checkin.get(target.isoformat(), [])
                if (e := index.status(bid, asof)) is not None}
        history_cfg = config['features']['pastFailureRate90d']
        history = index.historical(asof, history_cfg['history_days'])
        for event in history:
            used[event['event_id']] = event
            confirmation = index.first_confirmation(event['booking_id'], asof)
            used[confirmation['event_id']] = confirmation
        n, f = len(history), sum(e['payload']['label'] == 1 for e in history)
        rate = (f+history_cfg['prior_alpha'])/(n+history_cfg['prior_alpha']+history_cfg['prior_beta'])
        audit.check(math.isclose(row['pastFailureRate90d'], rate, rel_tol=1e-9, abs_tol=1e-10),
                    'past_failure_rate_independent_recomputation', grain)
        growth = config['features']['bookingVolumeGrowth7d']
        recent = index.confirmation_window(asof-timedelta(days=growth['recent_days']), asof, asof)
        previous = index.confirmation_window(asof-timedelta(days=growth['recent_days']+growth['previous_days']),
                                             asof-timedelta(days=growth['recent_days']), asof)
        n7, n28 = len(recent), len(previous)
        for event in recent+previous:
            used[event['event_id']] = event
        pseudocount = growth['additive_pseudocount']
        raw_growth = (n7+pseudocount)/(n28/(growth['previous_days']/growth['recent_days'])+pseudocount)-1
        expected_growth = max(growth['clip'][0], min(growth['clip'][1], raw_growth))
        audit.check(math.isclose(row['bookingVolumeGrowth7d'], expected_growth, abs_tol=1e-9),
                    'booking_growth_independent_recomputation', grain)
        overdue, due_count, overdue_used = index.overdue(asof, config['features']['reconfirmationOverdueRate7d']['window_days'])
        for event in overdue_used:
            used[event['event_id']] = event
        expected_overdue = overdue/due_count if due_count else config['features']['reconfirmationOverdueRate7d']['zero_denominator_value']
        audit.check(math.isclose(row['reconfirmationOverdueRate7d'], expected_overdue, abs_tol=1e-9),
                    'reconfirmation_overdue_independent_recomputation', grain)
        unavailable, probe_count, invalid_probes, inventory_used = index.inventory(target.isoformat(), asof, config['features']['inventoryUnavailableRate7d']['window_days'])
        for event in inventory_used:
            used[event['event_id']] = event
        expected_inventory = unavailable/probe_count if probe_count else config['features']['inventoryUnavailableRate7d']['zero_denominator_value']
        audit.check(math.isclose(row['inventoryUnavailableRate7d'], expected_inventory, abs_tol=1e-9),
                    'inventory_signal_independent_recomputation', grain)
        peak = config['features']['isPeakDay']
        expected_peak = target.weekday() in peak['check_in_weekdays_monday_zero'] or target.month in peak['peak_months'] \
            or target.isoformat() in peak['synthetic_peak_dates']
        audit.check(row['isPeakDay'] == expected_peak, 'peak_calendar_independent_recomputation', grain)

        event_ids = sorted(used)
        selected = list(used.values())
        audit.check(all(_kind(e) in PUBLIC_TYPES for e in selected), 'feature_lineage_public_events_only', grain)
        audit.check(all(index.known(e, asof) for e in selected), 'feature_lineage_available_before_prediction', grain)
        expected_digest = hashlib.sha256('\n'.join(event_ids).encode()).hexdigest()
        audit.check(row['lineageDigest'] == expected_digest and row['lineageEventCount'] == len(event_ids),
                    'feature_lineage_digest_independent_recomputation', grain)
        if 'lineageEventIds' in row:
            audit.check(len(row['lineageEventIds']) == len(set(row['lineageEventIds']))
                        and sorted(row['lineageEventIds']) == event_ids, 'feature_lineage_full_ids_match', grain)
        for key, times in (('featureMaxAvailableAt', index.available), ('featureMaxOccurredAt', index.occurred)):
            expected_time = max((times[e['event_id']] for e in selected), default=None)
            actual = _at(row[key]) if row.get(key) else None
            audit.check(actual == expected_time, 'lineage_maximum_timestamps_match', (grain, key))
        expected_audit = {
            'historyKnownCount': n, 'historyFailedCount': f, 'historyRawRate': f/n if n else None,
            'historyMissing': n == 0, 'growthRecentCount': n7, 'growthPreviousCount': n28,
            'reconfirmationEligibleCount': due_count, 'reconfirmationOverdueCount': overdue,
            'reconfirmationMissing': due_count == 0, 'inventoryValidCount': probe_count,
            'inventoryUnavailableCount': unavailable, 'inventoryInvalidCount': invalid_probes,
            'inventoryMissing': probe_count == 0,
        }
        audit.check(all(row.get(key) == value for key, value in expected_audit.items()),
                    'feature_support_and_missing_audits_match', grain)

        known = [index.outcome(bid, end) for bid in expected_cohort]
        failed = [e for e in known if e is not None and e['payload']['label'] == 1]
        known_count = sum(e is not None for e in known)
        target_value, available, status = None, None, 'UNKNOWN'
        if not expected_cohort:
            status = 'NOT_ELIGIBLE'
        elif failed:
            target_value, available, status = 1, min(index.available[e['event_id']] for e in failed), 'KNOWN'
        elif known_count == len(expected_cohort):
            target_value, available, status = 0, max(index.available[e['event_id']] for e in known), 'KNOWN'
        label = label_map[grain]
        audit.check(label['target'] == target_value and label['labelStatus'] == status
                    and (_at(label['labelAvailableAt']) if label.get('labelAvailableAt') else None) == available,
                    'hotel_day_or_labels_only_from_observed_evidence', grain)
        expected_counts = {'cohortSize': len(expected_cohort), 'knownBookingCount': known_count,
                           'failedBookingCount': len(failed), 'unknownBookingCount': len(expected_cohort)-known_count}
        audit.check(all(label[k] == value for k, value in expected_counts.items()), 'label_cohort_count_balance', grain)
    report = audit.report()
    report.update({'hotelId': source['hotel'].get('id', source['hotel'].get('hotel_id')),
                   'booking_count': len(index.bookings), 'event_count': len(index.events), 'panel_rows': len(feature_rows),
                   'label_status_counts': dict(Counter(r['labelStatus'] for r in label_rows)),
                   'class_counts': dict(Counter(str(r['target']) for r in label_rows if r['labelStatus'] == 'KNOWN'))})
    return report


def validate_dataset(rows: list[dict], config: dict) -> dict:
    """Audit complete calendar coverage and label eligibility without prevalence targets."""
    audit = _Audit()
    keys = [_grain(r) for r in rows]
    audit.check(len(keys) == len(set(keys)), 'dataset_grain_unique')
    first, last = date.fromisoformat(config['calendar_panel']['start']), date.fromisoformat(config['calendar_panel']['end'])
    expected_dates = {(first+timedelta(days=i)).isoformat() for i in range((last-first).days+1)}
    by_hotel = defaultdict(list)
    for row in rows:
        by_hotel[row['hotelId']].append(row)
        audit.check(row['targetCheckInDate'] in expected_dates, 'dataset_target_date_in_calendar', _grain(row))
        target = row['target']
        cohort = row['targetConfirmedBookingCount']
        known, failed, unknown = row['knownBookingCount'], row['failedBookingCount'], row['unknownBookingCount']
        audit.check(row['cohortSize'] == cohort and 0 <= failed <= known <= cohort
                    and known+unknown == cohort, 'dataset_cohort_counts_balance', _grain(row))
        if row['labelStatus'] == 'KNOWN':
            audit.check(type(target) is int and target in (0, 1) and bool(row.get('labelAvailableAt'))
                        and row['targetConfirmedBookingCount'] > 0, 'known_label_has_target_time_and_cohort', _grain(row))
            audit.check(_at(row['labelAvailableAt']) <= _at(config['simulation']['end']), 'label_mature_by_extraction', _grain(row))
            audit.check((target == 1 and failed > 0) or (target == 0 and failed == 0 and known == cohort),
                        'dataset_hotel_day_or_semantics', _grain(row))
        else:
            audit.check(target is None and not row.get('labelAvailableAt') and row['labelStatus'] in {'UNKNOWN', 'NOT_ELIGIBLE'},
                        'unknown_never_coerced_to_zero', _grain(row))
            audit.check((row['labelStatus'] == 'NOT_ELIGIBLE') == (row['targetConfirmedBookingCount'] == 0),
                        'empty_cohort_is_not_eligible', _grain(row))
            audit.check(failed == 0 and (cohort == 0 or unknown > 0), 'unknown_has_no_verified_failure_or_full_normal_proof', _grain(row))
    selected_hotels = config.get('generation', {}).get('selected_hotel_indices')
    hotel_count = len(selected_hotels) if selected_hotels is not None else config['simulation']['hotel_count']
    audit.check(len(by_hotel) == hotel_count, 'dataset_hotel_universe_complete', len(by_hotel))
    for hid, hotel_rows in by_hotel.items():
        audit.check({r['targetCheckInDate'] for r in hotel_rows} == expected_dates and len(hotel_rows) == len(expected_dates),
                    'dataset_calendar_panel_complete', hid)
    report = audit.report()
    statuses = Counter(r['labelStatus'] for r in rows)
    eligible = len(rows)-statuses['NOT_ELIGIBLE']
    report.update({'panel_rows': len(rows), 'hotels': len(by_hotel), 'days_per_hotel': len(expected_dates),
                   'eligible_rows': eligible, 'label_status_counts': dict(statuses),
                   'known_label_coverage': statuses['KNOWN']/eligible if eligible else None,
                   'class_counts': dict(Counter(str(r['target']) for r in rows if r['labelStatus'] == 'KNOWN')),
                   'minimum_failure_prevalence_enforced': False})
    return report
