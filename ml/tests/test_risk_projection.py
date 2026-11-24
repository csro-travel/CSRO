"""Focused tests for the offline observed-event handoff, without model or DB I/O."""
from copy import deepcopy
from datetime import date, timedelta
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from travel_ml import features as feature_builder
from travel_ml.common import FEATURE_NAMES, ROOT, json_hash, load_config, sha256
from travel_ml.risk_projection import ProjectionError, prepare_projection
from travel_ml.risk_runtime import validate_request


TARGET = "2026-11-10"
HOTEL = "HOTEL-001"
AS_OF = "2026-11-09T09:00:00+09:00"


def source_fixture():
    return json.loads((ROOT / "examples" / "risk-events-cold-start.json").read_text(encoding="utf-8"))


def event(kind, booking, occurred, payload, *, identifier="OBSERVATION", available=None):
    return {"event_id": identifier, "event_type": kind, "hotel_id": HOTEL,
            "booking_id": booking, "occurred_at": occurred,
            "available_at": available or occurred, "payload": payload}


def status(booking, occurred, *, identifier, target=TARGET, channel="PLATFORM", state="CONFIRMED",
           available=None):
    return event("BOOKING_STATUS", booking, occurred, {
        "status": state, "channel": channel, "check_in_date": target,
        "check_out_date": (date.fromisoformat(target) + timedelta(days=1)).isoformat(),
        "rooms": 1, "adults": 2,
    }, identifier=identifier, available=available)


class RiskProjectionTests(unittest.TestCase):
    def project(self, source):
        return prepare_projection(source, HOTEL, TARGET)

    def test_cold_start_has_exact_contract_honest_prior_and_cutoff_semantics(self):
        source, config = source_fixture(), load_config()
        originals = deepcopy((source, config))
        result = prepare_projection(source, HOTEL, TARGET, config)
        self.assertEqual(set(result), {"format", "sourceKind", "request", "cohortBookingCodes", "provenance"})
        self.assertEqual(result["format"], "risk-event-projection-1")
        self.assertEqual(result["sourceKind"], "SYNTHETIC_OBSERVATIONS")
        self.assertEqual(result["cohortBookingCodes"], ["YB-001"])
        request = result["request"]
        self.assertEqual(set(request), {"hotelId", "targetCheckInDate", "asOf", "featureSetVersion", "features"})
        self.assertEqual((request["hotelId"], request["targetCheckInDate"], request["asOf"]),
                         (HOTEL, TARGET, AS_OF))
        self.assertEqual(request["featureSetVersion"], "risk-features-0.2")
        self.assertEqual(request["features"], {
            "pastFailureRate90d": .05, "targetConfirmedBookingCount": 1,
            # Exact-cutoff confirmation is in the cohort; growth is [t-7d,t).
            "bookingVolumeGrowth7d": 0, "reconfirmationOverdueRate7d": 0,
            "inventoryUnavailableRate7d": 0, "isPeakDay": False,
        })
        self.assertEqual(tuple(request["features"]), FEATURE_NAMES)
        self.assertEqual(validate_request(request), request)
        effective = deepcopy(config)
        effective["calendar_panel"] = {"start": TARGET, "end": TARGET}
        self.assertEqual(result["provenance"], {
            "sourceSha256": json_hash(source),
            "featureBuilderSha256": sha256(Path(feature_builder.__file__)),
            "configSha256": json_hash(effective),
            "lineageDigest": hashlib.sha256(b"SYNTHETIC-YB001-CONFIRMED-D1").hexdigest(),
        })
        self.assertEqual((source, config), originals)

    def test_observed_history_and_failures_feed_the_existing_builder_without_audit_fields(self):
        source = source_fixture()
        source["events"][0]["occurred_at"] = source["events"][0]["available_at"] = "2026-11-06T08:00:00+09:00"
        source["events"] += [
            status("HISTORICAL", "2026-09-01T08:00:00+09:00", identifier="HISTORY-CONFIRMED", target="2026-11-01"),
            event("OBSERVED_OUTCOME", "HISTORICAL", "2026-11-03T12:00:00+09:00",
                  {"label": 1, "check_in_date": "2026-11-01"}, identifier="HISTORY-EVIDENCE"),
            event("RECONFIRMATION_REQUEST", "YB-001", "2026-11-07T10:00:00+09:00",
                  {"request_id": "REQ", "due_at": "2026-11-07T12:00:00+09:00"}, identifier="REQUEST"),
            event("HOTEL_REPLY", "YB-001", "2026-11-07T10:01:00+09:00",
                  {"request_id": "REQ", "http_status": 503, "schema_valid": False, "nights": []}, identifier="ERROR-REPLY"),
            event("INVENTORY_PROBE", None, "2026-11-08T08:00:00+09:00", {
                "request_id": "PROBE", "target_check_in_date": TARGET, "check_out_date": "2026-11-11",
                "available_rooms": 0, "http_status": 200, "room_type": "STANDARD",
                "adults": 2, "rooms": 1, "currency": "KRW",
            }, identifier="INVENTORY"),
        ]
        result = self.project(source)
        config = load_config()
        config["calendar_panel"] = {"start": TARGET, "end": TARGET}
        expected = feature_builder.build_features(source, config)[0]
        self.assertEqual(result["request"]["features"], {name: expected[name] for name in FEATURE_NAMES})
        self.assertAlmostEqual(result["request"]["features"]["pastFailureRate90d"], 2 / 21)
        self.assertEqual(result["request"]["features"]["bookingVolumeGrowth7d"], 1)
        self.assertEqual(result["request"]["features"]["reconfirmationOverdueRate7d"], 1)
        self.assertEqual(result["request"]["features"]["inventoryUnavailableRate7d"], 1)
        self.assertNotIn("historyFailedCount", result["request"])
        self.assertNotIn("lineageDigest", result["request"])
        self.assertEqual(result["cohortBookingCodes"], ["YB-001"])

    def test_future_observations_final_bookings_and_private_truth_cannot_change_inputs(self):
        source = source_fixture()
        source["events"][0]["occurred_at"] = source["events"][0]["available_at"] = "2026-11-06T08:00:00+09:00"
        source["events"].append(status("HISTORICAL", "2026-09-01T08:00:00+09:00",
                                        identifier="HISTORICAL-CONFIRMED", target="2026-11-01"))
        original = self.project(source)
        source["events"] += [
            status("YB-001", "2026-11-08T08:00:00+09:00", state="CANCELLED",
                   identifier="LATE-CANCELLATION", available="2026-11-10T10:00:00+09:00"),
            status("FUTURE", "2026-11-10T10:00:00+09:00", identifier="FUTURE-BOOKING"),
            event("OBSERVED_OUTCOME", "YB-001", "2026-11-10T10:00:00+09:00",
                  {"label": 1, "check_in_date": TARGET}, identifier="FUTURE-OUTCOME"),
            event("OBSERVED_OUTCOME", "HISTORICAL", "2026-11-03T10:00:00+09:00",
                  {"label": 1, "check_in_date": "2026-11-01"}, identifier="LATE-OBSERVED-EVIDENCE",
                  available="2026-11-10T10:00:00+09:00"),
            # Private events may be intentionally incomplete; never parse them.
            {"event_type": "PRIVATE_WORLD_TRUTH", "hotel_id": "OTHER-HOTEL", "payload": {"label": 1}},
        ]
        source["hotel"]["private_profile"] = "CHANGED"
        source["bookings"] = [{"booking_id": "YB-001", "status": "CANCELLED"}]
        source["truth"] = [{"label": 1, "private_delivery_dropped": True}]
        changed = self.project(source)
        self.assertEqual(changed["request"], original["request"])
        self.assertEqual(changed["cohortBookingCodes"], original["cohortBookingCodes"])
        self.assertEqual(changed["provenance"]["lineageDigest"], original["provenance"]["lineageDigest"])
        self.assertNotEqual(changed["provenance"]["sourceSha256"], original["provenance"]["sourceSha256"])
        # Directly verify the builder cannot accidentally access root audits.
        with patch("travel_ml.risk_projection.feature_builder.build_features", wraps=feature_builder.build_features) as build:
            self.project(source)
            passed_source = build.call_args.args[0]
            self.assertEqual(set(passed_source), {"hotel", "events"})
            self.assertEqual(passed_source["hotel"], {"hotel_id": HOTEL})
            self.assertNotIn("PRIVATE_WORLD_TRUTH", {item["event_type"] for item in passed_source["events"]})

    def test_cohort_codes_are_sorted_exact_observed_platform_members(self):
        source = source_fixture()
        source["events"] += [
            status("YB-000", "2026-11-08T08:00:00+09:00", identifier="EARLIER"),
            status("CANCELLED", "2026-11-07T08:00:00+09:00", identifier="TO-CANCEL"),
            status("CANCELLED", "2026-11-08T08:00:00+09:00", identifier="CANCEL", state="CANCELLED"),
            status("OTHER", "2026-11-08T08:00:00+09:00", identifier="OTHER", channel="OTHER"),
        ]
        result = self.project(source)
        self.assertEqual(result["cohortBookingCodes"], ["YB-000", "YB-001"])
        self.assertEqual(result["request"]["features"]["targetConfirmedBookingCount"], 2)

    def test_wrong_hotel_or_coercible_source_identifiers_fail_before_feature_building(self):
        for mutate in (
            lambda source: source["hotel"].update(hotel_id="PRIVATE-HOTEL-MARKER"),
            lambda source: source["events"][0].update(hotel_id="PRIVATE-HOTEL-MARKER"),
            lambda source: source["events"].append({
                **status("FUTURE", "2026-11-10T10:00:00+09:00", identifier="FUTURE"),
                "hotel_id": "PRIVATE-HOTEL-MARKER"}),
            lambda source: source["events"][0].update(booking_id=1),
            lambda source: source["events"][0].update(event_id=True),
        ):
            with self.subTest(mutate=mutate):
                source = source_fixture()
                mutate(source)
                with patch("travel_ml.risk_projection.feature_builder.build_features") as build:
                    with self.assertRaises(ProjectionError) as caught:
                        self.project(source)
                    build.assert_not_called()
                    self.assertNotIn("PRIVATE-HOTEL-MARKER", str(caught.exception))

    def test_malformed_public_types_dates_and_nonfinite_values_do_not_get_coerced(self):
        for mutate in (
            lambda source: source["events"][0].update(occurred_at="2026-11-09T09:00:00+08:60"),
            lambda source: source["events"][0].update(available_at="2026-11-09T08:59:59+09:00"),
            lambda source: source["events"][0]["payload"].update(check_out_date="2026-11-10"),
            lambda source: source["events"][0]["payload"].update(rooms=True),
            lambda source: source["events"][0].update(payload=[]),
            lambda source: source.update(truth=[{"label": float("nan")}]),
            lambda source: source["events"].append(event("OBSERVED_OUTCOME", "YB-001", AS_OF,
                {"label": True, "check_in_date": "2026-11-01"})),
        ):
            with self.subTest(mutate=mutate):
                source = source_fixture()
                mutate(source)
                with self.assertRaises(ProjectionError):
                    self.project(source)

    def test_duplicate_event_replay_is_safe_but_conflicts_have_sanitized_errors(self):
        source = source_fixture()
        original = self.project(source)
        source["events"].append(deepcopy(source["events"][0]))
        self.assertEqual(self.project(source)["request"], original["request"])
        source["events"][1]["payload"]["rooms"] = 2
        source["events"][0]["event_id"] = source["events"][1]["event_id"] = "PRIVATE-EVENT-MARKER"
        with self.assertRaises(ProjectionError) as caught:
            self.project(source)
        self.assertNotIn("PRIVATE-EVENT-MARKER", str(caught.exception))

    def test_frozen_config_cannot_be_tuned_and_empty_cohort_is_not_a_normal_prediction(self):
        config = load_config()
        config["features"]["pastFailureRate90d"]["prior_alpha"] = 99
        with self.assertRaisesRegex(ProjectionError, "unchanged frozen"):
            prepare_projection(source_fixture(), HOTEL, TARGET, config)
        source = source_fixture()
        source["events"][0]["payload"]["channel"] = "OTHER"
        with self.assertRaisesRegex(ProjectionError, "NOT_ELIGIBLE"):
            self.project(source)


class RiskProjectionCliTests(unittest.TestCase):
    def run_cli(self, source, output):
        return subprocess.run([
            sys.executable, "-m", "travel_ml.risk_projection", "--source", str(source),
            "--hotel-id", HOTEL, "--target-check-in-date", TARGET, "--output", str(output),
        ], cwd=ROOT, capture_output=True, text=True, check=False)

    def test_cli_writes_only_new_output_and_prints_no_raw_observations(self):
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory) / "source.json", Path(directory) / "projection.json"
            source.write_text(json.dumps(source_fixture()), encoding="utf-8")
            result = self.run_cli(source, output)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads(result.stdout), {
                "status": "prepared", "format": "risk-event-projection-1",
                "sourceKind": "SYNTHETIC_OBSERVATIONS", "cohortCount": 1,
            })
            self.assertEqual(json.loads(output.read_text()), prepare_projection(source_fixture(), HOTEL, TARGET))
            written = output.read_bytes()
            second = self.run_cli(source, output)
            self.assertEqual(second.returncode, 2)
            self.assertIn("already exists", second.stderr)
            self.assertEqual(second.stdout, "")
            self.assertEqual(output.read_bytes(), written)

    def test_cli_rejects_duplicate_json_nonfinite_and_malformed_json_without_echoing_values(self):
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory) / "source.json", Path(directory) / "projection.json"
            fixture_text = json.dumps(source_fixture())
            for malformed in (
                '{"hotel":{"hotel_id":"PRIVATE-KEY-MARKER","hotel_id":"HOTEL-001"},"events":[]}',
                fixture_text[:-1] + ',"PRIVATE-KEY-MARKER":NaN}',
                fixture_text[:-1] + ',"PRIVATE-KEY-MARKER":1e999}',
                '{"PRIVATE-KEY-MARKER":',
            ):
                with self.subTest(malformed=malformed[:20]):
                    source.write_text(malformed, encoding="utf-8")
                    result = self.run_cli(source, output)
                    self.assertEqual(result.returncode, 2)
                    self.assertEqual(result.stdout, "")
                    self.assertNotIn("PRIVATE-KEY-MARKER", result.stderr)
                    self.assertNotIn("Traceback", result.stderr)
                    self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
