from datetime import date, timedelta

from .schemas import (
    AlternativeCandidate,
    CandidateChecks,
    ScoreBreakdown,
    SearchPolicy,
    VerificationStatus,
)


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
        raise ValueError("가격, 숙박일수 또는 투숙 인원 값이 올바르지 않습니다.")

    used_fallback = False
    search_note = None

    dates_are_valid = (
            raw_check_in is not None
            and raw_check_out is not None
            and raw_check_in >= today
            and raw_check_out > raw_check_in
    )

    if dates_are_valid:
        check_in = raw_check_in
        check_out = raw_check_out
        safe_nights = (check_out - check_in).days
    else:
        safe_nights = nights
        check_in = today + timedelta(days=14)
        check_out = check_in + timedelta(days=safe_nights)
        used_fallback = True
        search_note = (
            f"요청 일정을 사용할 수 없어 미래 일정 "
            f"({check_in} ~ {check_out}, {safe_nights}박)으로 검색했습니다."
        )

    original_total = nightly_price * safe_nights

    return SearchPolicy(
        check_in_date=check_in,
        check_out_date=check_out,
        nights=safe_nights,
        adults=adults,
        original_total_amount=original_total,
        preferred_max_amount=round(original_total * 1.2),
        used_future_fallback_dates=used_fallback,
        search_note=search_note,
    )


def has_hard_failure(checks: CandidateChecks) -> bool:
    return any(
        status == VerificationStatus.FAILED
        for status in checks.model_dump().values()
    )


def is_verified_candidate(candidate: AlternativeCandidate) -> bool:
    checks = candidate.checks
    return not has_hard_failure(checks) and all(
        status == VerificationStatus.CONFIRMED
        for status in (checks.full_stay_available, checks.guest_capacity_ok)
    )


CHECK_LABELS = {
    "full_stay_available": "조회 일정 예약 가능 여부",
    "guest_capacity_ok": "투숙 인원 수용 여부",
    "address_and_listing_verified": "주소 및 숙소 실재성",
    "room_and_checkin_verified": "객실 구성 및 체크인 정보",
    "accessibility_and_amenities_ok": "접근성 및 필수 편의시설",
    "cancellation_policy_verified": "취소 조건",
}


def collect_unconfirmed_items(checks: CandidateChecks) -> list[str]:
    return [
        label
        for field, label in CHECK_LABELS.items()
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


def rank_candidates(
        candidates: list[AlternativeCandidate],
        *,
        top_k: int = 3,
) -> tuple[list[AlternativeCandidate], list[AlternativeCandidate]]:
    def sort_key(candidate: AlternativeCandidate) -> tuple:
        return (
            not has_hard_failure(candidate.checks),
            candidate.search_scope == "full_stay",
            candidate.total_score,
            candidate.total_price_krw is not None,
            -(candidate.total_price_krw or 0),
            -candidate.distance_km,
            candidate.listing_id,
        )

    recommendations = []
    exceptions = []
    seen: set[str] = set()
    for candidate in sorted(candidates, key=sort_key, reverse=True):
        if candidate.listing_id in seen:
            continue
        seen.add(candidate.listing_id)
        if has_hard_failure(candidate.checks):
            exceptions.append(candidate)
        else:
            recommendations.append(candidate)
    return recommendations[:max(1, min(top_k, 5))], exceptions
