import logging
from datetime import date

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, ConfigDict, Field, model_validator

from .location_agent import HotelLocationError
from .schemas import AlternativeSearchResponse
from .scoring_service import AlternativeSearchService

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
    check_in_date: date | None = Field(None, alias="checkInDate")
    check_out_date: date | None = Field(None, alias="checkOutDate")

    @model_validator(mode="after")
    def validate_date_pair(self) -> "AutoSearchRequest":
        if (self.check_in_date is None) != (self.check_out_date is None):
            raise ValueError("체크인 날짜와 체크아웃 날짜를 함께 입력해야 합니다.")
        if (
                self.check_in_date is not None
                and self.check_out_date is not None
                and self.check_out_date <= self.check_in_date
        ):
            raise ValueError("체크아웃 날짜는 체크인 날짜 이후여야 합니다.")
        return self


@router.post("/auto-search", response_model=AlternativeSearchResponse)
async def trigger_auto_search(
        payload: AutoSearchRequest,
) -> AlternativeSearchResponse:
    try:
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
        )
    except HotelLocationError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        logger.exception("대체 숙소 자동 탐색 실패")
        raise HTTPException(
            status_code=502,
            detail="대체 숙소 탐색 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
        ) from exc
