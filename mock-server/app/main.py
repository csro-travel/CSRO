"""API-026/027/028 fixtures, plus explicitly enabled supplier-owned booking state."""
from __future__ import annotations

import hmac
from http import HTTPStatus
import os
import math
import re
import time
from typing import Annotated
import uuid

from fastapi import Depends, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.openapi.utils import get_openapi
from fastapi.responses import JSONResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from starlette.exceptions import HTTPException as StarletteHTTPException

from .fixtures import STAY_DATES, hotel_response, lookup, supplier_response
from .models import HotelPending, HotelReply, Problem, ReconfirmationRequest, SupplierBooking
from .availability import AvailabilityResponse, AvailabilitySearch, InvalidSearch, search_quotes
from .booking_models import BookingConfirmed, BookingError, BookingLookup, BookingRequest

DEVELOPMENT_TOKEN = "csro-local-mock-token"  # Public local fixture credential, not a production secret.
bearer = HTTPBearer(auto_error=False, scheme_name="InternalServiceBearer")


class ContractError(Exception):
    def __init__(self, status: int, code: str, detail: str):
        self.status, self.code, self.detail = status, code, detail


def create_app(service_token: str | None = None, *, availability_enabled: bool = True,
               booking_store=None, booking_response_delay_seconds: float | None = None) -> FastAPI:
    token = service_token if service_token is not None else os.environ.get("MOCK_SERVICE_TOKEN", DEVELOPMENT_TOKEN)
    if not token or not token.strip():
        raise ValueError("MOCK_SERVICE_TOKEN must not be empty")
    if booking_store is None and os.environ.get("MOCK_STATEFUL_ENABLED", "false") == "true":
        from .stateful import store_from_environment
        booking_store = store_from_environment()
    try:
        delay = float(os.environ.get("MOCK_BOOKING_RESPONSE_DELAY_SECONDS", "0")) if booking_response_delay_seconds is None else float(booking_response_delay_seconds)
        if not math.isfinite(delay) or not 0 <= delay <= 10 or (delay and booking_store is None):
            raise ValueError()
    except (TypeError, ValueError):
        raise ValueError("Mock response delay must be 0..10 seconds in explicitly enabled stateful mode") from None
    app = FastAPI(title="여행봄 Mock — API-026~030", version="0.1.0",
                  description="고정 가상 시각의 개발용 JSON fixture. 공급사 계약과 호텔의 숙박일별 주장을 분리합니다.")

    def problem(request: Request, status: int, code: str, detail: str) -> JSONResponse:
        trace_id = getattr(request.state, "trace_id", "req-" + uuid.uuid4().hex)
        headers = {"X-Request-ID": trace_id}
        if status == 401:
            headers["WWW-Authenticate"] = "Bearer"
        body = Problem(title=HTTPStatus(status).phrase, status=status, detail=detail, code=code,
                       traceId=trace_id)
        return JSONResponse(status_code=status, content=body.model_dump(), headers=headers,
                            media_type="application/problem+json")

    @app.middleware("http")
    async def trace_request(request: Request, call_next):
        request.state.trace_id = "req-" + uuid.uuid4().hex
        if booking_store is not None and (request.client is None or request.client.host not in {"127.0.0.1", "::1"}
                or request.url.hostname not in {"127.0.0.1", "localhost", "[::1]", "::1"}):
            return problem(request, 403, "LOCAL_MOCK_ONLY", "상태 저장 Mock은 로컬 loopback 시연에서만 사용할 수 있습니다.")
        response = await call_next(request)
        response.headers["X-Request-ID"] = request.state.trace_id
        return response

    @app.exception_handler(ContractError)
    async def contract_error(request: Request, exc: ContractError):
        return problem(request, exc.status, exc.code, exc.detail)

    @app.exception_handler(BookingError)
    async def booking_error(request: Request, exc: BookingError):
        return problem(request, exc.status, exc.code, exc.detail)

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request: Request, exc: RequestValidationError):
        if request.url.path == "/mock-supplier/v1/availability-searches":
            return problem(request, 400, "INVALID_SEARCH_REQUEST", "검색 JSON의 필수 필드·타입·날짜·필터를 확인하세요.")
        if request.url.path == "/mock-supplier/v1/replacement-bookings":
            return problem(request, 400, "INVALID_BOOKING_REQUEST", "예약 JSON의 필수 필드·타입·금액을 확인하세요.")
        return problem(request, 400, "INVALID_REQUEST", "요청 JSON의 필수 필드·타입·날짜 배열을 확인하세요.")

    @app.exception_handler(StarletteHTTPException)
    async def http_error(request: Request, exc: StarletteHTTPException):
        return problem(request, exc.status_code, "RESOURCE_NOT_FOUND" if exc.status_code == 404 else "HTTP_ERROR",
                       str(exc.detail))

    @app.exception_handler(Exception)
    async def unexpected(request: Request, exc: Exception):
        return problem(request, 500, "INTERNAL_ERROR", "모의 서비스에서 예기치 못한 오류가 발생했습니다.")

    def authorize(credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)]):
        if credentials is None or not hmac.compare_digest(credentials.credentials.encode(), token.encode()):
            raise ContractError(401, "UNAUTHENTICATED_SERVICE", "내부 서비스 인증이 필요합니다.")

    error_docs = {code: {"description": HTTPStatus(code).phrase,
                         "content": {"application/problem+json": {"schema": Problem.model_json_schema()}}}
                  for code in (400, 401, 404, 409, 422, 503, 500)}

    @app.get("/healthz", tags=["개발 확인"])
    def health():
        return {"status": "ok", "service": "travel-mock", "contractVersion": "0.1", "isSynthetic": True}

    @app.get("/mock-supplier/v1/bookings/{supplierBookingId}",
             response_model=SupplierBooking, dependencies=[Depends(authorize)],
             responses={code: error_docs[code] for code in (401, 404, 503, 500)},
             tags=["API-026"], operation_id="getSupplierBooking")
    def get_booking(supplierBookingId: str):
        fixture = lookup(supplierBookingId)
        if fixture is None:
            raise ContractError(404, "SUPPLIER_BOOKING_NOT_FOUND", "공급사에서 해당 예약을 찾을 수 없습니다.")
        if fixture["mode"] == "UNAVAILABLE_SERVICE":
            raise ContractError(503, "SUPPLIER_UNAVAILABLE", "모의 공급사 서비스를 사용할 수 없습니다.")
        return supplier_response(supplierBookingId, fixture)

    @app.post("/mock-hotel/v1/reconfirmations", response_model=HotelReply,
              responses={**error_docs, 202: {"model": HotelPending}},
              dependencies=[Depends(authorize)], tags=["API-027"], operation_id="reconfirmHotelNights")
    def reconfirm(body: ReconfirmationRequest):
        fixture = lookup(body.supplierBookingId)
        if fixture is None:
            raise ContractError(404, "HOTEL_RESERVATION_NOT_FOUND", "해당 호텔에서 예약을 확인할 수 없습니다.")
        if body.propertyId != fixture["property"]:
            raise ContractError(422, "BOOKING_PROPERTY_MISMATCH", "공급사 예약과 원래 호텔이 일치하지 않습니다.")
        if {d.isoformat() for d in body.stayDates} != set(STAY_DATES):
            raise ContractError(422, "STAY_DATES_MISMATCH", "원예약의 모든 숙박일을 요청해야 하며 체크아웃 날짜는 제외합니다.")
        if fixture["mode"] == "HOTEL_NOT_FOUND":
            raise ContractError(404, "HOTEL_RESERVATION_NOT_FOUND", "해당 호텔에서 예약을 확인할 수 없습니다.")
        if fixture["mode"] == "UNAVAILABLE_SERVICE":
            raise ContractError(503, "HOTEL_UNAVAILABLE", "모의 호텔 재확인 서비스를 사용할 수 없습니다.")
        result = hotel_response(body.reconfirmationRequestId, body.supplierBookingId, fixture)
        if fixture["mode"] == "PENDING":
            return JSONResponse(status_code=202, content=HotelPending.model_validate(result).model_dump())
        return result

    @app.post("/mock-supplier/v1/availability-searches", response_model=AvailabilityResponse,
              responses={code: error_docs[code] for code in (400, 401, 422, 503, 500)},
              dependencies=[Depends(authorize)], tags=["API-028"], operation_id="searchAvailability")
    def availability_search(body: AvailabilitySearch):
        if not availability_enabled:
            raise ContractError(503, "SUPPLIER_UNAVAILABLE", "모의 공급사 검색 기능을 사용할 수 없습니다.")
        try:
            return booking_store.search(body) if booking_store is not None else search_quotes(body)
        except InvalidSearch as error:
            raise ContractError(422, error.code, error.detail) from error

    @app.post("/mock-supplier/v1/replacement-bookings", status_code=201,response_model=BookingConfirmed,
              responses={code:error_docs[code] for code in (400,401,404,409,422,503,500)},
              dependencies=[Depends(authorize)], tags=["API-029"], operation_id="createReplacementBooking")
    def create_replacement(body: BookingRequest, request: Request):
        key = request.headers.get("Idempotency-Key", "")
        if not re.fullmatch(r"[A-Za-z0-9._:-]{1,100}", key):
            raise ContractError(400, "INVALID_BOOKING_REQUEST", "유효한 Idempotency-Key가 필요합니다.")
        if booking_store is None:
            raise ContractError(503, "SUPPLIER_UNAVAILABLE", "상태 저장 공급사 모드가 명시적으로 준비되지 않았습니다.")
        outcome = booking_store.book(key, body.model_dump())
        # PostgreSQL has committed and closed its connection before a simulated
        # network delay. A retry replays the durable result without this delay.
        if outcome.status == 201 and not outcome.replayed and delay:
            time.sleep(delay)
        if outcome.status >= 400 and "traceId" in outcome.body:
            request.state.trace_id = outcome.body["traceId"]
        return JSONResponse(status_code=outcome.status, content=outcome.body,
                            media_type="application/problem+json" if outcome.status >= 400 else "application/json")

    @app.get("/mock-supplier/v1/replacement-booking-requests/{requestId}",response_model=BookingLookup,
             response_model_exclude_unset=True,responses={code:error_docs[code] for code in (401,404,503,500)},
             dependencies=[Depends(authorize)], tags=["API-030"], operation_id="getReplacementBookingRequest")
    def replacement_result(requestId: str):
        if booking_store is None:
            raise ContractError(503, "SUPPLIER_UNAVAILABLE", "상태 저장 공급사 모드가 명시적으로 준비되지 않았습니다.")
        if not re.fullmatch(r"[A-Za-z0-9._:-]{1,100}", requestId):
            raise ContractError(404, "BOOKING_REQUEST_NOT_FOUND", "해당 요청 식별자의 처리 이력을 찾을 수 없습니다.")
        return booking_store.result(requestId)

    def contract_openapi():
        if app.openapi_schema is None:
            schema = get_openapi(title=app.title, version=app.version, description=app.description, routes=app.routes)
            # FastAPI adds its default JSON-422 for any path parameter. API-026
            # has an unconstrained string ID; unknown IDs use its documented 404.
            schema["paths"]["/mock-supplier/v1/bookings/{supplierBookingId}"]["get"]["responses"].pop("422", None)
            schema["paths"]["/mock-supplier/v1/replacement-booking-requests/{requestId}"]["get"]["responses"].pop("422", None)
            app.openapi_schema = schema
        return app.openapi_schema

    app.openapi = contract_openapi
    return app


app = create_app()
