"""Focused local-launcher checks; no business DB, HTTP, model or training calls."""
from copy import deepcopy
from contextlib import redirect_stderr, redirect_stdout
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from travel_ml import risk_automation as automation
from travel_ml.common import ROOT


TARGET = "2026-11-10"
AUTH = {"ML_SERVICE_TOKEN": "private-ml-value", "BOOKING_RECONFIRMATION_MOCK_SERVICETOKEN": "private-mock-value"}


def batch_result(successful=True):
    return {"targetCheckInDate": TARGET, "eligibleHotels": 1,
            "hotels": [{"hotelId": "HOTEL-001", "state": "ASSESSED", "errorCode": None,
                        "assessment": {"assessmentId": 7, "rawRiskScore": .02, "selected": False,
                                       "rechecks": [],
                                       "ignoredRawPayload": "must-not-escape"}}],
            "resumedJobs": [{"state": "WAITING", "ignoredRawPayload": "must-not-escape"}],
            "inputErrors": [], "unusedProjections": 0, "workerError": None,
            "successful": successful}


class FakeProcess:
    def __init__(self, output, raw, code=0, interruption=None):
        output.write(raw)
        self.code = code
        self.interruption = interruption
        self.stopped = False
        self.terminated = False
        self.killed = False

    def wait(self, timeout=None):
        if self.interruption is not None:
            error, self.interruption = self.interruption, None
            raise error
        self.stopped = True
        return self.code

    def poll(self):
        return self.code if self.stopped else None

    def terminate(self):
        self.terminated = True

    def kill(self):
        self.killed = True


class RiskAutomationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.work = Path(self.temporary.name)
        self.source = json.loads((ROOT / "examples/risk-events-cold-start.json").read_text())
        self.source_path = self.work / "observations.json"
        self.source_path.write_text(json.dumps(self.source))
        self.catalog = self.work / "catalog.json"
        self.write_catalog([{"hotelId": "HOTEL-001", "path": "observations.json"}])
        self.jar = self.work / "backend with space.jar"
        self.jar.write_bytes(b"test-only placeholder; no execution")

    def write_catalog(self, sources):
        self.catalog.write_text(json.dumps({"format": "risk-observation-sources-1", "sources": sources}))

    def options(self, *extra):
        return ["--sources", str(self.catalog), "--target-check-in-date", TARGET,
                "--output", str(self.work / "generated"), "--jar", str(self.jar), *extra]

    def test_catalog_rejects_duplicate_hotels_and_ambiguous_json(self):
        sources = [{"hotelId": "HOTEL-001", "path": "observations.json"}] * 2
        self.write_catalog(sources)
        with self.assertRaises(automation.AutomationError):
            automation.load_sources(self.catalog)
        self.catalog.write_text('{"format":"x","format":"risk-observation-sources-1","sources":[]}')
        with self.assertRaises(automation.AutomationError):
            automation.load_sources(self.catalog)

    def test_each_cycle_rereads_observations_and_preserves_frozen_cutoff(self):
        sources = automation.load_sources(self.catalog)
        first, failures = automation.prepare_cycle(sources, TARGET, self.work / "generated")
        self.assertEqual(failures, [])
        first_manifest = json.loads(first.read_text())
        self.assertEqual(set(first_manifest), {"format", "targetCheckInDate", "projections"})
        before = json.loads(Path(first_manifest["projections"][0]).read_text())
        self.assertEqual(before["request"]["features"]["pastFailureRate90d"], .05)
        self.assertEqual(before["cohortBookingCodes"], ["YB-001"])
        late = deepcopy(self.source["events"][0])
        late.update(event_id="FUTURE-CANCELLATION", occurred_at="2026-11-09T10:00:00+09:00",
                    available_at="2026-11-09T10:00:00+09:00")
        late["payload"]["status"] = "CANCELLED"
        self.source["events"].append(late)
        self.source["truth"] = {"YB-001": "FAILURE"}
        self.source_path.write_text(json.dumps(self.source))
        second, _ = automation.prepare_cycle(sources, TARGET, self.work / "generated")
        after = json.loads(Path(json.loads(second.read_text())["projections"][0]).read_text())
        self.assertNotEqual(first.parent, second.parent)
        self.assertEqual(before["request"], after["request"])
        self.assertEqual(before["cohortBookingCodes"], after["cohortBookingCodes"])
        self.assertNotEqual(before["provenance"]["sourceSha256"], after["provenance"]["sourceSha256"])
        # Earlier output remains an immutable record, not overwritten by the loop.
        self.assertEqual(json.loads(Path(first_manifest["projections"][0]).read_text()), before)

    def test_one_bad_source_is_reported_without_dropping_the_valid_projection(self):
        self.write_catalog([{"hotelId": "HOTEL-001", "path": "observations.json"},
                            {"hotelId": "HOTEL-002", "path": "missing-private-path.json"}])
        manifest, failures = automation.prepare_cycle(automation.load_sources(self.catalog), TARGET,
                                                       self.work / "generated")
        self.assertEqual(len(json.loads(manifest.read_text())["projections"]), 1)
        self.assertEqual(failures, [{"hotelId": "HOTEL-002", "code": "PREPARATION_FAILED"}])
        self.assertNotIn("missing-private-path", json.dumps(failures))

    def test_excessive_integer_in_one_source_does_not_block_other_hotels_or_spring(self):
        (self.work / "bad.json").write_text('{"value":' + '9' * 5000 + '}')
        self.write_catalog([{"hotelId": "HOTEL-001", "path": "observations.json"},
                            {"hotelId": "HOTEL-002", "path": "bad.json"}])
        manifest, failures = automation.prepare_cycle(automation.load_sources(self.catalog), TARGET,
                                                       self.work / "generated")
        self.assertEqual(len(json.loads(manifest.read_text())["projections"]), 1)
        self.assertEqual(failures, [{"hotelId": "HOTEL-002", "code": "PREPARATION_FAILED"}])

    def test_auth_is_checked_before_spring_and_values_never_enter_command(self):
        for environment in ({}, {"ML_SERVICE_TOKEN": "configured"},
                            {**AUTH, "BOOKING_RECONFIRMATION_MOCK_SERVICE_TOKEN": "different"}):
            with self.subTest(environment=list(environment)), patch.dict(os.environ, environment, clear=True), \
                    patch.object(automation, "run_spring") as run, redirect_stderr(io.StringIO()) as errors:
                self.assertEqual(automation.main(self.options()), 2)
                run.assert_not_called()
                self.assertNotIn("different", errors.getvalue())
        command = automation.spring_command("java", self.jar, self.work / "manifest with space.json", "F1_BASELINE")
        self.assertEqual(command[2], str(self.jar))
        self.assertIn("--risk.demo.enabled=false", command)
        self.assertIn("--risk.jobs.demo.enabled=false", command)
        for value in AUTH.values():
            self.assertNotIn(value, " ".join(command))

    def test_child_output_is_allowlisted_and_no_startup_or_extra_payload_is_echoed(self):
        raw = b"unrelated startup secret must-not-escape\n" + automation.RESULT_PREFIX.encode() \
            + json.dumps(batch_result()).encode() + b"\n"
        processes = []
        def spawn(command, **kwargs):
            self.assertFalse(kwargs["shell"])
            self.assertEqual(kwargs["cwd"], automation.REPOSITORY / "backend")
            child = FakeProcess(kwargs["stdout"], raw)
            processes.append(child)
            return child
        with patch.object(automation.subprocess, "Popen", side_effect=spawn):
            summary = automation.run_spring(["java", "-jar", str(self.jar)], 5)
        self.assertTrue(summary["successful"])
        self.assertEqual(summary["resumedJobStates"], {"WAITING": 1})
        self.assertEqual(summary["hotels"][0]["assessmentId"], 7)
        self.assertNotIn("must-not-escape", json.dumps(summary))
        self.assertEqual(len(processes), 1)

    def test_immediate_recheck_errors_are_visible_without_any_raw_response(self):
        value = batch_result(False)
        value["hotels"][0]["assessment"]["rechecks"] = [
            {"status": "FAILED", "errorCode": "AUTHENTICATION", "rawResponse": "must-not-escape"},
            {"status": "PENDING", "errorCode": "UNAVAILABLE"},
        ]
        hotel = automation.summarize_batch(value)["hotels"][0]
        self.assertEqual(hotel["recheckStates"], {"FAILED": 1, "PENDING": 1})
        self.assertEqual(hotel["recheckErrors"], ["AUTHENTICATION", "UNAVAILABLE"])
        self.assertNotIn("must-not-escape", json.dumps(hotel))

    def test_child_nonzero_or_ambiguous_result_does_not_relay_raw_error_text(self):
        for code, raw, expected in [
            (1, b"database password=must-not-escape", "SPRING_BATCH_FAILED"),
            (0, b"nothing here", "SPRING_RESULT_INVALID"),
            (0, (automation.RESULT_PREFIX + "{}\n") .encode() * 2, "SPRING_RESULT_INVALID"),
        ]:
            def spawn(command, **kwargs):
                return FakeProcess(kwargs["stdout"], raw, code)
            with self.subTest(code=code, raw_size=len(raw)), \
                    patch.object(automation.subprocess, "Popen", side_effect=spawn):
                with self.assertRaisesRegex(automation.AutomationError, expected):
                    automation.run_spring(["java"], 5)

    def test_timeout_and_interrupt_reap_only_the_spawned_child(self):
        for interruption, expected in [(subprocess.TimeoutExpired("java", 5), automation.AutomationError),
                                       (KeyboardInterrupt(), KeyboardInterrupt)]:
            processes = []
            def spawn(command, **kwargs):
                child = FakeProcess(kwargs["stdout"], b"", interruption=interruption)
                processes.append(child)
                return child
            with self.subTest(interruption=type(interruption).__name__), \
                    patch.object(automation.subprocess, "Popen", side_effect=spawn):
                with self.assertRaises(expected):
                    automation.run_spring(["java"], 5)
            self.assertTrue(processes[0].terminated)
            self.assertTrue(processes[0].stopped)

    def test_cycles_are_sequential_and_final_recovery_can_exit_successfully(self):
        failure = automation.AutomationError("SPRING_BATCH_FAILED")
        success = automation.summarize_batch(batch_result())
        with patch.dict(os.environ, AUTH, clear=True), \
                patch.object(automation, "run_spring", side_effect=[failure, success]) as run, \
                patch.object(automation.time, "sleep") as sleep, redirect_stdout(io.StringIO()) as output:
            self.assertEqual(automation.main(self.options("--cycles", "2", "--interval-seconds", "1")), 0)
        self.assertEqual(run.call_count, 2)
        sleep.assert_called_once_with(1)
        reports = [json.loads(line.split(" ", 1)[1]) for line in output.getvalue().splitlines()]
        self.assertEqual([r["successful"] for r in reports], [False, True])
        self.assertEqual([r["cycle"] for r in reports], [1, 2])

    def test_partial_preparation_failure_still_runs_spring_but_exits_nonzero(self):
        self.write_catalog([{"hotelId": "HOTEL-001", "path": "observations.json"},
                            {"hotelId": "HOTEL-002", "path": "missing.json"}])
        with patch.dict(os.environ, AUTH, clear=True), \
                patch.object(automation, "run_spring", return_value=automation.summarize_batch(batch_result())) as run, \
                redirect_stdout(io.StringIO()) as output:
            self.assertEqual(automation.main(self.options()), 2)
        run.assert_called_once()
        report = json.loads(output.getvalue().split(" ", 1)[1])
        self.assertEqual(report["preparationFailures"], [{"hotelId": "HOTEL-002", "code": "PREPARATION_FAILED"}])
        self.assertFalse(report["successful"])

    def test_valid_empty_cohort_can_still_drain_jobs_and_finish_successfully(self):
        self.source["events"] = []
        self.source_path.write_text(json.dumps(self.source))
        result = batch_result()
        result.update(eligibleHotels=0, hotels=[], resumedJobs=[{"state": "COMPLETED"}])
        with patch.dict(os.environ, AUTH, clear=True), \
                patch.object(automation, "run_spring", return_value=automation.summarize_batch(result)) as run, \
                redirect_stdout(io.StringIO()) as output:
            self.assertEqual(automation.main(self.options()), 0)
        run.assert_called_once()
        manifest_argument = next(arg for arg in run.call_args.args[0]
                                 if arg.startswith("--risk.batch.manifest-file="))
        manifest = json.loads(Path(manifest_argument.split("=", 1)[1]).read_text())
        self.assertEqual(manifest["projections"], [])
        report = json.loads(output.getvalue().split(" ", 1)[1])
        self.assertEqual(report["preparationFailures"], [])
        self.assertEqual(report["batch"]["eligibleHotels"], 0)
        self.assertEqual(report["batch"]["resumedJobStates"], {"COMPLETED": 1})
        self.assertTrue(report["successful"])

    def test_different_child_target_is_not_reported_as_success(self):
        wrong = automation.summarize_batch(batch_result())
        wrong["targetCheckInDate"] = "2026-11-11"
        with patch.dict(os.environ, AUTH, clear=True), \
                patch.object(automation, "run_spring", return_value=wrong), \
                redirect_stdout(io.StringIO()) as output:
            self.assertEqual(automation.main(self.options()), 2)
        report = json.loads(output.getvalue().split(" ", 1)[1])
        self.assertEqual(report["errorCode"], "SPRING_RESULT_SCOPE_MISMATCH")
        self.assertFalse(report["successful"])


if __name__ == "__main__":
    unittest.main()
