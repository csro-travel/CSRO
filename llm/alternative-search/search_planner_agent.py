from pydantic import BaseModel

from .schemas import SearchPolicy


class SearchPlanResult(BaseModel):
    policy: SearchPolicy | None = None
    reason: str


class SearchPlannerAgent:
    def plan(
            self,
            *,
            verified_candidate_count: int,
            target_count: int,
            primary_policy: SearchPolicy,
            preliminary_candidate_count: int = 0,
            ai_review_unavailable: bool = False,
    ) -> SearchPlanResult:
        if verified_candidate_count >= target_count:
            return SearchPlanResult(reason="검증 후보 수가 목표에 도달했습니다.")
        if ai_review_unavailable and preliminary_candidate_count >= target_count:
            return SearchPlanResult(
                reason=f"전체 일정 상세 조회 후보 {preliminary_candidate_count}개를 확보했습니다. "
                       "AI 검토를 완료하지 못해 예약 가능 여부와 인원 수용을 미확인으로 표시하며 "
                       "반경 확장 검색은 생략했습니다."
            )
        return SearchPlanResult(
            policy=primary_policy,
            reason=f"검증 후보 {verified_candidate_count}개로 목표 {target_count}개에 미달해 "
                   "원예약 전체 기간으로 검색 반경을 넓혔습니다.",
        )
