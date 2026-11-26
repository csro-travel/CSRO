import logging
import os
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from secrets import compare_digest
from typing import Annotated
from uuid import uuid4

from fastapi import Depends, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from .assist import AssistRequest, AssistResponse, generate
from .extractor import ExtractionUnavailable, extract_claims
from .models import ExtractionRequest, ExtractionResponse, ProblemDetails
from .rankings import RankingInputError, RankingRequest, RankingResponse, RankingUnavailable, rank_quotes


logger = logging.getLogger(__name__)
bearer = HTTPBearer(auto_error=False)
problem_titles = {
    400: "Bad Request",
    401: "Unauthorized",
    422: "Unprocessable Content",
    500: "Internal Server Error",
    503: "Service Unavailable",
}


class ApiProblem(Exception):
    def __init__(self, status: int, code: str, detail: str) -> None:
        super().__init__(code)
        self.status = status
        self.code = code
        self.detail = detail


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    token = os.environ.get("AGENT_SERVICE_TOKEN", "").strip()
    if not token or any(character.isspace() for character in token):
        raise RuntimeError("AGENT_SERVICE_TOKEN에 공백 없는 내부 서비스 토큰을 설정하세요.")
    app.state.service_token = token.encode("utf-8")
    yield


app = FastAPI(title="여행봄 호텔 회신 분석 Agent", lifespan=lifespan)


def problem_response(status: int, code: str, detail: str) -> JSONResponse:
    problem = ProblemDetails(
        type="about:blank",
        title=problem_titles[status],
        status=status,
        detail=detail,
        code=code,
        traceId=f"req-{uuid4().hex}",
    )
    if status == 500:
        logger.error("호텔 회신 분석 중 내부 오류가 발생했습니다. traceId=%s", problem.traceId)
    return JSONResponse(
        status_code=status,
        content=problem.model_dump(),
        media_type="application/problem+json",
        headers={"WWW-Authenticate": "Bearer"} if status == 401 else None,
    )


@app.exception_handler(ApiProblem)
async def handle_api_problem(_request: Request, error: ApiProblem) -> JSONResponse:
    return problem_response(error.status, error.code, error.detail)


@app.exception_handler(RequestValidationError)
async def handle_invalid_request(_request: Request, error: RequestValidationError) -> JSONResponse:
    if _request.url.path == "/internal/v1/alternative-rankings":
        return problem_response(400, "INVALID_RANKING_REQUEST", "요청 JSON의 필드 또는 형식이 올바르지 않습니다.")
    if _request.url.path == "/internal/v1/counselor-assist":
        return problem_response(400, "INVALID_ASSIST_REQUEST", "요청 JSON의 필드 또는 형식이 올바르지 않습니다.")
    errors = error.errors()
    context_errors = {"empty_stay_dates", "invalid_stay_context"}
    if any(item["type"] not in context_errors for item in errors):
        return problem_response(400, "INVALID_EXTRACTION_REQUEST", "요청 JSON의 필드 또는 형식이 올바르지 않습니다.")
    if any(item["type"] == "empty_stay_dates" for item in errors):
        return problem_response(422, "EMPTY_STAY_DATES", "숙박 날짜 목록이 비어 있습니다.")
    return problem_response(422, "INVALID_STAY_CONTEXT", "숙박일 중복 또는 호텔 시간대를 확인하세요.")


def require_service_token(
    request: Request,
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)],
) -> None:
    if credentials is None or not compare_digest(
        request.app.state.service_token, credentials.credentials.encode("utf-8")
    ):
        raise ApiProblem(401, "UNAUTHENTICATED_SERVICE", "내부 서비스 인증에 실패했습니다.")


def validate_extraction_result(request: ExtractionRequest, result: ExtractionResponse) -> None:
    covered_dates = [claim.stayDate for claim in result.claims] + result.unresolvedStayDates
    if len(covered_dates) != len(request.stayDates) or set(covered_dates) != set(request.stayDates):
        raise ValueError("추출 결과의 숙박일이 요청과 일치하지 않습니다.")
    if result.evidenceId != request.evidenceId or bool(result.claims) != (result.analysisStatus == "EXTRACTED"):
        raise ValueError("추출 결과의 식별자 또는 분석 상태가 올바르지 않습니다.")
    if any(claim.supportingText not in request.originalMessage for claim in result.claims):
        raise ValueError("원문에 없는 인용이 포함되었습니다.")


@app.post(
    "/internal/v1/hotel-reply-extractions",
    dependencies=[Depends(require_service_token)],
    responses={
        status: {
            "description": title,
            "content": {"application/problem+json": {"schema": ProblemDetails.model_json_schema()}},
        }
        for status, title in problem_titles.items()
    },
)
def analyze_reply(request: ExtractionRequest) -> ExtractionResponse:
    try:
        result = extract_claims(request)
        validate_extraction_result(request, result)
        return result
    except ExtractionUnavailable:
        raise ApiProblem(503, "MODEL_UNAVAILABLE", "호텔 회신 분석을 일시적으로 사용할 수 없습니다.") from None
    except Exception:
        raise ApiProblem(500, "INTERNAL_ERROR", "호텔 회신 분석 중 내부 오류가 발생했습니다.") from None


@app.post(
    "/internal/v1/alternative-rankings",
    dependencies=[Depends(require_service_token)],
    response_model=RankingResponse,
    responses={
        status: {
            "description": title,
            "content": {"application/problem+json": {"schema": ProblemDetails.model_json_schema()}},
        }
        for status, title in problem_titles.items()
    },
)
def compare_alternatives(request: RankingRequest) -> RankingResponse:
    try:
        return rank_quotes(request)
    except RankingInputError as error:
        raise ApiProblem(error.status, error.code, error.detail) from None
    except RankingUnavailable:
        raise ApiProblem(503, "AGENT_UNAVAILABLE", "견적 비교를 일시적으로 사용할 수 없습니다.") from None
    except Exception:
        raise ApiProblem(500, "INTERNAL_ERROR", "견적 비교 중 내부 오류가 발생했습니다.") from None


@app.post(
    "/internal/v1/counselor-assist",
    dependencies=[Depends(require_service_token)],
    response_model=AssistResponse,
    responses={
        status: {
            "description": title,
            "content": {"application/problem+json": {"schema": ProblemDetails.model_json_schema()}},
        }
        for status, title in problem_titles.items()
    },
)
def counselor_assist(request: AssistRequest) -> AssistResponse:
    try:
        return generate(request)
    except Exception:
        raise ApiProblem(500, "INTERNAL_ERROR", "상담사 안내 초안 생성 중 내부 오류가 발생했습니다.") from None
