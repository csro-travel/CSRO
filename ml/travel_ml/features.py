"""Point-in-time, six-field feature projection from observed source events only.

The final bookings table, private truth and latent simulator fields are never
read.  Each call processes one hotel shard.  Timestamp/date indexes keep rolling
windows local to the hotel rather than repeatedly scanning the whole source.
"""
from __future__ import annotations

from bisect import bisect_left
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
import hashlib
from zoneinfo import ZoneInfo

FIELD_NAMES = (
    "pastFailureRate90d", "targetConfirmedBookingCount", "bookingVolumeGrowth7d",
    "reconfirmationOverdueRate7d", "inventoryUnavailableRate7d", "isPeakDay",
)
_PUBLIC_TYPES = frozenset(("BOOKING_STATUS", "RECONFIRMATION_REQUEST", "HOTEL_REPLY",
                           "INVENTORY_PROBE", "OBSERVED_OUTCOME"))


def _dt(value: str, zone: ZoneInfo) -> datetime:
    result = datetime.fromisoformat(value)
    if result.tzinfo is None or result.utcoffset() is None:
        raise ValueError("Source timestamps require timezone offsets")
    return result.astimezone(zone)


@dataclass(frozen=True)
class _Event:
    event_id: str
    booking_id: str | None
    kind: str
    occurred: datetime
    available: datetime
    payload: dict

    def visible(self, cutoff: datetime) -> bool:
        return self.occurred <= cutoff and self.available <= cutoff


def _order(event: _Event) -> tuple:
    return event.occurred, event.event_id


class _Index:
    def __init__(self, source: dict, hotel_id: str, zone: ZoneInfo):
        self.statuses: dict[str, list[_Event]] = defaultdict(list)
        self.candidates: dict[str, set[str]] = defaultdict(set)
        self.requests: dict[str, list[_Event]] = defaultdict(list)
        self.replies: dict[str, list[_Event]] = defaultdict(list)
        self.inventory: dict[str, list[_Event]] = defaultdict(list)
        confirmations, outcomes, due_requests = [], [], []
        seen = {}
        for raw in source["events"]:
            kind = raw["event_type"]
            if kind not in _PUBLIC_TYPES:
                continue
            if str(raw["hotel_id"]) != hotel_id:
                raise ValueError("build_features expects events from one hotel shard")
            event_id = str(raw["event_id"])
            if event_id in seen:
                if raw != seen[event_id]:
                    raise ValueError(f"Conflicting duplicate source event ID: {event_id}")
                continue
            seen[event_id] = raw
            booking = str(raw["booking_id"]) if raw.get("booking_id") is not None else None
            event = _Event(event_id, booking, kind, _dt(raw["occurred_at"], zone),
                           _dt(raw["available_at"], zone), raw["payload"])
            p = event.payload
            if kind == "BOOKING_STATUS":
                if booking is None:
                    raise ValueError("BOOKING_STATUS requires booking_id")
                self.statuses[booking].append(event)
                if p["status"] == "CONFIRMED":
                    self.candidates[p["check_in_date"]].add(booking)
                    confirmations.append(event)
            elif kind == "RECONFIRMATION_REQUEST":
                if booking is None:
                    raise ValueError("RECONFIRMATION_REQUEST requires booking_id")
                self.requests[booking].append(event)
                due_requests.append((_dt(p["due_at"], zone), event))
            elif kind == "HOTEL_REPLY":
                if booking is not None:
                    self.replies[booking].append(event)
            elif kind == "INVENTORY_PROBE":
                self.inventory[p["target_check_in_date"]].append(event)
            elif kind == "OBSERVED_OUTCOME":
                label = p.get("label")
                if label is None:
                    continue
                if type(label) is not int or label not in (0, 1) or booking is None:
                    raise ValueError("OBSERVED_OUTCOME requires booking_id and integer label 0/1")
                outcomes.append((date.fromisoformat(p["check_in_date"]), event))
        for mapping in (self.statuses, self.requests, self.replies, self.inventory):
            for values in mapping.values():
                values.sort(key=_order)
        self.confirmations = sorted(confirmations, key=_order)
        self.confirmation_times = [event.occurred for event in self.confirmations]
        self.outcomes = sorted(outcomes, key=lambda pair: (pair[0], _order(pair[1])))
        self.outcome_dates = [pair[0] for pair in self.outcomes]
        self.due_requests = sorted(due_requests, key=lambda pair: (pair[0], _order(pair[1])))
        self.due_times = [pair[0] for pair in self.due_requests]

    def status_at(self, booking: str, cutoff: datetime) -> _Event | None:
        return next((event for event in reversed(self.statuses.get(booking, ()))
                     if event.visible(cutoff)), None)

    def first_confirmed_at(self, booking: str, cutoff: datetime) -> _Event | None:
        return next((event for event in self.statuses.get(booking, ())
                     if event.payload["status"] == "CONFIRMED" and event.visible(cutoff)), None)

    def first_request_at(self, booking: str, cutoff: datetime) -> _Event | None:
        return next((event for event in self.requests.get(booking, ())
                     if event.visible(cutoff)), None)


def _matches_reply(reply: _Event, requests: list[_Event], status: _Event,
                   due: datetime, cutoff: datetime) -> bool:
    p = reply.payload
    if not (reply.visible(cutoff) and reply.available <= due
            and p.get("http_status") == 200 and p.get("schema_valid") is True):
        return False
    matching = [request for request in requests
                if request.payload["request_id"] == p.get("request_id")
                and request.visible(cutoff) and request.occurred <= reply.occurred]
    if not matching:
        return False
    expected_first = date.fromisoformat(status.payload["check_in_date"])
    expected_last = date.fromisoformat(status.payload["check_out_date"])
    # schema_valid is the upstream observation validator's verdict.  When the
    # raw nights are included (the simulator does include them), recheck them.
    if "nights" in p:
        nights = p["nights"]
        if not isinstance(nights, list):
            return False
        dates = [night.get("stay_date") for night in nights if isinstance(night, dict)]
        expected = {(expected_first + timedelta(days=i)).isoformat()
                    for i in range((expected_last - expected_first).days)}
        if len(dates) != len(nights) or len(dates) != len(set(dates)) or set(dates) != expected:
            return False
        if any(night.get("status") not in {"AVAILABLE", "UNAVAILABLE", "UNKNOWN"} for night in nights):
            return False
    return True


def build_features(source: dict, config: dict, *, include_lineage_ids: bool = False) -> list[dict]:
    """Return one row per configured target date for this observed hotel shard.

    ``lineageDigest`` hashes sorted distinct event IDs separated by ``\n``.
    Full IDs are optional because repeating a 90-day history in every panel row
    would expand the full-scale artifact considerably.  Cohort IDs always remain
    explicit so label aggregation uses the exact prediction-time population.
    """
    zone = ZoneInfo(config.get("timezone", "Asia/Seoul"))
    hotel = source["hotel"]
    hotel_id = str(hotel["hotel_id"])
    idx = _Index(source, hotel_id, zone)
    first = date.fromisoformat(config["calendar_panel"]["start"])
    last = date.fromisoformat(config["calendar_panel"]["end"])
    if last < first:
        raise ValueError("calendar_panel.end precedes start")
    clock = time.fromisoformat(config["as_of"]["local_time"])
    settings = config["features"]
    if tuple(settings["ordered_names"]) != FIELD_NAMES:
        raise ValueError("The feature order differs from risk-features-0.2")
    alpha = settings["pastFailureRate90d"]["prior_alpha"]
    beta = settings["pastFailureRate90d"]["prior_beta"]
    if alpha <= 0 or beta <= 0:
        raise ValueError("History prior parameters must be positive")
    growth = settings["bookingVolumeGrowth7d"]
    peak = settings["isPeakDay"]
    peak_dates = set(peak["synthetic_peak_dates"])
    result = []
    for offset in range((last - first).days + 1):
        target = first + timedelta(days=offset)
        cutoff = datetime.combine(target - timedelta(days=config["as_of"]["days_before_check_in"]), clock, zone)
        used: dict[str, _Event] = {}

        def use(event: _Event | None) -> None:
            if event is not None:
                if not event.visible(cutoff):
                    raise ValueError("Feature lineage includes a future event")
                used[event.event_id] = event

        confirmed_cache: dict[str, _Event | None] = {}

        def confirmed(booking: str) -> _Event | None:
            if booking not in confirmed_cache:
                confirmed_cache[booking] = idx.first_confirmed_at(booking, cutoff)
            return confirmed_cache[booking]

        cohort = []
        for booking in sorted(idx.candidates.get(target.isoformat(), ())):
            status = idx.status_at(booking, cutoff)
            use(status)
            if (status is not None and status.payload["status"] == "CONFIRMED"
                    and status.payload.get("channel") == "PLATFORM"
                    and status.payload["check_in_date"] == target.isoformat()):
                cohort.append(booking)

        history_start = cutoff.date() - timedelta(days=settings["pastFailureRate90d"]["history_days"])
        a, b = bisect_left(idx.outcome_dates, history_start), bisect_left(idx.outcome_dates, cutoff.date())
        known = {}
        for _, event in idx.outcomes[a:b]:
            if not event.visible(cutoff):
                continue
            confirmation = confirmed(event.booking_id)
            if confirmation is None or confirmation.payload.get("channel") != "PLATFORM":
                continue
            old = known.get(event.booking_id)
            if old is not None and old.payload["label"] != event.payload["label"]:
                raise ValueError("Semantic outcome retractions are outside v0.2")
            if old is None or (event.available, _order(event)) < (old.available, _order(old)):
                known[event.booking_id] = event
        for booking, event in known.items():
            use(event)
            use(confirmed(booking))
        n_known = len(known)
        n_failed = sum(event.payload["label"] for event in known.values())
        history_rate = (n_failed + alpha) / (n_known + alpha + beta)

        recent_start = cutoff - timedelta(days=growth["recent_days"])
        previous_start = recent_start - timedelta(days=growth["previous_days"])
        a, b = bisect_left(idx.confirmation_times, previous_start), bisect_left(idx.confirmation_times, cutoff)
        candidate_bookings = {event.booking_id for event in idx.confirmations[a:b] if event.visible(cutoff)}
        recent_count, previous_count = 0, 0
        for booking in candidate_bookings:
            event = confirmed(booking)
            if event is None or event.payload.get("channel") != "PLATFORM" or event.occurred < previous_start:
                continue
            if not event.occurred < cutoff:
                continue
            use(event)
            if event.occurred >= recent_start:
                recent_count += 1
            else:
                previous_count += 1
        pseudocount = growth["additive_pseudocount"]
        weeks = growth["previous_days"] / growth["recent_days"]
        raw_growth = (recent_count + pseudocount) / (previous_count / weeks + pseudocount) - 1
        clipped_growth = min(growth["clip"][1], max(growth["clip"][0], raw_growth))

        recon_start = cutoff - timedelta(days=settings["reconfirmationOverdueRate7d"]["window_days"])
        a, b = bisect_left(idx.due_times, recon_start), bisect_left(idx.due_times, cutoff)
        candidate_requests = {event.booking_id for _, event in idx.due_requests[a:b] if event.visible(cutoff)}
        eligible_count, overdue_count = 0, 0
        for booking in candidate_requests:
            request = idx.first_request_at(booking, cutoff)
            if request is None:
                continue
            due = _dt(request.payload["due_at"], zone)
            if not recon_start <= due < cutoff:
                continue
            status = idx.status_at(booking, due)
            if status is None or status.payload["status"] != "CONFIRMED" or status.payload.get("channel") != "PLATFORM":
                continue
            use(request)
            use(status)
            eligible_count += 1
            timely = [reply for reply in idx.replies.get(booking, ())
                      if _matches_reply(reply, idx.requests[booking], status, due, cutoff)]
            if timely:
                use(min(timely, key=lambda reply: (reply.available, _order(reply))))
            else:
                overdue_count += 1

        inventory_start = cutoff - timedelta(days=settings["inventoryUnavailableRate7d"]["window_days"])
        probes = {}
        for event in idx.inventory.get(target.isoformat(), ()):
            if not event.visible(cutoff):
                continue
            request_id = str(event.payload.get("request_id", event.event_id))
            # Same request ID is an immutable observation, not a new query.
            probes.setdefault(request_id, event)
        valid_inventory, unavailable_inventory, invalid_inventory = 0, 0, 0
        for event in probes.values():
            if not inventory_start <= event.occurred < cutoff:
                continue
            p = event.payload
            if not (p.get("check_out_date") == (target + timedelta(days=1)).isoformat()
                    and p.get("room_type") == "STANDARD" and p.get("adults") == 2
                    and p.get("rooms") == 1 and p.get("currency") == "KRW"):
                continue
            use(event)
            count = p.get("available_rooms")
            if p.get("http_status") != 200 or type(count) is not int or count < 0:
                invalid_inventory += 1
                continue
            valid_inventory += 1
            unavailable_inventory += int(count < 1)

        event_ids = sorted(used)
        row = {
            "hotelId": hotel_id, "targetCheckInDate": target.isoformat(),
            "asOf": cutoff.isoformat(timespec="seconds"),
            "featureSetVersion": config["feature_set_version"],
            "pastFailureRate90d": history_rate,
            "targetConfirmedBookingCount": len(cohort),
            "bookingVolumeGrowth7d": clipped_growth,
            "reconfirmationOverdueRate7d": overdue_count / eligible_count if eligible_count else settings["reconfirmationOverdueRate7d"]["zero_denominator_value"],
            "inventoryUnavailableRate7d": unavailable_inventory / valid_inventory if valid_inventory else settings["inventoryUnavailableRate7d"]["zero_denominator_value"],
            "isPeakDay": target.weekday() in peak["check_in_weekdays_monday_zero"] or target.month in peak["peak_months"] or target.isoformat() in peak_dates,
            "cohortBookingIds": cohort,
            "historyKnownCount": n_known, "historyFailedCount": n_failed,
            "historyRawRate": n_failed / n_known if n_known else None,
            "historyMissing": n_known == 0,
            "growthRecentCount": recent_count, "growthPreviousCount": previous_count,
            "growthRaw": raw_growth,
            "reconfirmationEligibleCount": eligible_count,
            "reconfirmationOverdueCount": overdue_count,
            "reconfirmationMissing": eligible_count == 0,
            "inventoryValidCount": valid_inventory,
            "inventoryUnavailableCount": unavailable_inventory,
            "inventoryInvalidCount": invalid_inventory,
            "inventoryMissing": valid_inventory == 0,
            "lineageEventCount": len(event_ids),
            "lineageDigest": hashlib.sha256("\n".join(event_ids).encode()).hexdigest(),
            "featureMaxOccurredAt": max((event.occurred for event in used.values()), default=None).isoformat(timespec="seconds") if used else None,
            "featureMaxAvailableAt": max((event.available for event in used.values()), default=None).isoformat(timespec="seconds") if used else None,
        }
        if include_lineage_ids:
            row["lineageEventIds"] = event_ids
        result.append(row)
    return result
