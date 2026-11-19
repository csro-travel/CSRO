import re
from datetime import date
from typing import Annotated, Literal
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import AwareDatetime, BaseModel, BeforeValidator, Field, StrictStr, field_validator
from pydantic_core import PydanticCustomError


ProvisionStatus = Literal["AVAILABLE", "UNAVAILABLE"]
MAX_MESSAGE_LENGTH = 10_000


def require_date_string(value: object) -> str:
    if not isinstance(value, str) or re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", value) is None:
        raise ValueError("숙박일은 YYYY-MM-DD 형식의 문자열이어야 합니다.")
    return value


StayDate = Annotated[date, BeforeValidator(require_date_string)]


class ExtractionRequest(BaseModel):
    evidenceId: StrictStr
    bookingId: StrictStr
    hotelId: StrictStr
    stayDates: list[StayDate]
    originalMessage: StrictStr = Field(max_length=MAX_MESSAGE_LENGTH)
    locale: StrictStr
    messageOccurredAt: AwareDatetime | None
    hotelTimeZone: StrictStr

    @field_validator("evidenceId", "bookingId", "hotelId", "originalMessage", "locale")
    @classmethod
    def reject_blank_text(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("빈 문자열은 사용할 수 없습니다.")
        return value

    @field_validator("messageOccurredAt", mode="before")
    @classmethod
    def require_datetime_string(cls, value: object) -> str | None:
        if value is None:
            return None
        if not isinstance(value, str) or re.match(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T", value) is None:
            raise ValueError("원문 발생 시각은 시간대가 포함된 ISO 8601 문자열이어야 합니다.")
        return value

    @field_validator("stayDates")
    @classmethod
    def validate_stay_dates(cls, dates: list[date]) -> list[date]:
        if not dates:
            raise PydanticCustomError("empty_stay_dates", "숙박 날짜 목록이 비어 있습니다.")
        if len(dates) != len(set(dates)):
            raise PydanticCustomError("invalid_stay_context", "숙박 날짜가 중복되었습니다.")
        return dates

    @field_validator("hotelTimeZone")
    @classmethod
    def validate_time_zone(cls, value: str) -> str:
        try:
            ZoneInfo(value)
        except (ZoneInfoNotFoundError, ValueError):
            raise PydanticCustomError("invalid_stay_context", "호텔 시간대가 유효하지 않습니다.") from None
        return value


class Claim(BaseModel):
    stayDate: date
    reportedProvisionStatus: ProvisionStatus
    supportingText: str = Field(min_length=1)


class ExtractionResponse(BaseModel):
    evidenceId: str
    analysisStatus: Literal["EXTRACTED", "AMBIGUOUS"]
    modelVersion: str
    claims: list[Claim]
    unresolvedStayDates: list[date]
    requiresHumanVerification: Literal[True]
    warnings: list[str]


class ProblemDetails(BaseModel):
    type: Literal["about:blank"]
    title: str
    status: int
    detail: str
    code: str
    traceId: str
