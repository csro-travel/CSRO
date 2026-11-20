"""Process-based booking/PMS/physical-stay simulation for ML data rules v0.2.

No prediction feature or model score is consulted here.  Public observations
are emitted by transport, HTTP and source-evidence processes.  Private physical
truth is exported separately and is never a fallback for missing observations.
"""
from __future__ import annotations

from collections import Counter, defaultdict
from datetime import date, datetime, time, timedelta, timezone
import heapq
from typing import Any

import numpy as np

SEOUL = timezone(timedelta(hours=9))
PRIORITY = {"release": 0, "service": 1, "reservation": 2, "query": 3, "arrival": 5}


def _dt(value: str) -> datetime:
    result = datetime.fromisoformat(value)
    if result.tzinfo is None:
        raise ValueError("Simulator timestamps require a UTC offset")
    return result.astimezone(SEOUL)


def _iso(value: datetime) -> str:
    return value.isoformat(timespec="seconds")


def _local(day: date, clock: str) -> datetime:
    return datetime.combine(day, time.fromisoformat(clock), SEOUL)


def _dates(start: str, end: str) -> list[str]:
    first, last = date.fromisoformat(start), date.fromisoformat(end)
    return [(first + timedelta(days=i)).isoformat() for i in range((last - first).days)]


class HotelSimulator:
    """One hotel shard with independent component RNGs and an actual event queue.

    Periodic cache ticks are real queue actions.  A dirty-date index lets each
    tick replace only stock that could have changed; unchanged dates have the
    same PMS snapshot without rescanning the entire booking horizon.
    """

    def __init__(self, config: dict, hotel_index: int):
        self.config, self.spec = config, config["simulation"]
        if self.spec["rooms_per_booking"] != 1:
            raise ValueError("v0.2 physical-room ledger requires one room per booking")
        if not 1 <= hotel_index <= self.spec["hotel_count"]:
            raise ValueError("hotel_index is outside the configured hotel universe")
        self.index = hotel_index
        self.hotel_id = f"HOTEL-{hotel_index:03d}"
        self.start, self.end = _dt(self.spec["start"]), _dt(self.spec["end"])
        if self.end <= self.start:
            raise ValueError("Simulation end must follow start")
        self.now = self.start
        self.rng = {component: np.random.default_rng(np.random.SeedSequence(
            [config["seed"], hotel_index, component])) for component in range(1, 10)}
        count = 0
        for profile_name in ("stable", "degraded", "fragile"):
            profile = self.spec["profiles"][profile_name]
            count += profile["hotel_count"]
            if hotel_index <= count:
                self.profile_name, self.profile = profile_name, profile
                break
        else:
            raise ValueError("Profile counts do not cover the hotel index")
        quality = self.rng[1]
        low, high = self.spec["hotel_room_capacity_inclusive"]
        self.capacity = int(quality.integers(low, high + 1))
        if self.capacity < 1:
            raise ValueError("Physical room capacity must be positive")
        self.utilization = float(quality.uniform(*self.spec["base_utilization_uniform"]))
        self.drop_probability = float(quality.uniform(*self.profile["initial_delivery_drop_probability_uniform"]))
        self.cache_minutes = float(quality.uniform(*self.profile["cache_sync_interval_minutes_uniform"]))
        if self.cache_minutes <= 0:
            raise ValueError("Cache interval must be positive")
        self.queue: list[tuple] = []
        self.serial = 0
        self.event_serial = 0
        self.events: list[dict] = []
        self.bookings: dict[str, dict] = {}
        self.pms: dict[str, set[str]] = defaultdict(set)
        self.physical: dict[str, set[str]] = defaultdict(set)
        self.cache: dict[str, int] = {}
        self.dirty: set[str] = set()
        self.manual: dict[str, dict] = {}
        self.outage_until = self.start - timedelta(seconds=1)
        self.counters: Counter = Counter()
        self.mechanisms: Counter = Counter()
        self.max_pms = self.max_physical = 0
        self.calendar = config["features"]["isPeakDay"]

    def _push(self, at: datetime, priority: str, kind: str, payload: Any = None,
              stable: str = "") -> None:
        if at < self.start or at > self.end:
            return
        self.serial += 1
        # Kind plus stable ID resolves simultaneous actions reproducibly.
        heapq.heappush(self.queue, (at, PRIORITY[priority], kind + ":" + stable,
                                  self.serial, kind, payload))

    def _emit(self, kind: str, booking: dict | None = None, payload: dict | None = None,
              occurred: datetime | None = None) -> None:
        self.event_serial += 1
        self.events.append({
            "event_id": f"EV-H{self.index:03d}-{self.event_serial:09d}",
            "hotel_id": self.hotel_id,
            "booking_id": booking["booking_id"] if booking else None,
            "event_type": kind,
            "occurred_at": _iso(occurred or self.now),
            "available_at": _iso(self.now),
            "payload": payload or {},
        })
        self.counters[kind] += 1

    def _down(self) -> bool:
        return self.now < self.outage_until

    def _peak(self, day: date) -> bool:
        return (day.weekday() in self.calendar["check_in_weekdays_monday_zero"]
                or day.month in self.calendar["peak_months"]
                or day.isoformat() in self.calendar["synthetic_peak_dates"])

    def _guard(self, booking: dict, action: str) -> bool:
        reason = None
        if booking["cancelled"]:
            reason = "CANCELLED"
        elif booking["no_show"]:
            reason = "NO_SHOW"
        elif booking["terminal_failure"]:
            reason = "TERMINAL_FAILURE"
        elif booking["closed"]:
            reason = "STAY_COMPLETED"
        elif self.now > booking["recovery_deadline"] and not booking["pms_reserved"]:
            reason = "RECOVERY_DEADLINE_PASSED"
        if reason:
            self._emit("ACTION_GUARD", booking, {"action": action, "reason": reason})
            self.counters["guarded_actions"] += 1
            return False
        return True

    def _stock_rows(self, nights: list[str]) -> list[dict]:
        return [{"stay_date": night, "pms_count": len(self.pms[night]),
                 "capacity": self.capacity} for night in nights]

    def _reserve(self, booking: dict, reason: str) -> bool:
        if not self._guard(booking, reason):
            return False
        if booking["pms_reserved"]:
            self._emit("PMS_ACK_DUPLICATE", booking, {"message_id": booking["message_id"], "reason": reason})
            self.counters["duplicate_acks"] += 1
            return True
        blocked = next((night for night in booking["nights"]
                        if len(self.pms[night]) + booking["rooms"] > self.capacity), None)
        if blocked:
            booking["last_blocking_night"] = blocked
            self._emit("PMS_RESERVATION_REJECTED", booking,
                       {"reason": reason, "blocking_night": blocked,
                        "pms_count": len(self.pms[blocked]), "capacity": self.capacity})
            self.mechanisms["pms_atomic_capacity_rejection"] += 1
            return False
        for night in booking["nights"]:
            self.pms[night].add(booking["booking_id"])
            self.dirty.add(night)
            self.max_pms = max(self.max_pms, len(self.pms[night]))
        booking["pms_reserved"] = True
        booking["ever_pms_reserved"] = True
        self._emit("PMS_RESERVED", booking, {"message_id": booking["message_id"],
                   "reason": reason, "rooms": booking["rooms"],
                   "stay_dates": booking["nights"], "nights": self._stock_rows(booking["nights"])})
        if booking["arrived"]:
            self._allocate(booking, reason)
        return True

    def _release(self, booking: dict, reason: str) -> None:
        if not booking["pms_reserved"]:
            return
        for night in booking["nights"]:
            self.pms[night].remove(booking["booking_id"])
            self.dirty.add(night)
        booking["pms_reserved"] = False
        self._emit("PMS_RELEASED", booking, {"reason": reason, "rooms": booking["rooms"],
                   "stay_dates": booking["nights"], "nights": self._stock_rows(booking["nights"])})

    def _refresh(self, nights: set[str] | None = None, cause: str = "PERIODIC") -> None:
        dates = set(self.dirty if nights is None else nights)
        changed = []
        for night in sorted(dates):
            available = self.capacity - len(self.pms[night])
            if self.cache.get(night, self.capacity) != available:
                changed.append({"stay_date": night, "available_rooms": available})
            self.cache[night] = available
        self.dirty.difference_update(dates)
        if changed:
            self._emit("CACHE_REFRESH", payload={"cause": cause, "nights": changed})

    def _delivery_delay(self) -> timedelta:
        value = float(self.rng[3].lognormal(
            np.log(self.profile["delivery_delay_median_minutes"]),
            self.spec["delivery_delay_lognormal_sigma"]))
        return timedelta(seconds=max(1, round(min(value * 60,
            self.spec["delivery_delay_max_hours"] * 3600))))

    def _prepare(self) -> None:
        demand = self.rng[2]
        stay_cfg, lead_cfg = self.spec["stay_nights"], self.spec["booking_lead_days"]
        expected_stay = sum(v * p for v, p in zip(stay_cfg["values"], stay_cfg["probabilities"]))
        end_day = date.fromisoformat(self.spec["internal_arrival_demand_end_date"])
        day = self.start.date()
        candidate_index = 0
        while day <= end_day:
            peak = self.spec["peak_demand_multiplier"] if self._peak(day) else 1.
            count = int(demand.poisson(self.capacity * self.utilization * peak / expected_stay))
            self.counters["candidate_demand"] += count
            for _ in range(count):
                candidate_index += 1
                nights = int(demand.choice(stay_cfg["values"], p=stay_cfg["probabilities"]))
                lead = int(demand.choice(lead_cfg["values"], p=lead_cfg["probabilities"]))
                created = _local(day - timedelta(days=lead), "00:00:00") + timedelta(
                    seconds=int(demand.integers(0, 86400)))
                arrival = _local(day, self.spec["arrival"]["check_in_local_time"]) + timedelta(
                    seconds=int(round(demand.uniform(*self.spec["arrival"]["arrival_jitter_hours_uniform"]) * 3600)))
                rate_low, rate_high = self.spec["synthetic_nightly_base_amount_krw_uniform_inclusive"]
                nightly = int(demand.integers(rate_low, rate_high + 1))
                candidate = {
                    "booking_id": f"B-H{self.index:03d}-{candidate_index:07d}",
                    "hotel_id": self.hotel_id,
                    "channel": "PLATFORM" if demand.random() < self.spec["platform_channel_share"] else "OTHER",
                    "check_in_date": day.isoformat(),
                    "check_out_date": (day + timedelta(days=nights)).isoformat(),
                    "created_at": _iso(created), "rooms": self.spec["rooms_per_booking"],
                    "adults": self.spec["adults_per_booking"],
                    "total_amount": nightly * nights * self.spec["rooms_per_booking"],
                    "arrival_at": arrival,
                }
                self._push(created, "reservation", "BOOK", candidate, candidate["booking_id"])
            day += timedelta(days=1)
        self._push(self.start, "service", "CACHE_TICK")
        day = self.start.date()
        while day <= self.end.date():
            self._push(_local(day, "08:00:00"), "service", "OUTAGE_CHECK")
            self._push(_local(day, self.spec["manual_recovery"]["process_at_local_time"]),
                       "reservation", "MANUAL_DAILY")
            for clock in self.spec["inventory_probes"]["daily_local_times"]:
                self._push(_local(day, clock), "query", "PROBES")
            day += timedelta(days=1)

    def _book(self, candidate: dict) -> None:
        nights = _dates(candidate["check_in_date"], candidate["check_out_date"])
        source = self.cache if candidate["channel"] == "PLATFORM" else None
        blocked = next((night for night in nights if (
            source.get(night, self.capacity) if source is not None
            else self.capacity - len(self.pms[night])) < candidate["rooms"]), None)
        if blocked:
            self._emit("BOOKING_DECLINED", payload={"candidate_id": candidate["booking_id"],
                       "channel": candidate["channel"], "blocking_night": blocked,
                       "source": "VISIBLE_CACHE" if source is not None else "CURRENT_PMS"})
            return
        booking = {**candidate, "nights": nights,
            "message_id": "MSG-" + candidate["booking_id"],
            "cancelled": False, "no_show": False, "terminal_failure": False,
            "closed": False, "pms_reserved": False, "ever_pms_reserved": False,
            "physical_allocated": False, "arrived": False,
            "recovery_deadline": candidate["arrival_at"] + timedelta(
                hours=self.spec["arrival"]["final_recovery_grace_hours"]),
            "checkout_at": _local(date.fromisoformat(candidate["check_out_date"]),
                                  self.spec["arrival"]["check_out_local_time"]),
            "request_id": None, "request_at": None, "valid_reply_at": None,
            "truth_label": None, "outcome_at": None, "outcome_reason": "PENDING_AFTER_SIMULATION",
            "blocking_night": None, "last_blocking_night": None,
            "observed_label": None, "label_available_at": None,
            "outbox_created": False, "outbox_lost": False,
        }
        self.bookings[booking["booking_id"]] = booking
        if source is not None:
            for night in nights:
                self.cache[night] = self.cache.get(night, self.capacity) - booking["rooms"]
                self.dirty.add(night)
        self._emit("BOOKING_STATUS", booking, {"status": "CONFIRMED", "channel": booking["channel"],
                   "check_in_date": booking["check_in_date"], "check_out_date": booking["check_out_date"],
                   "rooms": booking["rooms"], "adults": booking["adults"]})
        if source is None:
            if not self._reserve(booking, "OTHER_CHANNEL_CONFIRMATION"):
                raise AssertionError("Atomic other-channel confirmation lost its PMS allocation")
        elif self.rng[3].random() < self.drop_probability:
            self._emit("DELIVERY_DROPPED", booking, {"message_id": booking["message_id"], "silent": True})
            self.mechanisms["silent_delivery_drop"] += 1
            booking["initial_delivery_dropped"] = True
        else:
            booking["initial_delivery_dropped"] = False
            self._push(self.now + self._delivery_delay(), "reservation", "DELIVER", (booking, False), booking["booking_id"])
        if booking["channel"] == "PLATFORM":
            regular = _local(date.fromisoformat(booking["check_in_date"]) - timedelta(
                days=self.spec["reconfirmation"]["days_before_check_in"]),
                self.spec["reconfirmation"]["local_time"])
            self._push(max(regular, self.now), "query", "RECONFIRM", booking, booking["booking_id"])
        cancel_rng = self.rng[7]
        booking["planned_no_show"] = bool(cancel_rng.random() < self.spec["no_show_probability"])
        if cancel_rng.random() < self.spec["cancellation_probability"]:
            earliest = self.now + timedelta(seconds=1)
            last = _local(date.fromisoformat(booking["check_in_date"]),
                          self.spec["arrival"]["check_in_local_time"]) - timedelta(seconds=1)
            span = int((last - earliest).total_seconds())
            if span >= 0:
                cancel_at = earliest + timedelta(seconds=int(cancel_rng.integers(0, span + 1)))
                self._push(cancel_at, "release", "CANCEL", booking, booking["booking_id"])
        arrival_sort = booking["created_at"] + ":" + booking["booking_id"]
        self._push(booking["arrival_at"], "arrival", "ARRIVE", booking, arrival_sort)
        self._push(booking["recovery_deadline"], "arrival", "FINAL_DEADLINE", booking, arrival_sort)
        self._push(booking["checkout_at"], "release", "CHECKOUT", booking, booking["booking_id"])

    def _deliver(self, booking: dict, retry: bool) -> None:
        if not self._guard(booking, "PMS_DELIVERY"):
            return
        if self._down():
            self._emit("DELIVERY_ERROR", booking, {"message_id": booking["message_id"], "http_status": 503, "retry": retry})
            self.mechanisms["delivery_during_service_outage"] += 1
            if not retry:
                for hours in self.spec["observed_delivery_error_retry_delays_hours"]:
                    self._push(self.now + timedelta(hours=hours), "reservation", "DELIVER", (booking, True),
                               booking["booking_id"] + f":{hours}")
            return
        self._reserve(booking, "DELIVERY_RETRY" if retry else "INITIAL_DELIVERY")

    def _reply(self, booking: dict, request_id: str, manual: bool = False) -> None:
        registered = booking["pms_reserved"] and not booking["cancelled"]
        status = 503 if self._down() else 200 if registered else 404
        payload = {"request_id": request_id, "http_status": status,
            "schema_valid": status == 200,
            "check_in_date": booking["check_in_date"], "check_out_date": booking["check_out_date"],
            "nights": [{"stay_date": night, "status": "AVAILABLE"} for night in booking["nights"]] if status == 200 else [],
            "manual_followup": manual}
        # Response transport is distinct from server observation.  Hotel profile
        # affects transport delay; failures return quickly instead of becoming
        # fabricated successful date-level responses.
        delay = self._delivery_delay() if status == 200 else timedelta(minutes=1)
        produced = self.now + timedelta(seconds=1)
        self._push(self.now + max(delay, timedelta(seconds=1)), "query", "REPLY", (booking, payload, produced),
                   booking["booking_id"] + (":manual" if manual else ":first"))

    def _enqueue_manual(self, booking: dict, reason: str) -> None:
        if not self._guard(booking, "QUEUE_MANUAL"):
            return
        key = booking["booking_id"]
        if key not in self.manual:
            self.manual[key] = {"booking": booking, "queued_at": self.now, "pending": False}
            self._emit("MANUAL_QUEUED", booking, {"reason": reason,
                       "request_id": booking["request_id"]})

    def _manual_daily(self) -> None:
        tasks = sorted(self.manual.values(), key=lambda task: (
            task["booking"]["check_in_date"], task["queued_at"], task["booking"]["booking_id"]))
        processed = 0
        for task in tasks:
            booking = task["booking"]
            if not self._guard(booking, "MANUAL_DAILY"):
                self.manual.pop(booking["booking_id"], None)
                continue
            if task["pending"]:
                continue
            if processed >= self.spec["manual_recovery"]["tasks_per_hotel_day"]:
                break
            processed += 1
            task["pending"] = True
            delay = float(self.rng[5].uniform(*self.spec["manual_recovery"]["completion_delay_hours_uniform"]))
            complete_at = self.now + timedelta(seconds=max(1, round(delay * 3600)))
            self._emit("MANUAL_STARTED", booking, {"queued_at": _iso(task["queued_at"]),
                       "complete_at": _iso(complete_at)})
            self._push(complete_at, "reservation", "MANUAL_COMPLETE", booking, booking["booking_id"])

    def _manual_complete(self, booking: dict) -> None:
        task = self.manual.get(booking["booking_id"])
        if not task:
            return
        task["pending"] = False
        if not self._guard(booking, "MANUAL_COMPLETE"):
            self.manual.pop(booking["booking_id"], None)
            return
        if self._down() or self.rng[5].random() >= self.profile["recovery_contact_success_probability"]:
            self._emit("MANUAL_CONTACT_FAILED", booking, {"retry_next_day": True})
            return
        already_registered = booking["pms_reserved"]
        if self._reserve(booking, "MANUAL_RECOVERY"):
            self.mechanisms["manual_verified_existing" if already_registered else "manual_recovery_success"] += 1
            self.manual.pop(booking["booking_id"], None)
            if booking["request_id"]:
                self._reply(booking, booking["request_id"], manual=True)

    def _allocate(self, booking: dict, reason: str) -> None:
        if booking["physical_allocated"] or not self._guard(booking, "PHYSICAL_ALLOCATION"):
            return
        if not booking["pms_reserved"]:
            raise AssertionError("Physical allocation requires a protected PMS reservation")
        if any(len(self.physical[n]) + booking["rooms"] > self.capacity for n in booking["nights"]):
            raise AssertionError("Protected PMS reservations exceeded physical capacity")
        for night in booking["nights"]:
            self.physical[night].add(booking["booking_id"])
            self.max_physical = max(self.max_physical, len(self.physical[night]))
        booking["physical_allocated"] = True
        booking["allocated_at"] = self.now
        self._emit("PHYSICAL_ALLOCATED", booking, {"reason": reason, "rooms": booking["rooms"],
                   "stay_dates": booking["nights"], "nights": [
                       {"stay_date": n, "physical_count": len(self.physical[n]), "capacity": self.capacity}
                       for n in booking["nights"]]})
        if booking.get("initial_delivery_dropped"):
            self.mechanisms["dropped_then_recovered"] += 1
        if self.now > booking["arrival_at"]:
            self.mechanisms["delayed_then_allocated_within_grace"] += 1

    def _arrive(self, booking: dict) -> None:
        if booking["cancelled"] or booking["closed"]:
            return
        if booking["planned_no_show"]:
            booking["no_show"] = True
            booking["closed"] = True
            booking["outcome_reason"] = "NO_SHOW_UNVERIFIABLE_ORIGINAL_STAY"
            booking["outcome_at"] = self.now
            self._emit("NO_SHOW", booking)
            self._release(booking, "NO_SHOW")
            self._refresh(set(booking["nights"]), "NO_SHOW_RELEASE")
            return
        booking["arrived"] = True
        self._emit("ARRIVED", booking, {"recovery_deadline": _iso(booking["recovery_deadline"])})
        if booking["pms_reserved"]:
            self._allocate(booking, "PROTECTED_PMS_AT_ARRIVAL")
        elif not self._down():
            self._reserve(booking, "ONSITE_RECOVERY")

    def _outcome(self, booking: dict, label: int, reason: str) -> None:
        booking["truth_label"] = label
        booking["outcome_at"] = self.now
        booking["outcome_reason"] = reason
        self._emit("SOURCE_OUTCOME", booking, {"label": label, "reason": reason,
                   "blocking_night": booking["blocking_night"]})
        # This one draw is an actual source outbox write failure, not masking
        # an otherwise observable row or independently failing each label poll.
        if self.rng[6].random() < self.profile["evidence_delivery_drop_probability"]:
            booking["outbox_lost"] = True
            self._emit("EVIDENCE_OUTBOX_LOST", booking, {"source_outcome_at": _iso(self.now), "persistent": True})
            self.mechanisms["source_evidence_outbox_loss"] += 1
        else:
            booking["outbox_created"] = True
            self._emit("EVIDENCE_OUTBOX_WRITTEN", booking, {"source_outcome_at": _iso(self.now)})
        self._push(self.now + timedelta(hours=self.spec["evidence_collector_first_delay_hours"]),
                   "query", "COLLECT", booking, booking["booking_id"])

    def _final_deadline(self, booking: dict) -> None:
        if booking["cancelled"] or booking["no_show"] or booking["physical_allocated"] or booking["closed"]:
            return
        if not self._down() and self._reserve(booking, "ONSITE_GRACE_FINAL_ATTEMPT"):
            return
        booking["terminal_failure"] = True
        booking["closed"] = True
        # Capacity denials identify the earliest genuinely blocking night.
        # Service-only denial without a capacity check concerns the first night.
        blocked = next((n for n in booking["nights"] if len(self.pms[n]) + booking["rooms"] > self.capacity), None)
        booking["blocking_night"] = blocked or booking["nights"][0]
        reason = "SYNC_OVERBOOKING_CAPACITY" if blocked else "REGISTRATION_UNRESOLVED_DURING_OUTAGE"
        if booking.get("initial_delivery_dropped"):
            reason = "DELIVERY_MISSING_AND_" + reason
        self._emit("FINAL_REJECTED", booking, {"reason": reason,
                   "blocking_night": booking["blocking_night"],
                   "recovery_deadline": _iso(booking["recovery_deadline"])})
        self.mechanisms[reason] += 1
        self._outcome(booking, 1, reason)

    def _collect(self, booking: dict) -> None:
        if booking["observed_label"] is not None:
            return
        deadline = booking["outcome_at"] + timedelta(days=self.spec["max_reporting_delay_days"])
        status = 503 if self._down() else 200 if booking["outbox_created"] else 404
        self._emit("EVIDENCE_COLLECTION_ATTEMPT", booking, {"http_status": status,
                   "source_outcome_at": _iso(booking["outcome_at"])})
        if status == 200:
            booking["observed_label"] = booking["truth_label"]
            booking["label_available_at"] = self.now
            self._emit("OBSERVED_OUTCOME", booking, {"label": booking["truth_label"],
                       "check_in_date": booking["check_in_date"], "channel": booking["channel"],
                       "source_outcome_at": _iso(booking["outcome_at"]), "authoritative_source": "HOTEL_PHYSICAL_STAY_LEDGER",
                       "blocking_night": booking["blocking_night"]})
            return
        next_at = self.now + timedelta(hours=self.spec["evidence_retry_hours"])
        if next_at <= deadline:
            self._push(next_at, "query", "COLLECT", booking, booking["booking_id"])
        else:
            self._emit("EVIDENCE_COLLECTION_EXHAUSTED", booking, {"deadline": _iso(deadline)})

    def _dispatch(self, kind: str, payload: Any) -> None:
        if kind == "BOOK":
            self._book(payload)
        elif kind == "CACHE_TICK":
            self.counters["cache_ticks"] += 1
            if not self._down():
                self._refresh()
            self._push(self.now + timedelta(seconds=max(1, round(self.cache_minutes * 60))), "service", "CACHE_TICK")
        elif kind == "OUTAGE_CHECK":
            if not self._down() and self.rng[4].random() < self.profile["outage_start_probability_per_day"]:
                hours = float(self.rng[4].uniform(*self.spec["hotel_outage_duration_hours_uniform"]))
                self.outage_until = self.now + timedelta(seconds=max(1, round(hours * 3600)))
                self._emit("SERVICE_STATE", payload={"available": False, "until": _iso(self.outage_until)})
                self._push(self.outage_until, "service", "OUTAGE_END")
                self.mechanisms["transient_service_outage"] += 1
        elif kind == "OUTAGE_END":
            self._emit("SERVICE_STATE", payload={"available": True})
        elif kind == "DELIVER":
            self._deliver(*payload)
        elif kind == "CANCEL":
            booking = payload
            if not booking["closed"] and not booking["cancelled"]:
                booking["cancelled"] = True
                booking["closed"] = True
                booking["outcome_at"] = self.now
                booking["outcome_reason"] = "VOLUNTARY_CANCELLATION_UNVERIFIABLE_ORIGINAL_STAY"
                self._emit("BOOKING_STATUS", booking, {"status": "CANCELLED", "channel": booking["channel"],
                           "check_in_date": booking["check_in_date"], "check_out_date": booking["check_out_date"]})
                self._release(booking, "CANCELLATION")
                self._refresh(set(booking["nights"]), "CANCELLATION_SNAPSHOT")
        elif kind == "RECONFIRM":
            booking = payload
            if booking["cancelled"] or booking["request_id"]:
                return
            booking["request_id"] = "REQ-" + booking["booking_id"]
            booking["request_at"] = self.now
            due = self.now + timedelta(hours=self.spec["reconfirmation"]["deadline_hours"])
            self._emit("RECONFIRMATION_REQUEST", booking, {"request_id": booking["request_id"], "due_at": _iso(due)})
            self._reply(booking, booking["request_id"])
            self._push(due, "query", "RECONFIRM_DUE", booking, booking["booking_id"])
        elif kind == "REPLY":
            booking, response, produced_at = payload
            self._emit("HOTEL_REPLY", booking, response, occurred=produced_at)
            if response["http_status"] == 200:
                if booking["valid_reply_at"] is None:
                    booking["valid_reply_at"] = self.now
            elif response["http_status"] == 404:
                self._enqueue_manual(booking, "HOTEL_NOT_FOUND")
        elif kind == "RECONFIRM_DUE":
            booking = payload
            if booking["valid_reply_at"] is None or booking["valid_reply_at"] > self.now:
                self._enqueue_manual(booking, "RECONFIRMATION_OVERDUE")
        elif kind == "MANUAL_DAILY":
            self._manual_daily()
        elif kind == "MANUAL_COMPLETE":
            self._manual_complete(payload)
        elif kind == "PROBES":
            conditions = self.spec["inventory_probes"]["conditions"]
            for offset in range(1, self.spec["inventory_probes"]["future_check_in_horizon_days"] + 1):
                target = self.now.date() + timedelta(days=offset)
                status = 503 if self._down() else 200
                self._emit("INVENTORY_PROBE", payload={
                    "request_id": f"PROBE-{self.hotel_id}-{self.now.strftime('%Y%m%d%H%M%S')}-{offset}",
                    "target_check_in_date": target.isoformat(),
                    "check_out_date": (target + timedelta(days=conditions["nights"])).isoformat(),
                    "available_rooms": self.cache.get(target.isoformat(), self.capacity) if status == 200 else None,
                    "http_status": status, "room_type": conditions["room_type"],
                    "adults": conditions["adults"], "rooms": conditions["rooms"], "currency": conditions["currency"]})
        elif kind == "ARRIVE":
            self._arrive(payload)
        elif kind == "FINAL_DEADLINE":
            self._final_deadline(payload)
        elif kind == "CHECKOUT":
            booking = payload
            if booking["physical_allocated"] and not booking["closed"]:
                booking["closed"] = True
                self._outcome(booking, 0, "ALL_ORIGINAL_NIGHTS_PROVIDED")
                self._release(booking, "CHECKOUT")
        elif kind == "COLLECT":
            self._collect(payload)
        else:
            raise AssertionError(f"Unhandled simulator action: {kind}")

    def run(self) -> dict:
        self._prepare()
        while self.queue:
            self.now, _, _, _, kind, payload = heapq.heappop(self.queue)
            self._dispatch(kind, payload)
        hotel = {"hotel_id": self.hotel_id, "data_source": "SIMULATED",
            "room_capacity": self.capacity, "private_profile": self.profile_name,
            "private_base_utilization": self.utilization,
            "private_delivery_drop_probability": self.drop_probability,
            "private_cache_sync_interval_minutes": self.cache_minutes}
        bookings, nights, truth = [], [], []
        for booking in sorted(self.bookings.values(), key=lambda b: b["booking_id"]):
            bookings.append({key: booking[key] for key in (
                "booking_id", "hotel_id", "channel", "check_in_date", "check_out_date",
                "created_at", "rooms", "adults", "total_amount")})
            bookings[-1].update({"currency": self.spec["currency"], "room_type": self.spec["room_type"],
                "arrival_at": _iso(booking["arrival_at"]), "recovery_deadline": _iso(booking["recovery_deadline"]),
                "final_supplier_status": "CANCELLED" if booking["cancelled"] else "CONFIRMED"})
            for night in booking["nights"]:
                provided_by_end = (booking["physical_allocated"] and _local(
                    date.fromisoformat(night) + timedelta(days=1), self.spec["arrival"]["check_out_local_time"]) <= self.end)
                status = ("UNAVAILABLE" if booking["truth_label"] == 1 and night == booking["blocking_night"]
                          else "NOT_ATTEMPTED" if booking["truth_label"] == 1
                          else "AVAILABLE" if provided_by_end else "UNKNOWN")
                nights.append({"booking_id": booking["booking_id"], "hotel_id": self.hotel_id,
                    "stay_date": night, "provision_status": status,
                    "pms_reserved": booking["booking_id"] in self.pms[night],
                    "physical_allocated": booking["booking_id"] in self.physical[night],
                    "capacity": self.capacity,
                    "final_pms_night_count": len(self.pms[night]),
                    "physical_night_count": len(self.physical[night])})
            truth.append({"booking_id": booking["booking_id"], "hotel_id": self.hotel_id,
                "truth_label": booking["truth_label"], "outcome_at": _iso(booking["outcome_at"]) if booking["outcome_at"] else None,
                "reason": booking["outcome_reason"], "blocking_night": booking["blocking_night"],
                "observed_label": booking["observed_label"],
                "label_available_at": _iso(booking["label_available_at"]) if booking["label_available_at"] else None,
                "private_initial_delivery_dropped": booking.get("initial_delivery_dropped", False),
                "private_source_outbox_lost": booking["outbox_lost"], "channel": booking["channel"]})
        self.events.sort(key=lambda e: (e["occurred_at"], e["available_at"], e["event_id"]))
        summary = Counter("positive" if t["truth_label"] == 1 else "negative" if t["truth_label"] == 0 else "unknown" for t in truth)
        observed = Counter("positive" if t["observed_label"] == 1 else "negative" if t["observed_label"] == 0 else "unknown" for t in truth)
        stats = {**{key: summary[key] for key in ("positive", "negative", "unknown")},
            "observed": {key: observed[key] for key in ("positive", "negative", "unknown")}, "bookings": len(bookings),
            "events": len(self.events), "mechanisms": dict(self.mechanisms), "counters": dict(self.counters),
            "invariants": {"max_pms_rooms": self.max_pms, "max_physical_rooms": self.max_physical,
                           "capacity": self.capacity, "physical_over_capacity": self.max_physical > self.capacity,
                           "pms_over_capacity": self.max_pms > self.capacity}}
        return {"hotel": hotel, "bookings": bookings, "events": self.events,
                "booking_nights": nights, "truth": truth, "stats": stats}


def simulate_hotel(config: dict, hotel_index: int) -> dict:
    """Generate one deterministic hotel shard, independent of shard run order."""
    return HotelSimulator(config, hotel_index).run()
