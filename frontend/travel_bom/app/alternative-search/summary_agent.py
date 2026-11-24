from .schemas import (
    AlternativeCandidate,
    AlternativeSearchResponse,
    SearchPolicy,
)


def summarize_candidate(
        candidate: AlternativeCandidate,
        policy: SearchPolicy,
) -> str:
    parts: list[str] = []

    if candidate.score_breakdown:
        parts.append(f"총점 {candidate.score_breakdown.total}/100점")

    parts.append(f"기준 숙소에서 직선거리 {candidate.distance_km:.1f}km")

    if candidate.total_price_krw is None:
        parts.append("총 숙박 가격 미확인")
    else:
        parts.append(f"총액 {candidate.total_price_krw:,}원")
        if candidate.price_diff_krw is not None:
            diff = candidate.price_diff_krw
            if diff < 0:
                parts.append(f"원 예약보다 {abs(diff):,}원 저렴")
            elif diff > 0:
                parts.append(f"원 예약보다 {diff:,}원 높음")
            else:
                parts.append("원 예약과 동일 금액")

    if candidate.approval_required_reasons:
        parts.append(
            "승인 확인 필요: " + ", ".join(candidate.approval_required_reasons)
        )
    elif candidate.unconfirmed_items:
        parts.append("추가 확인 필요: " + ", ".join(candidate.unconfirmed_items))
    else:
        parts.append("필수 확인 항목 검증 완료")

    return " · ".join(parts)


def summarize_response(response: AlternativeSearchResponse) -> str:
    recommendation_count = len(response.top_recommendations)
    exception_count = len(response.exception_candidates)

    if recommendation_count == 0 and exception_count == 0:
        summary = "조건에 맞는 대체 숙소 후보를 찾지 못했습니다."
    else:
        summary = (
            f"추천 후보 {recommendation_count}개, "
            f"승인 또는 추가 확인이 필요한 후보 {exception_count}개를 찾았습니다."
        )

    if response.policy.used_future_fallback_dates:
        summary += " 원 일정 대신 미래 일정 기준으로 검색했습니다."

    return summary


def apply_candidate_summaries(
        candidates: list[AlternativeCandidate],
        policy: SearchPolicy,
) -> None:
    for candidate in candidates:
        candidate.selection_reason = summarize_candidate(candidate, policy)
