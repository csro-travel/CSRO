"""API-023: authenticated, read-only HTTP inference for risk-features-0.2.

Run from ``ml/`` with ``uvicorn travel_ml.risk_api:app --port 8002``.
The model is loaded once at startup; this module never generates training data,
queries the business database, or changes booking/reconfirmation state.
"""

from __future__ import annotations

from contextlib import asynccontextmanager
import hmac
from http import HTTPStatus
import json
import logging
import os
from pathlib import Path
from typing import Annotated, Any
from uuid import uuid4

from fastapi import FastAPI, Request, Security
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from starlette.concurrency import run_in_threadpool
from starlette.exceptions import HTTPException

from .common import ROOT
from .risk_runtime import (
    ModelUnavailableError,
    RiskModelRuntime,
    RiskRequestError,
    validate_request,
)


_LOGGER = logging.getLogger(__name__)


_REQUEST_EXAMPLE = {
    "hotelId": "HOTEL-001",
    "targetCheckInDate": "2025-11-01",
    "asOf": "2025-10-31T09:00:00+09:00",
    "featureSetVersion": "risk-features-0.2",
    "features": {
        "pastFailureRate90d": 0.05,
        "targetConfirmedBookingCount": 12,
        "bookingVolumeGrowth7d": 0.4,
        "reconfirmationOverdueRate7d": 0.2,
        "inventoryUnavailableRate7d": 0.1,
        "isPeakDay": False,
    },
}

_FEATURE_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": list(_REQUEST_EXAMPLE["features"]),
    "properties": {
        "pastFailureRate90d": {"type": "number", "minimum": 0, "maximum": 1},
        "targetConfirmedBookingCount": {"type": "integer", "minimum": 0},
        "bookingVolumeGrowth7d": {"type": "number", "minimum": -1, "maximum": 10},
        "reconfirmationOverdueRate7d": {"type": "number", "minimum": 0, "maximum": 1},
        "inventoryUnavailableRate7d": {"type": "number", "minimum": 0, "maximum": 1},
        "isPeakDay": {"type": "boolean"},
    },
}

_REQUEST_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": list(_REQUEST_EXAMPLE),
    "properties": {
        "hotelId": {"type": "string", "minLength": 1},
        "targetCheckInDate": {"type": "string", "format": "date"},
        "asOf": {
            "type": "string",
            "format": "date-time",
            "description": "D-1 09:00 Asia/Seoul, with a UTC offset; returned verbatim.",
        },
        "featureSetVersion": {"type": "string", "enum": ["risk-features-0.2"]},
        "features": _FEATURE_SCHEMA,
    },
}

_RESPONSE_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": [
        "hotelId", "targetCheckInDate", "asOf", "riskScore", "modelVersion", "featureSetVersion"
    ],
    "properties": {
        "hotelId": {"type": "string"},
        "targetCheckInDate": {"type": "string", "format": "date"},
        "asOf": {"type": "string", "format": "date-time"},
        "riskScore": {"type": "number", "minimum": 0, "maximum": 1},
        "modelVersion": {"type": "string"},
        "featureSetVersion": {"type": "string", "enum": ["risk-features-0.2"]},
    },
}

_PROBLEM_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": ["type", "title", "status", "detail", "code", "traceId"],
    "properties": {
        "type": {"type": "string", "example": "about:blank"},
        "title": {"type": "string"},
        "status": {"type": "integer"},
        "detail": {"type": "string"},
        "code": {"type": "string"},
        "traceId": {"type": "string", "format": "uuid"},
    },
}

_ERROR_DESCRIPTIONS = {
    400: "INVALID_RISK_REQUEST: invalid JSON, content type, or request structure/type.",
    401: "UNAUTHENTICATED_SERVICE: a configured service Bearer token is required.",
    422: "FEATURE_SCHEMA_UNSUPPORTED or INVALID_FEATURE_VALUES: incompatible features or D-1 values.",
    503: "MODEL_UNAVAILABLE: no verified inference model is ready.",
    500: "INTERNAL_ERROR: unexpected inference failure; internal details are omitted.",
}

_BEARER = HTTPBearer(
    auto_error=False,
    scheme_name="MLServiceBearer",
    description="Service token configured through ML_SERVICE_TOKEN; no default token exists.",
)


def _problem(request: Request, status: int, code: str, detail: str,
             headers: dict[str, str] | None = None) -> JSONResponse:
    trace_id = getattr(request.state, "trace_id", None) or str(uuid4())
    response_headers = {"X-Request-ID": trace_id, "Cache-Control": "no-store"}
    if headers:
        response_headers.update(headers)
    if status == 401:
        response_headers["WWW-Authenticate"] = "Bearer"
    return JSONResponse(
        status_code=status,
        content={
            "type": "about:blank",
            "title": "Unprocessable Content" if status == 422 else HTTPStatus(status).phrase,
            "status": status,
            "detail": detail,
            "code": code,
            "traceId": trace_id,
        },
        media_type="application/problem+json",
        headers=response_headers,
    )


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON key")
        result[key] = value
    return result


def _invalid_constant(_: str) -> None:
    raise ValueError("Non-finite JSON number")


async def _read_json(request: Request) -> Any:
    media_type = request.headers.get("content-type", "").split(";", 1)[0].strip().lower()
    if media_type != "application/json":
        raise RiskRequestError(400, "INVALID_RISK_REQUEST", "Content-Type must be application/json.")
    try:
        payload = json.loads(
            (await request.body()).decode("utf-8"),
            object_pairs_hook=_unique_object,
            parse_constant=_invalid_constant,
        )
        # Escaped lone surrogates are accepted by Python's decoder but cannot be
        # safely returned as UTF-8 JSON. Treat them as invalid input as well.
        json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8")
        return payload
    except (ValueError, UnicodeError, RecursionError):
        raise RiskRequestError(400, "INVALID_RISK_REQUEST", "The request body must be valid JSON.") from None


def _resolve_model_dir(model_dir: str | Path | None) -> Path:
    configured = model_dir if model_dir is not None else os.environ.get("ML_MODEL_DIR")
    path = Path(configured) if configured else Path("artifacts/reference_model_v02")
    return path if path.is_absolute() else ROOT / path


def create_app(model_dir: str | Path | None = None, service_token: str | None = None,
               runtime: RiskModelRuntime | None = None) -> FastAPI:
    """Create an API-023 app without loading a model during module import.

    Explicit factory values override environment variables. A missing/blank
    token leaves liveness available, but denies inference and readiness.
    """
    configured_token = service_token if service_token is not None else os.environ.get("ML_SERVICE_TOKEN")
    token_bytes = configured_token.encode("utf-8") if configured_token and configured_token.strip() else None
    model_runtime = runtime if runtime is not None else RiskModelRuntime(_resolve_model_dir(model_dir))

    def initialize_model() -> bool:
        model_runtime.load()
        return bool(model_runtime.ready)

    @asynccontextmanager
    async def lifespan(application: FastAPI):
        # RiskModelRuntime records sanitized load failures and stays unready.
        # The guard also keeps liveness working if a supplied runtime fails.
        try:
            application.state.model_ready = await run_in_threadpool(initialize_model)
        except Exception:
            application.state.model_ready = False
        yield

    application = FastAPI(
        title="CSRO API-023 Risk Assessment",
        version="0.2.0",
        description=(
            "Read-only inference with the saved LightGBM reference model and risk-features-0.2. "
            "Spring supplies the six as-of feature values and owns all business state changes. "
            "The documented 2025 example is synthetic, not an aggregate of the 2026 YB booking database."
        ),
        lifespan=lifespan,
    )
    application.state.runtime = model_runtime
    application.state.model_ready = False

    def model_ready() -> bool:
        # Readiness is fixed by the single startup attempt, with no hot reload.
        # Avoid taking the runtime's prediction lock on the ASGI event loop.
        return bool(application.state.model_ready)

    @application.middleware("http")
    async def trace_and_sanitize(request: Request, call_next):
        request.state.trace_id = str(uuid4())
        try:
            response = await call_next(request)
        except Exception as exc:
            # Never include request data, tokens, exception text, or local paths.
            _LOGGER.error("API-023 request failed traceId=%s exceptionType=%s",
                          request.state.trace_id, type(exc).__name__)
            response = _problem(request, 500, "INTERNAL_ERROR", "An internal error occurred.")
        response.headers["X-Request-ID"] = request.state.trace_id
        response.headers["Cache-Control"] = "no-store"
        return response

    @application.exception_handler(RiskRequestError)
    async def invalid_request(request: Request, exc: RiskRequestError):
        return _problem(request, exc.status_code, exc.code, exc.detail)

    @application.exception_handler(ModelUnavailableError)
    async def unavailable(request: Request, _: ModelUnavailableError):
        return _problem(request, 503, "MODEL_UNAVAILABLE", "The risk model is unavailable.")

    @application.exception_handler(RequestValidationError)
    async def invalid_framework_request(request: Request, _: RequestValidationError):
        return _problem(request, 400, "INVALID_RISK_REQUEST", "The request is invalid.")

    @application.exception_handler(HTTPException)
    async def http_error(request: Request, exc: HTTPException):
        codes = {404: "NOT_FOUND", 405: "METHOD_NOT_ALLOWED"}
        code = codes.get(exc.status_code, "HTTP_ERROR")
        detail = "The requested endpoint was not found." if exc.status_code == 404 else HTTPStatus(exc.status_code).phrase
        return _problem(request, exc.status_code, code, detail, exc.headers)

    @application.get("/healthz", tags=["Health"], summary="Check process liveness")
    async def healthz():
        return {"status": "alive", "modelReady": model_ready()}

    @application.get("/readyz", tags=["Health"], summary="Check model and authentication readiness",
                     responses={503: {"description": "Model or service authentication is not ready."}})
    async def readyz():
        ready_model = model_ready()
        ready = ready_model and token_bytes is not None
        return JSONResponse(
            status_code=200 if ready else 503,
            content={"status": "ready" if ready else "not_ready", "modelReady": ready_model,
                     "authConfigured": token_bytes is not None},
        )

    @application.post(
        "/internal/v1/risk-assessments",
        tags=["API-023"],
        summary="API-023: assess one hotel and check-in date",
        description=(
            "Authenticate before parsing the body. The six supplied features must use risk-features-0.2 "
            "and D-1 09:00 Asia/Seoul. Returns the actual saved-model score with the input asOf unchanged. "
            "A score is a risk signal, not proof that an original room cannot be provided."
        ),
        responses={
            200: {"description": "Saved-model inference result, without an envelope.",
                  "content": {"application/json": {"schema": _RESPONSE_SCHEMA}}},
            **{status: {"description": description,
                        "content": {"application/problem+json": {"schema": _PROBLEM_SCHEMA}}}
               for status, description in _ERROR_DESCRIPTIONS.items()},
        },
        openapi_extra={"requestBody": {"required": True, "content": {"application/json": {
            "schema": _REQUEST_SCHEMA, "example": _REQUEST_EXAMPLE,
        }}}},
    )
    async def assess_risk(
        request: Request,
        credentials: Annotated[HTTPAuthorizationCredentials | None, Security(_BEARER)],
    ):
        supplied_token = credentials.credentials.encode("utf-8") if credentials is not None else None
        if token_bytes is None or supplied_token is None or not hmac.compare_digest(token_bytes, supplied_token):
            return _problem(request, 401, "UNAUTHENTICATED_SERVICE", "Valid service authentication is required.")

        payload = validate_request(await _read_json(request))
        if not model_ready():
            raise ModelUnavailableError()
        prediction = await run_in_threadpool(model_runtime.predict, payload)
        return JSONResponse(content=prediction)

    return application


app = create_app()
