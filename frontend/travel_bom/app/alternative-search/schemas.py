from datetime import date, datetime
from enum import Enum

from pydantic import BaseModel, ConfigDict, Field


class VerificationStatus(str, Enum):
    CONFIRMED = "confirmed"
    UNCONFIRMED = "unconfirmed"
    FAILED = "failed"


class HotelOrigin(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    hotel_name: str
    address: str
    latitude: float
    longitude: float
    location_source: str
    verified_at: datetime


class SearchPolicy(BaseModel):
    check_in_date: date
    check_out_date: date
    nights: int = Field(..., ge=1)
    adults: int = Field(..., ge=1)
    original_total_amount: int = Field(..., ge=0)
    preferred_max_amount: int = Field(..., ge=0)
    primary_radius_km: float = Field(default=10.0, gt=0)
    fallback_radius_km: float = Field(default=20.0, gt=0)
    used_future_fallback_dates: bool = False
    search_note: str | None = None


class ScoreBreakdown(BaseModel):
    location_and_eta: int = Field(..., ge=0, le=30)
    room_and_capacity: int = Field(..., ge=0, le=25)
    accessibility_and_amenities: int = Field(..., ge=0, le=20)
    price: int = Field(..., ge=0, le=15)
    cancellation_flexibility: int = Field(..., ge=0, le=10)

    @property
    def total(self) -> int:
        return (
                self.location_and_eta
                + self.room_and_capacity
                + self.accessibility_and_amenities
                + self.price
                + self.cancellation_flexibility
        )


class CandidateChecks(BaseModel):
    full_stay_available: VerificationStatus = VerificationStatus.UNCONFIRMED
    guest_capacity_ok: VerificationStatus = VerificationStatus.UNCONFIRMED
    address_and_listing_verified: VerificationStatus = VerificationStatus.UNCONFIRMED
    room_and_checkin_verified: VerificationStatus = VerificationStatus.UNCONFIRMED
    accessibility_and_amenities_ok: VerificationStatus = VerificationStatus.UNCONFIRMED
    cancellation_policy_verified: VerificationStatus = VerificationStatus.UNCONFIRMED


class AlternativeCandidate(BaseModel):
    listing_id: str
    name: str
    address: str | None = None
    listing_url: str | None = None
    source: str
    checked_at: datetime
    distance_km: float = Field(..., ge=0)
    travel_minutes: int | None = Field(None, ge=0)
    total_price_krw: int | None = Field(None, ge=0)
    price_diff_krw: int | None = None
    cancellation_summary: str = "취소 규정 미확인"
    checks: CandidateChecks
    review_evidence: dict[str, str | None] = Field(default_factory=dict)
    ai_review_completed: bool | None = None
    amenities: list[str] = Field(default_factory=list)
    image_urls: list[str] = Field(default_factory=list)
    korean_name: str | None = None
    original_name: str | None = None
    check_in_date: date | None = None
    check_out_date: date | None = None
    search_scope: str = "full_stay"
    unconfirmed_items: list[str] = Field(default_factory=list)
    approval_required_reasons: list[str] = Field(default_factory=list)
    score_breakdown: ScoreBreakdown | None = None
    selection_reason: str = ""

    @property
    def total_score(self) -> int:
        return self.score_breakdown.total if self.score_breakdown else 0


class RankingCandidateInput(BaseModel):
    source: str
    listing_id: str
    total_price_krw: int | None
    check_in_date: date | None
    check_out_date: date | None
    search_scope: str
    checks: CandidateChecks
    total_score: int
    distance_km: float


class AlternativeSearchResponse(BaseModel):
    case_id: str
    booking_id: str
    origin: HotelOrigin
    policy: SearchPolicy
    searched_radius_km: float
    searched_candidate_count: int = Field(..., ge=0)
    verified_candidate_count: int = Field(..., ge=0)
    top_recommendations: list[AlternativeCandidate] = Field(default_factory=list)
    exception_candidates: list[AlternativeCandidate] = Field(default_factory=list)
    ranking_version: str | None = None
    analyzed_at: datetime | None = None
    ranking_input: list[RankingCandidateInput] = Field(default_factory=list)
    summary: str = ""
    disclaimer: str = (
        "검색 결과는 조회 시점에 확인된 후보이며, 예약 가능 여부와 가격은 "
        "예약 직전에 다시 확인해야 합니다."
    )
