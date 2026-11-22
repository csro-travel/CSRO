"""Read-only API-023 validation and a cached LightGBM inference runtime.

This module does not calculate features, select operational thresholds, retrain,
or access a business database.  A model directory is verified once at startup;
subsequent requests use that same in-memory checkpoint until process restart.
"""
from __future__ import annotations

from copy import deepcopy
from datetime import date, datetime, time, timedelta
from functools import lru_cache
import hashlib
import json
import math
from pathlib import Path
import re
from threading import RLock
from typing import Any

from jsonschema import Draft202012Validator

from .common import FEATURE_NAMES, ROOT, SEOUL

FEATURE_SET_VERSION = "risk-features-0.2"
SCHEMA_PATH = ROOT / "schemas" / "features-v0.2.schema.json"
_REQUEST_FIELDS = frozenset(("hotelId", "targetCheckInDate", "asOf", "featureSetVersion", "features"))
_MODEL_FILES = frozenset((
    "manifest.json", "model.txt", "model_metadata.json", "audit_slices.json",
    "evaluation_report.json", "evaluation_report.md", "search_results.csv",
    "test_predictions.csv",
))
_DATE = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}\Z")
_AWARE_ISO = re.compile(
    r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}"
    r"(?::[0-9]{2}(?:\.[0-9]{1,6})?)?"
    r"(?:Z|[+-](?:[01][0-9]|2[0-3]):[0-5][0-9])\Z"
)
_SHA256 = re.compile(r"[0-9a-f]{64}\Z")
_UNAVAILABLE_DETAIL = "The risk model is unavailable."


class RiskRequestError(ValueError):
    """A public, value-free request error suitable for a Problem JSON response."""

    def __init__(self, status_code: int, code: str, detail: str):
        super().__init__(detail)
        self.status_code = status_code
        self.code = code
        self.detail = detail


class ModelUnavailableError(RuntimeError):
    """The startup model could not be made ready; no fallback score is valid."""

    code = "MODEL_UNAVAILABLE"
    detail = _UNAVAILABLE_DETAIL

    def __init__(self):
        super().__init__(self.detail)


def _invalid_request(detail: str) -> RiskRequestError:
    return RiskRequestError(400, "INVALID_RISK_REQUEST", detail)


def _invalid_features(detail: str) -> RiskRequestError:
    return RiskRequestError(422, "INVALID_FEATURE_VALUES", detail)


@lru_cache(maxsize=1)
def _feature_validator() -> Draft202012Validator:
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema)


def validate_request(payload: Any) -> dict:
    """Validate v0.2 without coercing caller values or reflecting them in errors.

    As in the existing CLI, JSON Schema's mathematical integer semantics allow
    ``12.0`` for the booking count.  Booleans are not numbers.  UTC representations
    of the same D-1 09:00 Korean instant are allowed and echoed unchanged.
    """
    if not isinstance(payload, dict) or set(payload) != _REQUEST_FIELDS:
        raise _invalid_request("The request must contain exactly the five API-023 fields.")
    for name in _REQUEST_FIELDS - {"features"}:
        if not isinstance(payload[name], str) or not payload[name].strip():
            raise _invalid_request("Request identifiers, dates and versions must be nonempty strings.")
    if not isinstance(payload["features"], dict):
        raise _invalid_request("features must be a JSON object.")
    try:
        # Reject NaN/Infinity and non-JSON Python objects, including in extra fields.
        json.dumps(payload, allow_nan=False)
    except (TypeError, ValueError, OverflowError, RecursionError):
        raise _invalid_request("The request must contain finite JSON values.") from None
    try:
        if not _DATE.fullmatch(payload["targetCheckInDate"]):
            raise ValueError
        target = date.fromisoformat(payload["targetCheckInDate"])
        if not _AWARE_ISO.fullmatch(payload["asOf"]):
            raise ValueError
        observed_at = datetime.fromisoformat(payload["asOf"])
        if observed_at.tzinfo is None or observed_at.utcoffset() is None:
            raise ValueError
    except (ValueError, OverflowError):
        raise _invalid_request("Use a valid YYYY-MM-DD target date and a timezone-aware ISO asOf.") from None
    if payload["featureSetVersion"] != FEATURE_SET_VERSION:
        raise RiskRequestError(422, "FEATURE_SCHEMA_UNSUPPORTED", "The feature schema version is unsupported.")
    errors = list(_feature_validator().iter_errors(payload["features"]))
    if any(error.validator == "type" for error in errors):
        raise _invalid_request("Feature field types must match risk-features-0.2.")
    if errors:
        raise _invalid_features("Supply exactly the six v0.2 features within their declared bounds.")
    try:
        if not all(math.isfinite(float(payload["features"][name])) for name in FEATURE_NAMES):
            raise ValueError
    except (TypeError, ValueError, OverflowError):
        raise _invalid_request("Features must be finite values representable by the model.") from None
    try:
        expected = datetime.combine(target - timedelta(days=1), time(9), SEOUL)
    except OverflowError:
        raise _invalid_features("The target date must permit a D-1 prediction instant.") from None
    if observed_at != expected:
        raise _invalid_features("asOf must equal D-1 09:00 Asia/Seoul.")
    return deepcopy(payload)


def _read_checked(path: Path, root: Path) -> bytes:
    if not path.resolve().is_relative_to(root):
        raise ValueError("Artifact files must remain inside the model directory")
    return path.read_bytes()


def _load_verified_model(model_dir: Path):
    """Read verified bytes once; never reopen model.txt through LightGBM."""
    import lightgbm as lgb

    root = model_dir.resolve()
    actual = {path.relative_to(root).as_posix() for path in root.rglob("*") if path.is_file()}
    if actual != _MODEL_FILES:
        raise ValueError("Unexpected model artifact file set")
    manifest = json.loads(_read_checked(root / "manifest.json", root))
    expected = manifest.get("files")
    if not isinstance(expected, dict) or set(expected) != _MODEL_FILES - {"manifest.json"}:
        raise ValueError("The model manifest file set is invalid")
    buffers = {}
    for name in sorted(expected):
        digest = expected[name]
        if not isinstance(digest, str) or not _SHA256.fullmatch(digest):
            raise ValueError("Invalid model artifact digest")
        data = _read_checked(root / name, root)
        if hashlib.sha256(data).hexdigest() != digest:
            raise ValueError("Model artifact checksum mismatch")
        if name in {"model.txt", "model_metadata.json"}:
            buffers[name] = data
    metadata = json.loads(buffers["model_metadata.json"])
    if (manifest.get("type") != "model" or manifest.get("isSynthetic") is not True
            or manifest.get("featureSetVersion") != FEATURE_SET_VERSION
            or metadata.get("artifactFormat") != "lightgbm-text-v1"
            or metadata.get("featureSetVersion") != FEATURE_SET_VERSION
            or metadata.get("featureNames") != list(FEATURE_NAMES)):
        raise ValueError("The model metadata contract is invalid")
    model_bytes = buffers["model.txt"]
    model_digest = hashlib.sha256(model_bytes).hexdigest()
    version = metadata.get("modelVersion")
    if (metadata.get("modelSha256") != model_digest
            or not isinstance(version, str) or not version.strip()
            or version != "process-synthetic-lightgbm-0.2-" + model_digest[:12]
            or manifest.get("modelVersion") != version):
        raise ValueError("The model identity does not match the verified bytes")
    iteration = metadata.get("bestIteration")
    if type(iteration) is not int or iteration < 1:
        raise ValueError("The model checkpoint metadata is invalid")
    # Both checks above refer to this same immutable buffer, closing the common
    # verify-file-then-reopen-file race for the model consumed by the predictor.
    model = lgb.Booster(model_str=model_bytes.decode("utf-8"))
    if (model.num_feature() != len(FEATURE_NAMES)
            or model.feature_name() != list(FEATURE_NAMES)
            or model.current_iteration() != iteration
            or not str(model.dump_model().get("objective", "")).startswith("binary")):
        raise ValueError("The loaded model has an incompatible feature or checkpoint contract")
    return model, version


class RiskModelRuntime:
    """Thread-safe, startup-loaded model with a fail-closed readiness state."""

    def __init__(self, model_dir: Path):
        self._model_dir = Path(model_dir)
        self._lock = RLock()
        self._load_attempted = False
        self._ready = False
        self._model = None
        self._model_version: str | None = None
        self._load_error: str | None = None

    @property
    def ready(self) -> bool:
        with self._lock:
            return self._ready

    @property
    def model_version(self) -> str | None:
        with self._lock:
            return self._model_version

    @property
    def feature_set_version(self) -> str:
        return FEATURE_SET_VERSION

    @property
    def load_error(self) -> str | None:
        with self._lock:
            return self._load_error

    def load(self) -> None:
        """Attempt initialization once, keeping failure diagnostics value-free."""
        with self._lock:
            if self._load_attempted:
                return
            self._load_attempted = True
            try:
                model, version = _load_verified_model(self._model_dir)
            except Exception:
                # Do not retain exception strings, paths, model contents or tokens.
                self._load_error = _UNAVAILABLE_DETAIL
                return
            self._model = model
            self._model_version = version
            self._load_error = None
            self._ready = True

    def predict(self, payload: Any) -> dict:
        request = validate_request(payload)
        with self._lock:
            if not self._ready:
                raise ModelUnavailableError()
            import numpy as np

            vector = np.asarray([[float(request["features"][name]) for name in FEATURE_NAMES]], dtype=float)
            # Unexpected prediction errors propagate to the API's generic 500
            # handler. They are distinct from an unavailable startup model.
            score = float(self._model.predict(vector, num_threads=1)[0])
            if not math.isfinite(score) or not 0 <= score <= 1:
                raise RuntimeError("The risk model returned an invalid score.")
            version = self._model_version
        return {
            "hotelId": request["hotelId"],
            "targetCheckInDate": request["targetCheckInDate"],
            "asOf": request["asOf"],
            "riskScore": score,
            "modelVersion": version,
            "featureSetVersion": self.feature_set_version,
        }
