from datetime import date, datetime, timedelta, timezone
import logging

from .airbnb_agent import AirbnbAlternativeSearchAgent
from .candidate_review_agent import CandidateReviewAgent
from .location_agent import GoogleMapsLocationAgent
from .openai_client import StructuredOutputClient
from .policy import has_hard_failure, is_verified_candidate, rank_candidates, resolve_search_policy
from .schemas import AlternativeSearchResponse, RankingCandidateInput
from .search_planner_agent import SearchPlannerAgent
from .summary_agent import SummaryAgent

logger = logging.getLogger(__name__)
SEOUL_TIME = timezone(timedelta(hours=9))
RANKING_VERSION = "airbnb-candidate-ranking-v1"


class AlternativeSearchService:
    def __init__(
            self,
            *,
            location_agent=None,
            airbnb_agent=None,
            search_planner=None,
            candidate_review=None,
            summary_agent=None,
            min_desired_candidates: int = 5,
            max_recommendations: int = 5,
    ) -> None:
        llm = StructuredOutputClient()
        self.location_agent = location_agent
        self.search_planner = search_planner or SearchPlannerAgent()
        self.airbnb_agent = airbnb_agent or AirbnbAlternativeSearchAgent(
            candidate_review=candidate_review or CandidateReviewAgent(llm)
        )
        self.summary_agent = summary_agent or SummaryAgent(llm)
        self.min_desired_candidates = max(1, min_desired_candidates)
        self.max_recommendations = max(1, min(max_recommendations, 5))

    async def run_auto_search(
            self,
            *,
            case_id: str,
            booking_id: str,
            hotel_name: str,
            nightly_price: int,
            nights: int,
            adults: int,
            region_hint: str | None = None,
            check_in_date: date | None = None,
            check_out_date: date | None = None,
            today: date | None = None,
    ) -> AlternativeSearchResponse:
        today = today or datetime.now(SEOUL_TIME).date()
        policy = resolve_search_policy(
            nightly_price=nightly_price,
            nights=nights,
            adults=adults,
            raw_check_in=check_in_date,
            raw_check_out=check_out_date,
            today=today,
        )
        location_agent = self.location_agent or GoogleMapsLocationAgent()
        origin = await location_agent.resolve_hotel_origin(
            hotel_name=hotel_name, region_hint=region_hint
        )
        candidates, searched_ids = await self.airbnb_agent.search_and_verify(
            origin=origin, policy=policy, radius_km=policy.primary_radius_km
        )
        searched_radius = policy.primary_radius_km
        primary_detail_count = len(candidates)
        primary_verified_count = sum(map(is_verified_candidate, candidates))
        preliminary_count = sum(not has_hard_failure(candidate.checks) for candidate in candidates)
        ai_review_unavailable = bool(candidates) and all(
            candidate.ai_review_completed is False for candidate in candidates
        )
        notes = [policy.search_note] if policy.search_note else []
        fallback_policy = None
        plan = self.search_planner.plan(
            verified_candidate_count=primary_verified_count,
            target_count=self.min_desired_candidates,
            primary_policy=policy,
            preliminary_candidate_count=preliminary_count,
            ai_review_unavailable=ai_review_unavailable,
        )
        if plan.policy is not None:
            try:
                expanded, expanded_ids = await self.airbnb_agent.search_and_verify(
                    origin=origin,
                    policy=plan.policy,
                    radius_km=plan.policy.fallback_radius_km,
                    skip_listing_ids={candidate.listing_id for candidate in candidates},
                    max_detail_checks=12,
                )
            except Exception:
                logger.exception("전체 숙박 기간 확장 검색 실패")
                notes.append("반경 확장 검색에 실패해 첫 검색 결과를 표시합니다.")
            else:
                candidates.extend(expanded)
                searched_ids.update(expanded_ids)
                searched_radius = plan.policy.fallback_radius_km
                fallback_policy = plan.policy
                notes.append(plan.reason)
        elif primary_verified_count < self.min_desired_candidates:
            notes.append(plan.reason)

        ranking_input = [
            RankingCandidateInput.model_validate(candidate, from_attributes=True)
            for candidate in candidates
        ]
        recommendations, exceptions = rank_candidates(candidates, top_k=self.max_recommendations)
        response = AlternativeSearchResponse(
            case_id=case_id,
            booking_id=booking_id,
            origin=origin,
            policy=policy,
            searched_radius_km=searched_radius,
            searched_candidate_count=len(searched_ids),
            verified_candidate_count=len({
                candidate.listing_id for candidate in candidates if is_verified_candidate(candidate)
            }),
            primary_detail_candidate_count=primary_detail_count,
            primary_verified_candidate_count=primary_verified_count,
            fallback_policy=fallback_policy,
            search_notes=notes,
            ranking_version=RANKING_VERSION,
            analyzed_at=datetime.now(timezone.utc),
            ranking_input=ranking_input,
            top_recommendations=recommendations,
            exception_candidates=exceptions,
        )
        await self.summary_agent.summarize(response)
        return response
