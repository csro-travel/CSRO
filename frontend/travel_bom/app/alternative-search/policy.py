from datetime import date

from .schemas import (
    AlternativeCandidate,
    CandidateChecks,
    ScoreBreakdown,
    SearchPolicy,
    VerificationStatus,
)


class SearchPolicyError(ValueError):
    """The requested booking period cannot be used without changing its meaning."""


def resolve_search_policy(
        *,
        nightly_price: int,
        nights: int,
        adults: int,
        raw_check_in: date | None,
        raw_check_out: date | None,
        today: date,
) -> SearchPolicy:
    if nightly_price < 0 or nights < 1 or adults < 1:
        raise SearchPolicyError("가격, 숙박일수 또는 투숙 인원 값이 올바르지 않습니다.")
    if raw_check_in is None or raw_check_out is None:
        raise SearchPolicyError("원예약의 체크인 날짜와 체크아웃 날짜가 필요합니다.")
    if raw_check_out <= raw_check_in:
        raise SearchPolicyError("체크아웃 날짜는 체크인 날짜 이후여야 합니다.")
    if raw_check_in < today:
        raise SearchPolicyError("이미 시작한 원예약 기간은 이 검색으로 조회할 수 없습니다. 날짜를 임의로 변경하지 않았습니다.")
    safe_nights = (raw_check_out - raw_check_in).days
    if safe_nights != nights:
        raise SearchPolicyError("숙박일수가 원예약의 체크인·체크아웃 기간과 일치하지 않습니다.")

    original_total = nightly_price * safe_nights

    return SearchPolicy(
        check_in_date=raw_check_in,
        check_out_date=raw_check_out,
        nights=safe_nights,
        adults=adults,
        original_total_amount=original_total,
        preferred_max_amount=round(original_total * 1.2),
        used_future_fallback_dates=False,
        search_note=None,
    )


def has_hard_failure(checks: CandidateChecks) -> bool:
    return any(
        status == VerificationStatus.FAILED
        for status in checks.model_dump().values()
    )


def collect_unconfirmed_items(checks: CandidateChecks) -> list[str]:
    labels = {
        "full_stay_available": "전체 숙박일 예약 가능 여부",
        "guest_capacity_ok": "투숙 인원 수용 여부",
        "address_and_listing_verified": "주소 및 숙소 실재성",
        "room_and_checkin_verified": "객실 구성 및 체크인 정보",
        "accessibility_and_amenities_ok": "접근성 및 필수 편의시설",
        "cancellation_policy_verified": "취소 조건",
    }
    return [
        label
        for field, label in labels.items()
        if getattr(checks, field) == VerificationStatus.UNCONFIRMED
    ]


def collect_approval_reasons(
        *,
        distance_km: float,
        total_price: int | None,
        preferred_max_amount: int,
        primary_radius_km: float,
        unconfirmed_items: list[str],
) -> list[str]:
    reasons: list[str] = []

    if distance_km > primary_radius_km:
        reasons.append(
            f"기준 반경 {primary_radius_km:.0f}km 초과 ({distance_km:.1f}km)"
        )
    if total_price is None:
        reasons.append("총 숙박 가격 미확인")
    elif total_price > preferred_max_amount:
        reasons.append(
            f"기준 상한 {preferred_max_amount:,}원 초과 ({total_price:,}원)"
        )
    if unconfirmed_items:
        reasons.append(f"미확인 항목: {', '.join(unconfirmed_items)}")

    return reasons


def calculate_score(
        *,
        distance_km: float,
        total_price: int | None,
        preferred_max_amount: int,
        checks: CandidateChecks,
        amenities_confirmed_ratio: float,
        flexible_cancellation: bool,
) -> ScoreBreakdown:
    if distance_km <= 2:
        location_score = 30
    elif distance_km <= 5:
        location_score = 24
    elif distance_km <= 10:
        location_score = 18
    else:
        location_score = 10

    if (
            checks.guest_capacity_ok == VerificationStatus.CONFIRMED
            and checks.room_and_checkin_verified == VerificationStatus.CONFIRMED
    ):
        room_score = 25
    elif checks.guest_capacity_ok == VerificationStatus.CONFIRMED:
        room_score = 18
    else:
        room_score = 10

    amenity_ratio = max(0.0, min(1.0, amenities_confirmed_ratio))
    amenity_score = round(amenity_ratio * 20)

    if total_price is None:
        price_score = 0
    elif preferred_max_amount <= 0:
        price_score = 15 if total_price == 0 else 0
    elif total_price <= preferred_max_amount * 0.85:
        price_score = 15
    elif total_price <= preferred_max_amount:
        price_score = 12
    elif total_price <= preferred_max_amount * 1.15:
        price_score = 7
    else:
        price_score = 3

    return ScoreBreakdown(
        location_and_eta=location_score,
        room_and_capacity=room_score,
        accessibility_and_amenities=amenity_score,
        price=price_score,
        cancellation_flexibility=10 if flexible_cancellation else 4,
    )


# def rank_candidates(
#         candidates: list[AlternativeCandidate],
#         *,
#         top_k: int = 5,
# ) -> tuple[list[AlternativeCandidate], list[AlternativeCandidate]]:
#     qualified: list[AlternativeCandidate] = []
#     exceptions: list[AlternativeCandidate] = []

#     for candidate in candidates:
#         if (
#                 has_hard_failure(candidate.checks)
#                 or candidate.unconfirmed_items
#                 or candidate.approval_required_reasons
#         ):
#             exceptions.append(candidate)
#         else:
#             qualified.append(candidate)

#     sort_key = lambda item: (
#         item.total_score,
#         item.total_price_krw is not None,
#         -(item.total_price_krw or 0),
#         -item.distance_km,
#     )
#     qualified.sort(key=sort_key, reverse=True)
#     exceptions.sort(key=sort_key, reverse=True)

#     return qualified[: max(1, min(top_k, 5))], exceptions

def rank_candidates(
    candidates: list[AlternativeCandidate],
    *,
    top_k: int = 5,
) -> tuple[list[AlternativeCandidate], list[AlternativeCandidate]]:
    recommendations: list[AlternativeCandidate] = []
    exceptions: list[AlternativeCandidate] = []

    for candidate in candidates:
        # 실제 실패로 확인된 조건이 있는 후보만 예외 목록으로 분류
        if has_hard_failure(candidate.checks):
            exceptions.append(candidate)
        else:
            # 미확인 항목이나 승인 사유가 있어도 상담사 검토용 추천 후보에 포함
            recommendations.append(candidate)

    def sort_key(candidate: AlternativeCandidate) -> tuple:
        return (
            candidate.total_score,
            candidate.total_price_krw is not None,
            -(candidate.total_price_krw or 0),
            -candidate.distance_km,
        )

    recommendations.sort(key=sort_key, reverse=True)
    exceptions.sort(key=sort_key, reverse=True)

    # 기존 정책대로 추천 영역은 상위 최대 5개까지만 반환
    return recommendations[: max(1, min(top_k, 5))], exceptions
