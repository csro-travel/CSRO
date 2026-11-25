"""API-025: deterministic comparison of supplied snapshots, without DB writes."""
from __future__ import annotations

import re
from datetime import date
from typing import Annotated, Literal

from pydantic import AwareDatetime, BaseModel, BeforeValidator, ConfigDict, Field, StrictInt, StrictStr, field_validator


def date_string(value: object) -> str:
    if not isinstance(value, str) or re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", value) is None:
        raise ValueError("숙박일은 YYYY-MM-DD 문자열이어야 합니다.")
    return value


DateString = Annotated[date, BeforeValidator(date_string)]
PositiveInt = Annotated[StrictInt, Field(ge=1)]
Amount = Annotated[StrictInt, Field(ge=0)]
NonemptyText = Annotated[StrictStr, Field(min_length=1)]


class NestedInput(BaseModel):
    model_config = ConfigDict(extra="forbid")


class RequestedStay(NestedInput):
    checkInDate: DateString
    checkOutDate: DateString
    adults: PositiveInt


class OriginalStayTotal(NestedInput):
    amount: Amount
    currency: NonemptyText


class RankingQuote(NestedInput):
    quoteId: NonemptyText
    hotelName: NonemptyText
    checkInDate: DateString
    checkOutDate: DateString
    adults: PositiveInt
    distanceMeters: Amount
    totalAmount: Amount
    currency: NonemptyText
    checkedAt: AwareDatetime

    @field_validator("checkedAt", mode="before")
    @classmethod
    def timestamp_string(cls, value: object) -> str:
        if not isinstance(value, str) or re.match(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T", value) is None:
            raise ValueError("조회 시각은 시간대를 포함한 ISO 8601 문자열이어야 합니다.")
        return value


class RankingRequest(BaseModel):
    # The source schema permits future top-level extensions, but closes nested objects.
    model_config = ConfigDict(extra="ignore")

    caseId: NonemptyText
    bookingId: NonemptyText
    requestedStay: RequestedStay
    originalStayTotal: OriginalStayTotal | None = None
    quotes: list[RankingQuote] = Field(min_length=1, max_length=20)

    @field_validator("caseId", "bookingId")
    @classmethod
    def nonblank_identifier(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("빈 식별자는 사용할 수 없습니다.")
        return value

    @field_validator("originalStayTotal", mode="before")
    @classmethod
    def optional_total_must_be_object(cls, value: object) -> object:
        # Optional means absent; JSON null is not an object in the source schema.
        if value is None:
            raise ValueError("비교할 수 없는 원예약 총액은 생략하세요.")
        return value


class RankingReason(BaseModel):
    quoteId: str
    reasonCodes: list[Literal["NEAREST", "LOWEST_PRICE", "ALTERNATIVE"]]
    summary: str


class RankingResponse(BaseModel):
    caseId: str
    rankingVersion: Literal["alternative-ranker-0.1"] = "alternative-ranker-0.1"
    rankedQuoteIds: list[str]
    reasons: list[RankingReason]
    warnings: list[str]


class RankingInputError(Exception):
    def __init__(self, status: int, code: str, detail: str):
        self.status, self.code, self.detail = status, code, detail


class RankingUnavailable(Exception):
    """The comparison provider is temporarily unavailable."""


def rank_quotes(request: RankingRequest) -> RankingResponse:
    quote_ids = [quote.quoteId for quote in request.quotes]
    if any(not value.strip() for value in quote_ids) or len(quote_ids) != len(set(quote_ids)):
        raise RankingInputError(400, "INVALID_RANKING_REQUEST", "견적 ID는 비어 있거나 중복될 수 없습니다.")
    stay = request.requestedStay
    if stay.checkOutDate <= stay.checkInDate or any(
        (quote.checkInDate, quote.checkOutDate, quote.adults)
        != (stay.checkInDate, stay.checkOutDate, stay.adults)
        for quote in request.quotes
    ):
        raise RankingInputError(422, "QUOTE_STAY_MISMATCH", "견적의 숙박 기간과 인원이 요청 조건과 다릅니다.")
    if any(quote.currency != "KRW" for quote in request.quotes) or (
        request.originalStayTotal is not None and request.originalStayTotal.currency != "KRW"
    ):
        raise RankingInputError(422, "CURRENCY_MISMATCH", "KRW 견적만 비교하며 통화를 자동 환산하지 않습니다.")

    # Initial, inspectable rule rather than experimentally validated recommendation weights:
    # nearest first; cheapest of the remaining second; remainder by total/distance/ID.
    nearest = min(request.quotes, key=lambda quote: (quote.distanceMeters, quote.totalAmount, quote.quoteId))
    remaining = sorted(
        (quote for quote in request.quotes if quote.quoteId != nearest.quoteId),
        key=lambda quote: (quote.totalAmount, quote.distanceMeters, quote.quoteId),
    )
    ordered = [nearest, *remaining]
    lowest_amount = min(quote.totalAmount for quote in request.quotes)
    nights = (stay.checkOutDate - stay.checkInDate).days
    reasons = []
    for index, quote in enumerate(ordered):
        if index == 0:
            code, summary = "NEAREST", "기존 호텔에서 가장 가까워 이동 거리가 짧습니다."
        elif quote.totalAmount == lowest_amount:
            code = "LOWEST_PRICE"
            summary = f"비교 후보 중 {nights}박 숙박 견적 총액이 가장 낮습니다."
        else:
            code, summary = "ALTERNATIVE", f"{nights}박 숙박 조건이 맞는 추가 후보입니다."
        reasons.append(RankingReason(quoteId=quote.quoteId, reasonCodes=[code], summary=summary))
    return RankingResponse(
        caseId=request.caseId,
        rankedQuoteIds=[quote.quoteId for quote in ordered],
        reasons=reasons,
        warnings=["숙소 예약은 확정되지 않았으며 실행 직전 가격과 재고를 다시 확인해야 합니다."],
    )
