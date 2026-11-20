import copy
from datetime import datetime, timedelta
import hashlib
import json
from pathlib import Path
import unittest

from travel_ml.features import FIELD_NAMES, build_features


ROOT = Path(__file__).resolve().parents[1]


def config(start="2025-04-01", end=None):
    result = json.loads((ROOT / "config" / "simulator_v0.2.json").read_text())
    result["calendar_panel"] = {"start": start, "end": end or start}
    return result


class FeatureTests(unittest.TestCase):
    def setUp(self):
        self.source = {"hotel": {"hotel_id": "HOTEL-001"}, "events": [],
                       "bookings": [{"booking_id": "DO-NOT-READ", "status": "CANCELLED"}],
                       "truth": [{"label": 1}]}

    def event(self, kind, booking, occurred, payload, available=None, event_id=None):
        event = {
            "event_id": event_id or f"E-{len(self.source['events']) + 1:04d}",
            "hotel_id": "HOTEL-001", "booking_id": booking,
            "event_type": kind, "occurred_at": occurred,
            "available_at": available or occurred, "payload": payload,
        }
        self.source["events"].append(event)
        return event

    def status(self, booking, occurred="2025-03-01T00:00:00+09:00", *,
               target="2025-04-01", status="CONFIRMED", available=None, channel="PLATFORM"):
        return self.event("BOOKING_STATUS", booking, occurred, {
            "status": status, "channel": channel, "check_in_date": target,
            "check_out_date": (datetime.fromisoformat(target) + timedelta(days=1)).date().isoformat(),
            "rooms": 1, "adults": 2,
        }, available)

    def outcome(self, booking, target, occurred, label, available=None):
        return self.event("OBSERVED_OUTCOME", booking, occurred,
                          {"label": label, "check_in_date": target}, available)

    def row(self, **kwargs):
        return build_features(self.source, config(), **kwargs)[0]

    def test_cold_start_has_prior_not_fake_negative_and_strict_feature_order(self):
        row = self.row()
        self.assertEqual(row["pastFailureRate90d"], .05)
        self.assertTrue(row["historyMissing"])
        self.assertIsNone(row["historyRawRate"])
        self.assertEqual(row["cohortBookingIds"], [])
        self.assertEqual(row["targetConfirmedBookingCount"], 0)
        self.assertEqual(row["reconfirmationOverdueRate7d"], 0)
        self.assertEqual(row["inventoryUnavailableRate7d"], 0)
        self.assertEqual(row["lineageEventCount"], 0)
        self.assertEqual(row["lineageDigest"], hashlib.sha256(b"").hexdigest())
        self.assertIsNone(row["featureMaxAvailableAt"])
        self.assertNotIn("lineageEventIds", row)
        changed = config()
        changed["features"]["ordered_names"] = list(reversed(FIELD_NAMES))
        with self.assertRaisesRegex(ValueError, "feature order"):
            build_features(self.source, changed)

    def test_cohort_uses_known_status_not_final_state_or_future_cancellation(self):
        self.status("late-cancel")
        self.status("late-cancel", "2025-03-30T00:00:00+09:00", status="CANCELLED", available="2025-04-02T00:00:00+09:00")
        self.status("known-cancel")
        self.status("known-cancel", "2025-03-30T00:00:00+09:00", status="CANCELLED")
        self.status("other-channel", channel="OTHER")
        self.status("too-late", "2025-03-31T10:00:00+09:00")
        self.status("exact-cutoff", "2025-03-31T09:00:00+09:00")
        row = self.row()
        self.assertEqual(row["cohortBookingIds"], ["exact-cutoff", "late-cancel"])
        self.assertEqual(row["targetConfirmedBookingCount"], 2)
        self.assertEqual(row["asOf"], "2025-03-31T09:00:00+09:00")

    def test_history_is_booking_weighted_available_and_90_day_bounded(self):
        for booking, target, label in (("failure", "2025-03-01", 1), ("normal", "2025-03-02", 0),
                                        ("unknown", "2025-03-03", None), ("future-label", "2025-03-04", 1),
                                        ("outside", "2024-12-30", 1), ("asof-day", "2025-03-31", 1)):
            self.status(booking, "2024-12-01T00:00:00+09:00", target=target)
            self.outcome(booking, target, "2025-03-05T00:00:00+09:00", label,
                         "2025-04-01T00:00:00+09:00" if booking == "future-label" else None)
        # Replayed observed evidence does not count a reservation twice.
        self.outcome("failure", "2025-03-01", "2025-03-06T00:00:00+09:00", 1)
        row = self.row()
        self.assertEqual((row["historyKnownCount"], row["historyFailedCount"]), (2, 1))
        self.assertEqual(row["historyRawRate"], .5)
        self.assertAlmostEqual(row["pastFailureRate90d"], 2 / 22)

    def test_growth_windows_add_one_and_logical_confirmation_deduplication(self):
        for n in range(4):
            self.status(f"old-{n}", "2025-03-01T12:00:00+09:00")
        for n in range(2):
            self.status(f"recent-{n}", "2025-03-25T12:00:00+09:00")
        self.status("recent-0", "2025-03-26T12:00:00+09:00")
        self.status("outside", "2025-01-01T12:00:00+09:00")
        self.status("outside", "2025-03-26T12:00:00+09:00")
        self.status("at-end", "2025-03-31T09:00:00+09:00")
        row = self.row()
        self.assertEqual((row["growthRecentCount"], row["growthPreviousCount"]), (2, 4))
        self.assertEqual(row["bookingVolumeGrowth7d"], .5)
        self.assertIn("at-end", row["cohortBookingIds"])

    def test_reconfirmation_first_request_deadline_and_matching_reply(self):
        for booking in ("timely", "late", "wrong-dates", "cancelled-before-due", "cancel-known-later"):
            self.status(booking)
            self.event("RECONFIRMATION_REQUEST", booking, "2025-03-28T10:00:00+09:00",
                       {"request_id": booking, "due_at": "2025-03-28T12:00:00+09:00"})
        for booking, available, nights in (
                ("timely", "2025-03-28T12:00:00+09:00", [{"stay_date": "2025-04-01", "status": "UNKNOWN"}]),
                ("late", "2025-03-28T12:00:01+09:00", [{"stay_date": "2025-04-01", "status": "AVAILABLE"}]),
                ("wrong-dates", "2025-03-28T11:00:00+09:00", [{"stay_date": "2025-04-02", "status": "AVAILABLE"}])):
            self.event("HOTEL_REPLY", booking, "2025-03-28T11:00:00+09:00",
                       {"request_id": booking, "http_status": 200, "schema_valid": True, "nights": nights}, available)
        self.status("cancelled-before-due", "2025-03-28T11:00:00+09:00", status="CANCELLED")
        self.status("cancel-known-later", "2025-03-28T11:00:00+09:00", status="CANCELLED", available="2025-03-28T13:00:00+09:00")
        self.event("RECONFIRMATION_REQUEST", "timely", "2025-03-29T10:00:00+09:00",
                   {"request_id": "retry", "due_at": "2025-03-29T12:00:00+09:00"})
        row = self.row()
        self.assertEqual(row["reconfirmationEligibleCount"], 4)
        self.assertEqual(row["reconfirmationOverdueCount"], 3)
        self.assertEqual(row["reconfirmationOverdueRate7d"], .75)

    def test_inventory_only_valid_fixed_condition_queries_and_deduplicated_ids(self):
        payload = {"request_id": "q1", "target_check_in_date": "2025-04-01",
                   "check_out_date": "2025-04-02", "available_rooms": 0, "http_status": 200,
                   "room_type": "STANDARD", "adults": 2, "rooms": 1, "currency": "KRW"}
        self.event("INVENTORY_PROBE", None, "2025-03-30T08:00:00+09:00", payload)
        self.event("INVENTORY_PROBE", None, "2025-03-30T08:01:00+09:00", dict(payload))
        for identifier, changes in (("available", {"available_rooms": 2}),
                                    ("server-error", {"http_status": 503, "available_rooms": None}),
                                    ("wrong-period", {"check_out_date": "2025-04-03"}),
                                    ("wrong-adults", {"adults": 3})):
            self.event("INVENTORY_PROBE", None, "2025-03-30T08:00:00+09:00",
                       {**payload, "request_id": identifier, **changes})
        row = self.row()
        self.assertEqual((row["inventoryValidCount"], row["inventoryUnavailableCount"], row["inventoryInvalidCount"]), (2, 1, 1))
        self.assertEqual(row["inventoryUnavailableRate7d"], .5)

    def test_future_mutations_and_private_truth_do_not_change_prior_features(self):
        self.status("known")
        self.status("future", "2025-04-03T10:00:00+09:00")
        self.status("known", "2025-03-30T10:00:00+09:00", status="CANCELLED", available="2025-04-03T12:00:00+09:00")
        before = self.row(include_lineage_ids=True)
        changed = copy.deepcopy(self.source)
        for event in changed["events"]:
            if event["available_at"] > before["asOf"]:
                event["payload"]["status"] = "CANCELLED" if event["payload"]["status"] == "CONFIRMED" else "CONFIRMED"
                event["payload"]["check_in_date"] = "2025-05-01"
        changed["bookings"] = [{"anything": "different final booking state"}]
        changed["truth"] = [{"label": 0, "latent_hotel_profile": "CHANGED"}]
        changed["events"].append({"event_id": "PRIVATE", "event_type": "PRIVATE_WORLD_TRUTH"})
        self.assertEqual(before, build_features(changed, config(), include_lineage_ids=True)[0])
        by_id = {e["event_id"]: e for e in self.source["events"]}
        self.assertEqual(before["lineageDigest"], hashlib.sha256("\n".join(before["lineageEventIds"]).encode()).hexdigest())
        for event_id in before["lineageEventIds"]:
            self.assertLessEqual(by_id[event_id]["occurred_at"], before["asOf"])
            self.assertLessEqual(by_id[event_id]["available_at"], before["asOf"])

    def test_calendar_only_uses_fixed_public_configuration(self):
        rows = build_features(self.source, config("2025-04-03", "2025-04-06"))
        self.assertEqual([row["isPeakDay"] for row in rows], [False, True, True, False])
        self.assertTrue(build_features(self.source, config("2025-07-01"))[0]["isPeakDay"])
        self.assertTrue(build_features(self.source, config("2025-12-25"))[0]["isPeakDay"])


if __name__ == "__main__":
    unittest.main()
