"""Explicit local launcher: observed files -> frozen projections -> Spring batch.

Python never connects to the business DB or calls an HTTP service here. The
unchanged offline feature builder prepares local handoff files; a Spring JAR owns
all inference HTTP, selection, writes and existing RECHECK-job recovery.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import date
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import uuid
from typing import Any

from .common import ROOT
from .risk_projection import ProjectionError, prepare_projection

REPOSITORY = ROOT.parent
POLICIES = ("F1_BASELINE", "RECALL_50_F1", "MAX_OBSERVED_RECALL")
RESULT_PREFIX = "RISK_BATCH_RESULT "
MAX_SOURCES = 100
MAX_CAPTURE_BYTES = 16 * 1024 * 1024
_NOT_ELIGIBLE = "NOT_ELIGIBLE: no confirmed platform bookings were known at D-1."
_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}\Z")
_CODE = re.compile(r"[A-Z][A-Z0-9_]{0,79}\Z")


class AutomationError(ValueError):
    """Only stable codes are emitted; no source, token or child exception text."""


@dataclass(frozen=True)
class Source:
    hotel_id: str
    path: Path


def _object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise AutomationError("INVALID_JSON")
        result[key] = value
    return result


def _constant(_):
    raise AutomationError("INVALID_JSON")


def _read_json(path: Path, limit: int) -> Any:
    try:
        with path.open("rb") as stream:
            raw = stream.read(limit + 1)
        if len(raw) > limit:
            raise AutomationError("JSON_SIZE_LIMIT")
        return json.loads(raw.decode("utf-8-sig"), object_pairs_hook=_object, parse_constant=_constant)
    except AutomationError:
        raise
    except (OSError, UnicodeError, ValueError, RecursionError):
        raise AutomationError("INVALID_JSON") from None


def load_sources(path: Path) -> list[Source]:
    """Explicit catalog paths are resolved relative to the catalog, never cwd."""
    document = _read_json(path, 1_048_576)
    if (type(document) is not dict or set(document) != {"format", "sources"}
            or document["format"] != "risk-observation-sources-1"
            or type(document["sources"]) is not list
            or not 1 <= len(document["sources"]) <= MAX_SOURCES):
        raise AutomationError("INVALID_SOURCE_CATALOG")
    result, hotels = [], set()
    for item in document["sources"]:
        if (type(item) is not dict or set(item) != {"hotelId", "path"}
                or type(item["hotelId"]) is not str or not _ID.fullmatch(item["hotelId"])
                or item["hotelId"] in hotels
                or type(item["path"]) is not str or not item["path"].strip()
                or any(ord(char) < 32 or ord(char) == 127 for char in item["path"])):
            raise AutomationError("INVALID_SOURCE_CATALOG")
        hotels.add(item["hotelId"])
        source_path = Path(item["path"])
        if not source_path.is_absolute():
            source_path = path.parent / source_path
        result.append(Source(item["hotelId"], source_path.resolve()))
    return result


def _write_json(path: Path, value: Any) -> None:
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False)
        stream.write("\n")


def prepare_cycle(sources: list[Source], target: str, output: Path) -> tuple[Path, list[dict]]:
    """Each cycle reads fresh observations and exclusively creates its own files."""
    try:
        output.mkdir(parents=True, exist_ok=True)
        run = output / ("cycle-" + uuid.uuid4().hex)
        run.mkdir()
        projections, failures = [], []
        for index, source in enumerate(sources):
            try:
                observed = _read_json(source.path, 64 * 1024 * 1024)
                projection = prepare_projection(observed, source.hotel_id, target)
            except ProjectionError as error:
                # A valid history can have no target-date cohort. Spring still
                # decides DB eligibility and fails closed if it needed this input.
                if str(error) == _NOT_ELIGIBLE:
                    continue
                failures.append({"hotelId": source.hotel_id, "code": "PREPARATION_FAILED"})
                continue
            except AutomationError:
                failures.append({"hotelId": source.hotel_id, "code": "PREPARATION_FAILED"})
                continue
            path = run / f"projection-{index:03d}.json"
            _write_json(path, projection)
            projections.append(str(path.resolve()))
        manifest = run / "batch.json"
        _write_json(manifest, {"format": "risk-automation-batch-1", "targetCheckInDate": target,
                               "projections": projections})
        return manifest, failures
    except OSError:
        raise AutomationError("PREPARATION_STORAGE_FAILED") from None


def require_auth(environment: dict[str, str]) -> None:
    if not environment.get("ML_SERVICE_TOKEN", "").strip():
        raise AutomationError("ML_AUTH_MISSING")
    tokens = [environment.get(name, "").strip() for name in (
        "BOOKING_RECONFIRMATION_MOCK_SERVICETOKEN", "BOOKING_RECONFIRMATION_MOCK_SERVICE_TOKEN")]
    if not any(tokens):
        raise AutomationError("MOCK_AUTH_MISSING")
    if all(tokens) and tokens[0] != tokens[1]:
        raise AutomationError("MOCK_AUTH_AMBIGUOUS")


def resolve_jar(explicit: Path | None) -> Path:
    if explicit is not None:
        if not explicit.is_file() or explicit.suffix != ".jar":
            raise AutomationError("SPRING_JAR_MISSING")
        return explicit.resolve()
    jars = [p for p in (REPOSITORY / "backend/build/libs").glob("*.jar")
            if p.is_file() and not p.name.endswith("-plain.jar")]
    if len(jars) != 1:
        raise AutomationError("SPRING_JAR_MISSING_OR_AMBIGUOUS")
    return jars[0].resolve()


def spring_command(java: str, jar: Path, manifest: Path, policy: str) -> list[str]:
    return [java, "-jar", str(jar), "--spring.profiles.active=local",
            "--spring.main.web-application-type=none",
            "--risk.demo.enabled=false", "--risk.jobs.demo.enabled=false",
            "--risk.batch.enabled=true", "--risk.batch.manifest-file=" + str(manifest),
            "--risk.batch.policy=" + policy, "--risk.batch.max-targets=100", "--risk.batch.job-limit=10"]


def _stop_child(child) -> None:
    if child.poll() is not None:
        return
    try:
        child.terminate()
        child.wait(timeout=5)
    except (OSError, subprocess.TimeoutExpired):
        try:
            child.kill()
            child.wait(timeout=5)
        except (OSError, subprocess.TimeoutExpired):
            pass


def _safe_code(value: Any) -> str | None:
    if value is None:
        return None
    if type(value) is not str or not _CODE.fullmatch(value):
        raise AutomationError("SPRING_RESULT_INVALID")
    return value


def summarize_batch(value: Any) -> dict:
    """Allowlist operational data rather than echoing arbitrary child JSON/logs."""
    required = {"targetCheckInDate", "eligibleHotels", "hotels", "resumedJobs", "inputErrors",
                "unusedProjections", "workerError", "successful"}
    if (type(value) is not dict or set(value) != required
            or type(value["successful"]) is not bool
            or type(value["hotels"]) is not list or type(value["resumedJobs"]) is not list
            or type(value["inputErrors"]) is not list
            or type(value["eligibleHotels"]) is not int or not 0 <= value["eligibleHotels"] <= MAX_SOURCES
            or type(value["unusedProjections"]) is not int or not 0 <= value["unusedProjections"] <= MAX_SOURCES):
        raise AutomationError("SPRING_RESULT_INVALID")
    hotels = []
    for item in value["hotels"]:
        if (type(item) is not dict or set(item) != {"hotelId", "state", "assessment", "errorCode"}
                or type(item["hotelId"]) is not str or not _ID.fullmatch(item["hotelId"])
                or item["state"] not in {"ASSESSED", "REUSED", "FAILED"}):
            raise AutomationError("SPRING_RESULT_INVALID")
        assessment = item["assessment"]
        result = {"hotelId": item["hotelId"], "state": item["state"],
                  "errorCode": _safe_code(item["errorCode"])}
        if assessment is not None:
            if (type(assessment) is not dict or type(assessment.get("assessmentId")) is not int
                    or assessment["assessmentId"] <= 0 or type(assessment.get("selected")) is not bool
                    or type(assessment.get("rawRiskScore")) not in (int, float)
                    or not math.isfinite(assessment["rawRiskScore"])
                    or not 0 <= assessment["rawRiskScore"] <= 1):
                raise AutomationError("SPRING_RESULT_INVALID")
            result.update(assessmentId=assessment["assessmentId"], selected=assessment["selected"],
                          rawRiskScore=assessment["rawRiskScore"])
            rechecks = assessment.get("rechecks")
            if type(rechecks) is not list:
                raise AutomationError("SPRING_RESULT_INVALID")
            recheck_states, recheck_errors = {}, set()
            for recheck in rechecks:
                if (type(recheck) is not dict
                        or recheck.get("status") not in {"COMPLETED", "PENDING", "FAILED"}):
                    raise AutomationError("SPRING_RESULT_INVALID")
                state = recheck["status"]
                recheck_states[state] = recheck_states.get(state, 0) + 1
                error = _safe_code(recheck.get("errorCode"))
                if error is not None:
                    recheck_errors.add(error)
            result.update(recheckStates=recheck_states, recheckErrors=sorted(recheck_errors))
        hotels.append(result)
    states = {}
    for job in value["resumedJobs"]:
        if type(job) is not dict or job.get("state") not in {"PENDING", "RUNNING", "WAITING", "COMPLETED", "FAILED"}:
            raise AutomationError("SPRING_RESULT_INVALID")
        states[job["state"]] = states.get(job["state"], 0) + 1
    try:
        target = value["targetCheckInDate"]
        if type(target) is not str or date.fromisoformat(target).isoformat() != target:
            raise ValueError
    except (ValueError, TypeError):
        raise AutomationError("SPRING_RESULT_INVALID") from None
    return {"targetCheckInDate": target, "eligibleHotels": value["eligibleHotels"], "hotels": hotels,
            "resumedJobStates": states, "inputErrors": [_safe_code(code) for code in value["inputErrors"]],
            "unusedProjections": value["unusedProjections"], "workerError": _safe_code(value["workerError"]),
            "successful": value["successful"]}


def run_spring(command: list[str], timeout: int) -> dict:
    """Capture privately, print no raw output, and reap only this child on stop."""
    child = None
    try:
        with tempfile.TemporaryFile() as capture:
            child = subprocess.Popen(command, cwd=REPOSITORY / "backend", stdout=capture,
                                     stderr=subprocess.STDOUT, shell=False)
            try:
                code = child.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                _stop_child(child)
                raise AutomationError("SPRING_TIMEOUT") from None
            except KeyboardInterrupt:
                _stop_child(child)
                raise
            if code != 0:
                raise AutomationError("SPRING_BATCH_FAILED")
            capture.seek(0)
            raw = capture.read(MAX_CAPTURE_BYTES + 1)
            if len(raw) > MAX_CAPTURE_BYTES:
                raise AutomationError("SPRING_OUTPUT_LIMIT")
            try:
                lines = raw.decode("utf-8").splitlines()
                matches = [line[len(RESULT_PREFIX):] for line in lines if line.startswith(RESULT_PREFIX)]
                if len(matches) != 1:
                    raise ValueError
                value = json.loads(matches[0], object_pairs_hook=_object, parse_constant=_constant)
                return summarize_batch(value)
            except (UnicodeError, json.JSONDecodeError, ValueError, TypeError, RecursionError):
                raise AutomationError("SPRING_RESULT_INVALID") from None
    except OSError:
        if child is not None:
            _stop_child(child)
        raise AutomationError("SPRING_LAUNCH_FAILED") from None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sources", type=Path, default=ROOT / "config/risk_automation_sources_v1.json")
    parser.add_argument("--target-check-in-date", required=True)
    parser.add_argument("--policy", choices=POLICIES, default="F1_BASELINE")
    parser.add_argument("--cycles", type=int, default=1, help="0 explicitly repeats until Ctrl+C")
    parser.add_argument("--interval-seconds", type=int, default=30, help="Delay after a completed cycle")
    parser.add_argument("--timeout-seconds", type=int, default=300)
    parser.add_argument("--output", type=Path, default=ROOT / "generated_risk_automation")
    parser.add_argument("--jar", type=Path)
    parser.add_argument("--java", default="java")
    args = parser.parse_args(argv)
    try:
        if (date.fromisoformat(args.target_check_in_date).isoformat() != args.target_check_in_date
                or not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", args.target_check_in_date)
                or args.cycles < 0 or not 1 <= args.interval_seconds <= 3600
                or not 5 <= args.timeout_seconds <= 3600):
            raise AutomationError("INVALID_AUTOMATION_OPTIONS")
        sources_path = args.sources.resolve()
        load_sources(sources_path)
        require_auth(os.environ)
        jar = resolve_jar(args.jar)
        cycle, last_success = 0, False
        while args.cycles == 0 or cycle < args.cycles:
            cycle += 1
            sources = load_sources(sources_path)
            report = {"cycle": cycle, "policy": args.policy, "preparationFailures": [], "batch": None,
                      "errorCode": None, "successful": False}
            try:
                manifest, failures = prepare_cycle(sources, args.target_check_in_date, args.output.resolve())
                report["preparationFailures"] = failures
                report["batch"] = run_spring(spring_command(args.java, jar, manifest, args.policy),
                                              args.timeout_seconds)
                if report["batch"]["targetCheckInDate"] != args.target_check_in_date:
                    raise AutomationError("SPRING_RESULT_SCOPE_MISMATCH")
                report["successful"] = not failures and report["batch"]["successful"]
            except AutomationError as error:
                report["errorCode"] = str(error)
            last_success = report["successful"]
            print("RISK_AUTOMATION_CYCLE " + json.dumps(report, ensure_ascii=False, allow_nan=False), flush=True)
            if args.cycles == 0 or cycle < args.cycles:
                time.sleep(args.interval_seconds)
        return 0 if last_success else 2
    except KeyboardInterrupt:
        print("Automation stopped; committed Spring jobs retain their existing request IDs.", file=sys.stderr)
        return 130
    except (AutomationError, ValueError, OSError):
        error = sys.exc_info()[1]
        code = str(error) if isinstance(error, AutomationError) else "INVALID_AUTOMATION_OPTIONS"
        print("Error: " + code, file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
