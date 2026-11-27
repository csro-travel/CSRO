from pydantic import BaseModel

from .openai_client import StructuredOutputClient
from .policy import CHECK_LABELS
from .schemas import AlternativeCandidate, AlternativeSearchResponse, VerificationStatus


class SummaryFinding(BaseModel):
    listing_id: str
    ordered_reason_keys: list[str]


class SummaryResult(BaseModel):
    findings: list[SummaryFinding]


def _selection_facts(candidate: AlternativeCandidate) -> dict[str, str]:
    facts = {
        "score": f"총점 {candidate.total_score}/100점",
        "distance": f"기준 숙소에서 직선거리 {candidate.distance_km:.1f}km",
    }
    if candidate.total_price_krw is not None:
        facts["price"] = f"조회 일정 총액 {candidate.total_price_krw:,}원"
    for field, label in CHECK_LABELS.items():
        if getattr(candidate.checks, field) == VerificationStatus.CONFIRMED:
            facts[field] = f"{label} 확인"
    return facts


def _customer_highlight(candidate: AlternativeCandidate, ordered_keys: dict[str, None]) -> str:
    sentences = {
        "distance": f"기존 숙소에서 직선거리 {candidate.distance_km:.2f}km에 있습니다.",
        "full_stay_available": "검색 시점에 요청하신 전체 일정으로 조회되었습니다.",
        "guest_capacity_ok": "요청하신 인원을 수용할 수 있는 객실로 확인되었습니다.",
        "room_and_checkin_verified": "객실 구성과 체크인 정보를 확인할 수 있습니다.",
        "cancellation_policy_verified": "숙소 페이지에서 취소 조건을 확인할 수 있습니다.",
    }
    highlights = [sentences[key] for key in ordered_keys if key in sentences]
    return " ".join(highlights[:2])


class SummaryAgent:
    def __init__(self, llm: StructuredOutputClient | None = None) -> None:
        self.llm = llm or StructuredOutputClient()

    async def summarize(self, response: AlternativeSearchResponse) -> None:
        facts_by_id = {
            candidate.listing_id: _selection_facts(candidate)
            for candidate in response.top_recommendations
        }
        order_by_id = {}
        if facts_by_id:
            result = await self.llm.parse(
                SummaryResult,
                "상담사가 비교하기 쉽도록 숙소별 선정 이유의 키를 중요한 순서로 배열하세요. "
                "주어진 listing_id와 사전에 있는 키만 사용하세요.",
                facts_by_id,
            )
            if result:
                order_by_id = {item.listing_id: item.ordered_reason_keys for item in result.findings}

        for candidate in response.top_recommendations + response.exception_candidates:
            facts = facts_by_id.get(candidate.listing_id)
            candidate.counselor_checks = [
                                             f"{item} 확인" for item in candidate.unconfirmed_items
                                         ] + [
                                             f"{label} 불충족 조건 확인"
                                             for field, label in CHECK_LABELS.items()
                                             if getattr(candidate.checks, field) == VerificationStatus.FAILED
                                         ] + [
                                             reason for reason in candidate.approval_required_reasons
                                             if not reason.startswith("미확인 항목:") and reason != "총 숙박 가격 미확인"
                                         ]
            if facts:
                ordered_keys = dict.fromkeys([*order_by_id.get(candidate.listing_id, []), *facts])
                descriptions = [facts[key] for key in ordered_keys if key in facts]
                if candidate.search_scope == "incident_night":
                    descriptions.insert(0, "장애 발생일 기준 1박")
                candidate.selection_reason = " · ".join(descriptions)
                candidate.customer_highlight = _customer_highlight(
                    candidate, dict.fromkeys(key for key in ordered_keys if key in facts)
                )
            else:
                candidate.selection_reason = "상세 조회에서 조건 불충족이 확인되었습니다."

        count = len(response.top_recommendations)
        exceptions = len(response.exception_candidates)
        summary = f"추천 후보 {count}개, 조건 불충족 후보 {exceptions}개를 찾았습니다."
        if count:
            summary += " 각 후보의 조회 일정, 미확인 항목 및 상담사 확인 사항을 확인해 주세요."
        if response.search_notes:
            summary += " " + " ".join(response.search_notes)
        response.summary = summary
