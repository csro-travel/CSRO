import asyncio
import logging
from datetime import date, datetime

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, ConfigDict, Field, model_validator

from .location_agent import HotelLocationError
from .schemas import AlternativeSearchResponse
from .scoring_service import AlternativeSearchService, SEOUL_TIME

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/api/v1/alternative-search",
    tags=["alternative-search"],
)

search_service = AlternativeSearchService()


class AutoSearchRequest(BaseModel):
    model_config = ConfigDict(
        populate_by_name=True,
        str_strip_whitespace=True,
    )

    case_id: str = Field(..., alias="caseId", min_length=1)
    booking_id: str = Field(..., alias="bookingId", min_length=1)
    hotel_name: str = Field(..., alias="hotelName", min_length=1)
    nightly_price: int = Field(..., alias="nightlyPrice", ge=0)
    nights: int = Field(..., ge=1)
    adults: int = Field(..., ge=1)
    region_hint: str | None = Field(None, alias="regionHint")
    check_in_date: date = Field(..., alias="checkInDate")
    check_out_date: date = Field(..., alias="checkOutDate")

    @model_validator(mode="after")
    def validate_date_pair(self) -> "AutoSearchRequest":
        if self.check_out_date <= self.check_in_date:
            raise ValueError("체크아웃 날짜는 체크인 날짜 이후여야 합니다.")
        if (self.check_out_date - self.check_in_date).days != self.nights:
            raise ValueError("원예약 기간과 숙박일수가 일치해야 합니다.")
        return self


@router.post("/auto-search", response_model=AlternativeSearchResponse)
async def trigger_auto_search(
        payload: AutoSearchRequest,
) -> AlternativeSearchResponse:
    today = datetime.now(SEOUL_TIME).date()
    if payload.check_in_date < today:
        raise HTTPException(
            status_code=400,
            detail="원예약의 체크인 날짜가 지나 숙소를 검색할 수 없습니다.",
        )

    try:
        async with asyncio.timeout(120):
            return await search_service.run_auto_search(
                case_id=payload.case_id,
                booking_id=payload.booking_id,
                hotel_name=payload.hotel_name,
                nightly_price=payload.nightly_price,
                nights=payload.nights,
                adults=payload.adults,
                region_hint=payload.region_hint,
                check_in_date=payload.check_in_date,
                check_out_date=payload.check_out_date,
                today=today,
            )
    except TimeoutError as exc:
        raise HTTPException(
            status_code=504,
            detail="숙소 검색 응답 시간이 초과되었습니다. 다시 시도해 주세요.",
        ) from exc
    except HotelLocationError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        logger.exception("대체 숙소 자동 탐색 실패")
        raise HTTPException(
            status_code=502,
            detail="대체 숙소 탐색 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
        ) from exc
