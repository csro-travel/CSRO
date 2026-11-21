"""Check a running API-023 server; never print the service token.

Run from ml/: python scripts/check_risk_api.py
ML_SERVICE_TOKEN must also be set in this client terminal. The default model
is the local reference bundle; --model-dir supports an explicitly chosen bundle.
"""
from __future__ import annotations

import argparse
from copy import deepcopy
import json
import math
import os
from pathlib import Path
import sys
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener
from uuid import UUID

ML_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ML_ROOT))

from travel_ml.__main__ import predict  # noqa: E402


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def main() -> int:
    parser = argparse.ArgumentParser(description="Check API-023 against local saved-model CLI inference")
    parser.add_argument("--base-url", default="http://127.0.0.1:8002")
    parser.add_argument("--model-dir", type=Path, default=ML_ROOT / "artifacts/reference_model_v02")
    args = parser.parse_args()
    token = os.environ.get("ML_SERVICE_TOKEN")
    if not token or not token.strip():
        print("Set ML_SERVICE_TOKEN in this client terminal before running checks.", file=sys.stderr)
        return 1
    opener = build_opener(NoRedirect())
    base = args.base_url.rstrip("/")
    endpoint = "/internal/v1/risk-assessments"
    sample_path = ML_ROOT / "examples/risk-request.json"
    sample = json.loads(sample_path.read_text(encoding="utf-8-sig"))

    def call(path, payload=None, authenticated=True, raw=None):
        headers = {"Accept": "application/json"}
        if authenticated:
            headers["Authorization"] = "Bearer " + token
        data = raw if raw is not None else json.dumps(payload).encode("utf-8") if payload is not None else None
        if data is not None:
            headers["Content-Type"] = "application/json"
        req = Request(base + path, data=data, headers=headers)
        try:
            response = opener.open(req, timeout=10)
        except HTTPError as exc:
            response = exc
        with response:
            body = json.loads(response.read().decode("utf-8"))
            return response.status, response.headers, body

    def problem(status, headers, body, expected_status, code):
        assert status == expected_status, "Unexpected HTTP error status"
        assert headers.get_content_type() == "application/problem+json", "Unexpected error content type"
        assert set(body) == {"type", "title", "status", "detail", "code", "traceId"}, "Unexpected Problem fields"
        assert body["status"] == status and body["code"] == code, "Unexpected Problem code"
        UUID(body["traceId"])
        assert headers.get("X-Request-ID") == body["traceId"], "Trace header mismatch"

    try:
        expected = predict(args.model_dir, sample_path)
        status, _, body = call("/healthz", authenticated=False)
        assert status == 200 and body == {"status": "alive", "modelReady": True}, "Liveness/model not ready"
        print("Liveness: 200 PASS")
        status, _, body = call("/readyz", authenticated=False)
        assert status == 200 and body == {"status": "ready", "modelReady": True, "authConfigured": True}, "Server not ready"
        print("Readiness: 200 PASS")
        for repeat in range(2):
            status, headers, body = call(endpoint, sample)
            assert status == 200 and set(body) == set(expected), "Unexpected success response"
            UUID(headers.get("X-Request-ID", ""))
            assert all(body[key] == expected[key] for key in expected if key != "riskScore"), "Response identity/version mismatch"
            assert type(body["riskScore"]) in (int, float) and 0 <= body["riskScore"] <= 1, "Invalid risk score"
            assert math.isclose(body["riskScore"], expected["riskScore"], rel_tol=0, abs_tol=1e-12), "HTTP and local CLI scores differ"
            print(f"Inference {repeat + 1}: 200, HTTP score equals local CLI PASS")
        problem(*call(endpoint, sample, authenticated=False), 401, "UNAUTHENTICATED_SERVICE")
        print("Missing service authentication: 401 PASS")
        problem(*call(endpoint, raw=b"{"), 400, "INVALID_RISK_REQUEST")
        print("Malformed JSON: 400 PASS")
        unsupported = deepcopy(sample)
        unsupported["featureSetVersion"] = "risk-features-0.1"
        problem(*call(endpoint, unsupported), 422, "FEATURE_SCHEMA_UNSUPPORTED")
        print("Unsupported feature version: 422 PASS")
        invalid = deepcopy(sample)
        invalid["features"]["pastFailureRate90d"] = 1.1
        problem(*call(endpoint, invalid), 422, "INVALID_FEATURE_VALUES")
        print("Out-of-range feature: 422 PASS")
    except (AssertionError, OSError, ValueError, KeyError, TypeError, URLError):
        # Keep credentials, filesystem paths and server-provided bodies out of logs.
        print("API-023 checks failed. Check server readiness, token and model selection locally.", file=sys.stderr)
        return 1
    print("Running API-023 checks: passed (8 checks)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
