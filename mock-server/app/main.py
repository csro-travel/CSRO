"""API-026/027 local development mock; JSON responses, zero database access."""
from __future__ import annotations

import hmac
from http import HTTPStatus
import os
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

DEVELOPMENT_TOKEN = "csro-local-mock-token"  # Public local fixture credential, not a production secret.
bearer = HTTPBearer(auto_error=False, scheme_name="InternalServiceBearer")


class ContractError(Exception):
    def __init__(self, status: int, code: str, detail: str):
        self.status, self.code, self.detail = status, code, detail


def create_app(service_token: str | None = None) -> FastAPI:
    token = service_token if service_token is not None else os.environ.get("MOCK_SERVICE_TOKEN", DEVELOPMENT_TOKEN)
    if not token or not token.strip():
        raise ValueError("MOCK_SERVICE_TOKEN must not be empty")
    app = FastAPI(title="여행봄 Mock — API-026 / API-027", version="0.1.0",
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
        response = await call_next(request)
        response.headers["X-Request-ID"] = request.state.trace_id
        return response

    @app.exception_handler(ContractError)
    async def contract_error(request: Request, exc: ContractError):
        return problem(request, exc.status, exc.code, exc.detail)

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request: Request, exc: RequestValidationError):
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
                  for code in (400, 401, 404, 422, 503, 500)}

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

    def contract_openapi():
        if app.openapi_schema is None:
            schema = get_openapi(title=app.title, version=app.version, description=app.description, routes=app.routes)
            # FastAPI adds its default JSON-422 for any path parameter. API-026
            # has an unconstrained string ID; unknown IDs use its documented 404.
            schema["paths"]["/mock-supplier/v1/bookings/{supplierBookingId}"]["get"]["responses"].pop("422", None)
            app.openapi_schema = schema
        return app.openapi_schema

    app.openapi = contract_openapi
    return app


app = create_app()
