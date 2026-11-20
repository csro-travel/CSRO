"""Black-box process/feature/label integration and independent fault injection.

These small simulations are test fixtures.  They do not tune the final seed42
experiment or require an arbitrary real-world failure prevalence.
"""
from copy import deepcopy
from datetime import date, datetime, time, timedelta, timezone
import json
from pathlib import Path
import unittest

from travel_ml.features import build_features
from travel_ml.labels import build_labels, split_rows
from travel_ml.simulator import simulate_hotel
from travel_ml.validation import validate_dataset, validate_hotel

SEOUL = timezone(timedelta(hours=9))
CONFIG = Path(__file__).resolve().parents[1]/'config/simulator_v0.2.json'


def small_config():
    config = json.loads(CONFIG.read_text(encoding='utf-8-sig'))
    config['calendar_panel'] = {'start': '2025-01-01', 'end': '2025-01-14'}
    config['simulation']['end'] = '2025-01-26T00:00:00+09:00'
    config['simulation']['internal_arrival_demand_end_date'] = '2025-04-14'
    config['generation'] = {'is_pilot': True, 'selected_hotel_indices': [96]}
    return config


class ProcessIntegrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.config = small_config()
        cls.source = simulate_hotel(cls.config, 96)
        cls.features = build_features(cls.source, cls.config, include_lineage_ids=True)
        cls.labels = build_labels(cls.source, cls.features, cls.config)

    def test_process_features_labels_pass_independent_audit(self):
        report = validate_hotel(self.source, self.features, self.labels, self.config)
        self.assertEqual(report['status'], 'PASS')
        self.assertGreater(report['assertion_count'], 10000)
        rows = [{**f, **label} for f, label in zip(self.features, self.labels)]
        report = validate_dataset(rows, self.config)
        self.assertEqual(report['panel_rows'], 14)
        self.assertFalse(report['minimum_failure_prevalence_enforced'])

    def test_seed_replay_and_hotel_run_order_are_independent(self):
        # Running an unrelated hotel cannot advance this hotel's component RNGs.
        simulate_hotel(self.config, 1)
        replay = simulate_hotel(self.config, 96)
        self.assertEqual(replay, self.source)

    def test_silent_delivery_drop_can_recover_normally(self):
        recovered = [t for t in self.source['truth']
                     if t['private_initial_delivery_dropped'] and t['truth_label'] == 0]
        self.assertTrue(recovered, 'The seeded process should exercise drop→recovery→fulfilled')
        for row in recovered:
            nights = [n for n in self.source['booking_nights'] if n['booking_id'] == row['booking_id']]
            self.assertTrue(all(n['provision_status'] == 'AVAILABLE' for n in nights))

    def test_atomic_rejection_does_not_mark_every_night_unavailable(self):
        multi_night_failures = []
        for result in self.source['truth']:
            nights = [n for n in self.source['booking_nights'] if n['booking_id'] == result['booking_id']]
            if result['truth_label'] == 1 and len(nights) > 1:
                multi_night_failures.append(result)
                self.assertEqual(sum(n['provision_status'] == 'UNAVAILABLE' for n in nights), 1)
                self.assertTrue(all(n['provision_status'] in {'UNKNOWN', 'NOT_ATTEMPTED'}
                                    for n in nights if n['stay_date'] != result['blocking_night']))
        self.assertTrue(multi_night_failures)

    def test_cancel_after_cutoff_stays_in_frozen_cohort_without_resurrection(self):
        bookings = {b['booking_id']: b for b in self.source['bookings']}
        eligible = []
        for event in self.source['events']:
            if event['event_type'] != 'BOOKING_STATUS' or event['payload']['status'] != 'CANCELLED':
                continue
            booking = bookings[event['booking_id']]
            cutoff = datetime.combine(date.fromisoformat(booking['check_in_date'])-timedelta(days=1), time(9), SEOUL)
            cancelled_at = datetime.fromisoformat(event['occurred_at'])
            forbidden = [e for e in self.source['events'] if e.get('booking_id') == booking['booking_id']
                         and e['event_type'] in {'PMS_RESERVED', 'PHYSICAL_ALLOCATED'}
                         and datetime.fromisoformat(e['occurred_at']) >= cancelled_at]
            self.assertEqual(forbidden, [])
            if booking['channel'] == 'PLATFORM' and datetime.fromisoformat(booking['created_at']) <= cutoff < cancelled_at:
                eligible.append(booking)
        self.assertTrue(eligible)
        booking = eligible[0]
        config = deepcopy(self.config)
        config['calendar_panel'] = {'start': booking['check_in_date'], 'end': booking['check_in_date']}
        features = build_features(self.source, config)
        self.assertIn(booking['booking_id'], features[0]['cohortBookingIds'])
        labels = build_labels(self.source, features, config)
        self.assertGreaterEqual(labels[0]['unknownBookingCount'], 1)

    def test_missing_outbox_is_a_logged_unknown_not_truth_fallback(self):
        lost = [t for t in self.source['truth'] if t['truth_label'] in (0, 1) and t['private_source_outbox_lost']]
        self.assertTrue(lost)
        observed = {e['booking_id'] for e in self.source['events'] if e['event_type'] == 'OBSERVED_OUTCOME'}
        logged = {e['booking_id'] for e in self.source['events'] if e['event_type'] == 'EVIDENCE_OUTBOX_LOST'}
        for row in lost:
            self.assertIn(row['booking_id'], logged)
            self.assertNotIn(row['booking_id'], observed)
            self.assertIsNone(row['observed_label'])

    def test_future_events_and_private_truth_cannot_change_prior_features(self):
        cutoff = datetime.fromisoformat(self.features[0]['asOf'])
        truncated = {'hotel': self.source['hotel'], 'events': [e for e in self.source['events']
                     if datetime.fromisoformat(e['occurred_at']) <= cutoff
                     and datetime.fromisoformat(e['available_at']) <= cutoff]}
        # No bookings/final-state/night/truth tables are supplied at all.
        projected = build_features(truncated, self.config, include_lineage_ids=True)
        self.assertEqual(projected[0], self.features[0])
        mutated = deepcopy(self.source)
        for event in mutated['events']:
            if datetime.fromisoformat(event['occurred_at']) > cutoff:
                if event['event_type'] == 'OBSERVED_OUTCOME':
                    event['payload']['label'] = 1-event['payload']['label']
                elif event['event_type'] == 'INVENTORY_PROBE' and event['payload'].get('available_rooms') is not None:
                    event['payload']['available_rooms'] += 1000
        self.assertEqual(build_features(mutated, self.config, include_lineage_ids=True)[0], self.features[0])

    def test_losing_one_normal_observation_does_not_create_a_negative_label(self):
        index = next(i for i, row in enumerate(self.labels) if row['target'] == 0)
        booking_id = self.features[index]['cohortBookingIds'][0]
        source = deepcopy(self.source)
        source['events'] = [e for e in source['events'] if not (e['event_type'] == 'OBSERVED_OUTCOME' and e['booking_id'] == booking_id)]
        labels = build_labels(source, self.features, self.config)
        self.assertEqual(labels[index]['labelStatus'], 'UNKNOWN')
        self.assertIsNone(labels[index]['target'])
        self.assertIsNone(labels[index]['labelAvailableAt'])

    def test_compact_lineage_is_independently_verified(self):
        compact = build_features(self.source, self.config)
        self.assertNotIn('lineageEventIds', compact[0])
        self.assertEqual(validate_hotel(self.source, compact, self.labels, self.config)['status'], 'PASS')
        compact[0]['lineageDigest'] = '0'*64
        with self.assertRaisesRegex(ValueError, 'feature_lineage_digest_independent_recomputation'):
            validate_hotel(self.source, compact, self.labels, self.config)

    def test_auditor_rejects_changed_feature_formula(self):
        features = deepcopy(self.features)
        features[0]['pastFailureRate90d'] = 1.
        with self.assertRaisesRegex(ValueError, 'past_failure_rate_independent_recomputation'):
            validate_hotel(self.source, features, self.labels, self.config)

    def test_auditor_rejects_changed_capacity_ledger(self):
        source = deepcopy(self.source)
        reserve = next(e for e in source['events'] if e['event_type'] == 'PMS_RESERVED')
        reserve['payload']['nights'][0]['pms_count'] += 1
        with self.assertRaisesRegex(ValueError, 'pms_capacity_and_ledger_balance'):
            validate_hotel(source, self.features, self.labels, self.config)

    def test_calendar_panel_cannot_silently_drop_unknown_rows(self):
        rows = [{**f, **label} for f, label in zip(self.features, self.labels)]
        unknown_index = next(i for i, row in enumerate(rows) if row['labelStatus'] == 'UNKNOWN')
        rows.pop(unknown_index)
        with self.assertRaisesRegex(ValueError, 'dataset_calendar_panel_complete'):
            validate_dataset(rows, self.config)

    def test_split_cutoff_equality_is_purged_except_fixed_test_extraction(self):
        config = json.loads(CONFIG.read_text())
        cases = [
            ('2025-09-30', config['evaluation']['train_label_available_before'], 'train', 'PURGED_LABEL_NOT_AVAILABLE'),
            ('2025-10-31', config['evaluation']['validation_label_available_before'], 'validation', 'PURGED_LABEL_NOT_AVAILABLE'),
            ('2025-12-31', config['evaluation']['test_label_available_by'], 'test', 'INCLUDED'),
        ]
        for day, available, split, status in cases:
            row = {'hotelId': 'HOTEL-096', 'targetCheckInDate': day, 'asOf': '2025-01-01T09:00:00+09:00',
                   'labelStatus': 'KNOWN', 'target': 0, 'labelAvailableAt': available}
            parts, audit = split_rows([row], config)
            self.assertEqual(audit[0]['assignedSplit'], split)
            self.assertEqual(audit[0]['status'], status)
            self.assertEqual(len(parts[split]), int(status == 'INCLUDED'))


if __name__ == '__main__':
    unittest.main()
