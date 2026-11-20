import copy
from datetime import date, datetime, timedelta
import json
from pathlib import Path
import unittest

from travel_ml.simulator import HotelSimulator, SEOUL, simulate_hotel


ROOT = Path(__file__).resolve().parents[1]


def config():
    result = json.loads((ROOT / "config/simulator_v0.2.json").read_text())
    spec = result["simulation"]
    spec["start"] = "2025-01-01T00:00:00+09:00"
    spec["end"] = "2025-01-20T00:00:00+09:00"
    spec["internal_arrival_demand_end_date"] = "2025-04-19"
    spec["hotel_room_capacity_inclusive"] = [1, 1]
    spec["base_utilization_uniform"] = [.9, .9]
    spec["cancellation_probability"] = 0.
    spec["no_show_probability"] = 0.
    for profile in spec["profiles"].values():
        profile["outage_start_probability_per_day"] = 0.
        profile["initial_delivery_drop_probability_uniform"] = [1., 1.]
        profile["evidence_delivery_drop_probability"] = 0.
        profile["recovery_contact_success_probability"] = 1.
        profile["cache_sync_interval_minutes_uniform"] = [60., 60.]
    return result


def candidate(sim, number=1, channel="PLATFORM", check_in="2025-01-03", nights=3):
    day = date.fromisoformat(check_in)
    return {
        "booking_id": f"FIXTURE-H{sim.index:03d}-{number:04d}",
        "hotel_id": sim.hotel_id, "channel": channel,
        "check_in_date": check_in, "check_out_date": (day + timedelta(days=nights)).isoformat(),
        "created_at": sim.now.isoformat(timespec="seconds"), "rooms": 1, "adults": 2,
        "total_amount": 100000 * nights,
        "arrival_at": datetime.combine(day, datetime.min.time(), SEOUL).replace(hour=15),
    }


def accepted(sim, number=1, channel="PLATFORM", check_in="2025-01-03", nights=3):
    raw = candidate(sim, number, channel, check_in, nights)
    sim._book(raw)
    return sim.bookings[raw["booking_id"]]


def normal_outcome(sim, booking):
    sim.now = booking["arrival_at"]
    sim._arrive(booking)
    sim.now = booking["checkout_at"]
    sim._dispatch("CHECKOUT", booking)


class SimulatorStateTest(unittest.TestCase):
    def test_atomic_cache_confirmation_and_refresh_can_create_overcommitment(self):
        sim = HotelSimulator(config(), 1)
        first = accepted(sim)
        sim._book(candidate(sim, 2))
        self.assertEqual(len(sim.bookings), 1)  # cache is decremented, no free resale
        self.assertTrue(all(sim.cache[night] == 0 for night in first["nights"]))
        sim._refresh()
        second = accepted(sim, 3, "OTHER")  # lost message was absent from PMS
        self.assertFalse(first["pms_reserved"])
        self.assertTrue(second["pms_reserved"])
        self.assertEqual(len(sim.bookings), 2)
        self.assertTrue(all(len(sim.pms[night]) == 1 for night in first["nights"]))

    def test_missing_message_can_recover_and_complete_original_stay(self):
        sim = HotelSimulator(config(), 1)
        booking = accepted(sim)
        self.assertTrue(booking["initial_delivery_dropped"])
        self.assertIsNone(booking["truth_label"])
        normal_outcome(sim, booking)
        self.assertEqual(booking["truth_label"], 0)
        self.assertEqual(sim.mechanisms["dropped_then_recovered"], 1)
        self.assertEqual(sim.counters["PMS_RELEASED"], 1)
        sim.now += timedelta(hours=2)
        sim._collect(booking)
        self.assertEqual(booking["observed_label"], 0)

    def test_failed_full_stay_has_one_blocking_night_and_no_late_resurrection(self):
        sim = HotelSimulator(config(), 1)
        lost = accepted(sim)
        sim._refresh()
        protected = accepted(sim, 2, "OTHER")
        sim.now = lost["arrival_at"]
        sim._arrive(protected)
        sim._arrive(lost)
        self.assertFalse(lost["physical_allocated"])
        sim.now = lost["recovery_deadline"]
        sim._final_deadline(lost)
        self.assertEqual(lost["truth_label"], 1)
        self.assertEqual(lost["blocking_night"], lost["nights"][0])
        sim.now += timedelta(hours=1)
        sim._release(protected, "TEST_ROOM_FREED")
        sim._deliver(lost, True)
        self.assertFalse(lost["pms_reserved"])
        self.assertFalse(lost["physical_allocated"])
        self.assertEqual(sim.max_physical, 1)
        self.assertEqual(sim.events[-1]["event_type"], "ACTION_GUARD")

    def test_pms_ack_is_idempotent_and_cancellation_releases_once(self):
        sim = HotelSimulator(config(), 1)
        booking = accepted(sim, channel="OTHER")
        sim._deliver(booking, True)
        sim._deliver(booking, True)
        self.assertEqual(sim.counters["PMS_RESERVED"], 1)
        self.assertEqual(sim.counters["PMS_ACK_DUPLICATE"], 2)
        self.assertTrue(all(len(sim.pms[n]) == 1 for n in booking["nights"]))
        sim.now += timedelta(hours=1)
        sim._dispatch("CANCEL", booking)
        sim._dispatch("CANCEL", booking)
        sim._deliver(booking, True)
        self.assertEqual(sim.counters["PMS_RELEASED"], 1)
        self.assertTrue(all(len(sim.pms[n]) == 0 for n in booking["nights"]))
        self.assertIsNone(booking["truth_label"])

    def test_no_show_releases_protected_rooms_without_normal_label(self):
        sim = HotelSimulator(config(), 1)
        booking = accepted(sim, channel="OTHER")
        booking["planned_no_show"] = True
        sim.now = booking["arrival_at"]
        sim._arrive(booking)
        sim._deliver(booking, True)
        self.assertTrue(booking["no_show"])
        self.assertFalse(booking["physical_allocated"])
        self.assertFalse(booking["pms_reserved"])
        self.assertIsNone(booking["truth_label"])

    def test_manual_queue_capacity_and_delay_anchor(self):
        cfg = config()
        cfg["simulation"]["manual_recovery"]["completion_delay_hours_uniform"] = [1., 1.]
        sim = HotelSimulator(cfg, 1)
        for i in range(1, 6):
            booking = accepted(sim, i, check_in="2025-01-05")
            sim._refresh()
            sim._enqueue_manual(booking, "TEST_NOT_FOUND")
        sim.now = datetime(2025, 1, 2, 16, tzinfo=SEOUL)
        sim._manual_daily()
        starts = [e for e in sim.events if e["event_type"] == "MANUAL_STARTED"]
        self.assertEqual(len(starts), 3)
        self.assertTrue(all(e["payload"]["complete_at"] == "2025-01-02T17:00:00+09:00" for e in starts))
        self.assertEqual(sum(task["pending"] for task in sim.manual.values()), 3)

    def test_evidence_loss_is_persistent_outbox_failure_and_never_truth_fallback(self):
        cfg = config()
        cfg["simulation"]["profiles"]["stable"]["evidence_delivery_drop_probability"] = 1.
        sim = HotelSimulator(cfg, 1)
        booking = accepted(sim)
        normal_outcome(sim, booking)
        self.assertEqual(booking["truth_label"], 0)
        self.assertTrue(booking["outbox_lost"])
        self.assertFalse(booking["outbox_created"])
        for hours in (2, 8, 14, 20):
            sim.now = booking["outcome_at"] + timedelta(hours=hours)
            sim._collect(booking)
        self.assertIsNone(booking["observed_label"])
        self.assertEqual(sim.counters["EVIDENCE_OUTBOX_LOST"], 1)
        self.assertEqual(sim.counters["OBSERVED_OUTCOME"], 0)

    def test_transient_evidence_service_outage_recovers_existing_source_record(self):
        sim = HotelSimulator(config(), 1)
        booking = accepted(sim)
        normal_outcome(sim, booking)
        sim.now += timedelta(hours=2)
        sim.outage_until = sim.now + timedelta(hours=1)
        sim._collect(booking)
        self.assertIsNone(booking["observed_label"])
        sim.now += timedelta(hours=6)
        sim._collect(booking)
        self.assertEqual(booking["observed_label"], 0)
        self.assertEqual(sim.counters["EVIDENCE_OUTBOX_WRITTEN"], 1)
        self.assertEqual(sim.counters["OBSERVED_OUTCOME"], 1)


class SimulatorShardTest(unittest.TestCase):
    def test_seed_reproducibility_capacity_and_integer_second_events(self):
        cfg = config()
        first = simulate_hotel(cfg, 96)
        self.assertEqual(first, simulate_hotel(cfg, 96))
        capacity = first["hotel"]["room_capacity"]
        self.assertLessEqual(first["stats"]["invariants"]["max_pms_rooms"], capacity)
        self.assertLessEqual(first["stats"]["invariants"]["max_physical_rooms"], capacity)
        ids = [e["event_id"] for e in first["events"]]
        self.assertEqual(len(ids), len(set(ids)))
        for event in first["events"]:
            occurred, available = map(datetime.fromisoformat, (event["occurred_at"], event["available_at"]))
            self.assertLessEqual(occurred, available)
            self.assertEqual(occurred.microsecond, 0)
            self.assertEqual(available.microsecond, 0)
            self.assertEqual(occurred.utcoffset(), timedelta(hours=9))
        by_booking = {}
        for row in first["booking_nights"]:
            by_booking.setdefault(row["booking_id"], []).append(row)
        for truth in first["truth"]:
            if truth["truth_label"] == 1:
                rows = by_booking[truth["booking_id"]]
                self.assertEqual(sum(r["provision_status"] == "UNAVAILABLE" for r in rows), 1)
                self.assertTrue(all(r["provision_status"] in {"UNAVAILABLE", "NOT_ATTEMPTED"} for r in rows))

    def test_extending_end_does_not_change_earlier_event_prefix(self):
        short = config()
        short["simulation"]["end"] = "2025-01-10T00:00:00+09:00"
        long = copy.deepcopy(short)
        long["simulation"]["end"] = "2025-01-20T00:00:00+09:00"
        a, b = simulate_hotel(short, 1), simulate_hotel(long, 1)
        cutoff = datetime.fromisoformat(short["simulation"]["end"])
        previous = [e for e in b["events"] if datetime.fromisoformat(e["available_at"]) <= cutoff]
        self.assertEqual(a["events"], previous)

    def test_shard_profile_assignment_does_not_depend_on_run_order(self):
        cfg = config()
        fragile = simulate_hotel(cfg, 96)
        stable = simulate_hotel(cfg, 1)
        self.assertEqual(fragile["hotel"]["private_profile"], "fragile")
        self.assertEqual(stable["hotel"]["private_profile"], "stable")
        self.assertEqual(fragile, simulate_hotel(cfg, 96))


if __name__ == "__main__":
    unittest.main()
