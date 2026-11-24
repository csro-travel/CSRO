from datetime import date

from .airbnb_agent import AirbnbAlternativeSearchAgent
from .location_agent import GoogleMapsLocationAgent
from .policy import rank_candidates, resolve_search_policy
from .schemas import AlternativeSearchResponse, HotelOrigin
from .summary_agent import apply_candidate_summaries, summarize_response


class AlternativeSearchService:
    def __init__(
            self,
            *,
            location_agent: GoogleMapsLocationAgent | None = None,
            airbnb_agent: AirbnbAlternativeSearchAgent | None = None,
            min_desired_candidates: int = 3,
            max_recommendations: int = 5,
    ) -> None:
        self.location_agent = location_agent or GoogleMapsLocationAgent()
        self.airbnb_agent = airbnb_agent or AirbnbAlternativeSearchAgent()
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
        policy = resolve_search_policy(
            nightly_price=nightly_price,
            nights=nights,
            adults=adults,
            raw_check_in=check_in_date,
            raw_check_out=check_out_date,
            today=today or date.today(),
        )

        origin: HotelOrigin = await self.location_agent.resolve_hotel_origin(
            hotel_name=hotel_name,
            region_hint=region_hint,
        )

        candidates, raw_count = await self.airbnb_agent.search_and_verify(
            origin=origin,
            policy=policy,
            radius_km=policy.primary_radius_km,
        )
        searched_radius = policy.primary_radius_km

        if len(candidates) < self.min_desired_candidates:
            expanded, expanded_count = await self.airbnb_agent.search_and_verify(
                origin=origin,
                policy=policy,
                radius_km=policy.fallback_radius_km,
            )
            searched_radius = policy.fallback_radius_km

            by_id = {candidate.listing_id: candidate for candidate in candidates}
            for candidate in expanded:
                by_id.setdefault(candidate.listing_id, candidate)
            candidates = list(by_id.values())
            raw_count += expanded_count

        apply_candidate_summaries(candidates, policy)
        recommendations, exceptions = rank_candidates(
            candidates,
            top_k=self.max_recommendations,
        )

        response = AlternativeSearchResponse(
            case_id=case_id,
            booking_id=booking_id,
            origin=origin,
            policy=policy,
            searched_radius_km=searched_radius,
            searched_candidate_count=raw_count,
            verified_candidate_count=len(candidates),
            top_recommendations=recommendations,
            exception_candidates=exceptions,
        )
        response.summary = summarize_response(response)
        return response
