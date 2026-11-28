import asyncio
from contextlib import asynccontextmanager
import logging
import os
import json
import math
import re
from typing import Any, AsyncIterator

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

from .candidate_review_agent import CandidateReviewAgent
from .schemas import AlternativeCandidate, HotelOrigin, SearchPolicy

logger = logging.getLogger(__name__)


def haversine_km(
        lat1: float,
        lon1: float,
        lat2: float,
        lon2: float,
) -> float:
    """두 위경도 사이의 대략적인 직선거리를 km로 반환합니다."""
    radius_km = 6371.0

    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    delta_phi = math.radians(lat2 - lat1)
    delta_lambda = math.radians(lon2 - lon1)

    value = (
            math.sin(delta_phi / 2) ** 2
            + math.cos(phi1)
            * math.cos(phi2)
            * math.sin(delta_lambda / 2) ** 2
    )
    value = min(1.0, max(0.0, value))

    return 2 * radius_km * math.atan2(
        math.sqrt(value),
        math.sqrt(1 - value),
    )


def _extract_json_from_mcp_result(result: Any) -> dict[str, Any]:
    """MCP 응답의 text 콘텐츠에서 JSON 객체를 추출합니다."""
    structured = getattr(result, "structuredContent", None)
    if isinstance(structured, dict) and {"searchResults", "details", "listingUrl"} & structured.keys():
        return structured

    for content in getattr(result, "content", []) or []:
        text = getattr(content, "text", None)
        if not text:
            continue

        try:
            parsed = json.loads(text)
        except json.JSONDecodeError:
            # 앞뒤 설명 문구가 붙은 경우 JSON 객체 부분만 추출합니다.
            start = text.find("{")
            end = text.rfind("}")
            if start < 0 or end <= start:
                continue

            try:
                parsed = json.loads(text[start : end + 1])
            except json.JSONDecodeError:
                continue

        if isinstance(parsed, dict):
            return parsed

    return {}


def _parse_total_price_krw(item: dict[str, Any]) -> int | None:
    """명시적으로 표시된 원화 총액만 사용합니다."""
    price = item.get("structuredDisplayPrice") or {}
    details = (price.get("explanationData") or {}).get("priceDetails")
    if isinstance(details, dict):
        details = details.get("items")

    if isinstance(details, list):
        breakdown = [
            f"{line.get('description', '')}: {line.get('priceString', '')}"
            for line in details if isinstance(line, dict)
        ]
    elif isinstance(details, str):
        # 금액 내부의 천 단위 쉼표는 분리하지 않습니다.
        breakdown = re.split(r",(?=\s*[^\d\s])|[;\n]", details)
    else:
        breakdown = []

    display = [
        line.get("accessibilityLabel")
        for key in ("primaryLine", "secondaryLine")
        if isinstance(line := price.get(key), dict)
    ]
    amount = r"(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)"
    won = rf"(?:₩\s*{amount}|{amount}\s*원)"
    total = r"(?:total(?:\s+for\s+\d+\s+nights?)?|총액|합계|총\s*금액)"
    included = r"(?:\s*\((?:includes taxes(?: and fees)?|taxes included|세금(?: 및 수수료)? 포함)\))?"
    total_line = re.compile(
        rf"(?:{total}\s*:?\s*(?P<prefix>{won})|(?P<suffix>{won})\s*{total}){included}",
        re.IGNORECASE,
    )

    for labels in (breakdown, display):
        amounts = set()
        for label in labels:
            if not isinstance(label, str):
                continue
            match = total_line.fullmatch(" ".join(label.split()))
            if match:
                value = match.group("prefix") or match.group("suffix")
                amounts.add(int(re.sub(r"[^0-9]", "", value)))
        if amounts:
            return next(iter(amounts)) if len(amounts) == 1 else None

    return None


def _extract_name(item: dict[str, Any]) -> str:
    """검색 결과에서 숙소 이름을 가져옵니다."""
    demand = item.get("demandStayListing") or {}
    description = demand.get("description") or {}
    name = description.get("name") or {}
    localized = name.get("localizedStringWithTranslationPreference")

    if isinstance(localized, str) and localized.strip():
        return localized.strip()

    return str(item.get("name") or item.get("id") or "이름 미확인 숙소")


def _extract_coordinates(
        item: dict[str, Any],
) -> tuple[float | None, float | None]:
    """검색 결과에서 숙소 좌표를 가져옵니다."""
    demand = item.get("demandStayListing") or {}
    location = demand.get("location") or {}
    coordinate = location.get("coordinate") or {}

    lat = coordinate.get("latitude")
    lng = coordinate.get("longitude")

    if isinstance(lat, (int, float)) and isinstance(lng, (int, float)):
        return float(lat), float(lng)

    return None, None


def _extract_listing_url(
        item: dict[str, Any],
        details: dict[str, Any],
) -> str | None:
    """상세 응답 또는 검색 결과에 URL이 있으면 반환합니다."""
    url = details.get("listingUrl") or item.get("url")
    return str(url) if url else None


class AirbnbMcpClient:
    """Airbnb MCP 서버에 검색 및 상세 조회를 요청합니다."""

    def __init__(
            self,
            *,
            command: str | None = None,
            args: list[str] | None = None,
    ) -> None:
        self.server_params = StdioServerParameters(
            command=command or ("npx.cmd" if os.name == "nt" else "npx"),
            args=(
                args
                if args is not None
                else ["-y", "@openbnb/mcp-server-airbnb"]
            ),
        )

    @asynccontextmanager
    async def session(self) -> AsyncIterator[ClientSession]:
        async with stdio_client(self.server_params) as (read, write):
            async with ClientSession(read, write) as session:
                await session.initialize()
                yield session

    async def search_listings(
            self,
            session: ClientSession,
            *,
            location: str,
            policy: SearchPolicy,
    ) -> list[dict[str, Any]]:
        arguments: dict[str, Any] = {
            "location": location,
            "checkin": policy.check_in_date.isoformat(),
            "checkout": policy.check_out_date.isoformat(),
            "adults": policy.adults,
            "ignoreRobotsText": True,
        }

        result = await session.call_tool(
            "airbnb_search",
            arguments=arguments,
        )

        if getattr(result, "isError", False):
            raise RuntimeError(
                f"airbnb_search 호출 실패: "
                f"{getattr(result, 'content', None)}"
            )

        parsed = _extract_json_from_mcp_result(result)
        results = parsed.get("searchResults")

        if not isinstance(results, list):
            raise RuntimeError("airbnb_search 응답에 검색 결과 배열이 없습니다.")
        return [item for item in results if isinstance(item, dict)]

    async def get_listing_details(
            self,
            session: ClientSession,
            *,
            listing_id: str,
            policy: SearchPolicy,
    ) -> dict[str, Any]:
        arguments: dict[str, Any] = {
            "id": listing_id,
            "checkin": policy.check_in_date.isoformat(),
            "checkout": policy.check_out_date.isoformat(),
            "adults": policy.adults,
            "ignoreRobotsText": True,
        }

        result = await session.call_tool(
            "airbnb_listing_details",
            arguments=arguments,
        )

        if getattr(result, "isError", False):
            raise RuntimeError(
                "airbnb_listing_details 호출 실패: "
                f"{getattr(result, 'content', None)}"
            )

        return _extract_json_from_mcp_result(result)


class AirbnbAlternativeSearchAgent:
    """MCP 검색과 상세 조회를 수행하고 후보 검토를 위임합니다."""

    def __init__(
            self,
            mcp_client: AirbnbMcpClient | None = None,
            candidate_review: CandidateReviewAgent | None = None,
    ) -> None:
        self.mcp_client = mcp_client or AirbnbMcpClient()
        self.candidate_review = candidate_review or CandidateReviewAgent()

    async def search_and_verify(
            self,
            *,
            origin: HotelOrigin,
            policy: SearchPolicy,
            radius_km: float,
            search_scope: str = "full_stay",
            skip_listing_ids: set[str] | None = None,
            max_detail_checks: int = 8,
    ) -> tuple[list[AlternativeCandidate], set[str]]:
        pending_reviews = []
        found_ids: set[str] = set()
        async with self.mcp_client.session() as session:
            raw_items = await self.mcp_client.search_listings(
                session,
                location=_build_search_location(origin, expanded=radius_km > policy.primary_radius_km),
                policy=policy,
            )
            filtered = {}
            for item in raw_items:
                listing_id = str(item.get("id") or "")
                if not listing_id:
                    continue
                found_ids.add(listing_id)
                if listing_id in filtered or listing_id in (skip_listing_ids or ()):
                    continue
                lat, lng = _extract_coordinates(item)
                if lat is None or lng is None or not (-90 <= lat <= 90 and -180 <= lng <= 180):
                    continue
                distance = haversine_km(origin.latitude, origin.longitude, lat, lng)
                if distance <= radius_km:
                    filtered[listing_id] = (item, distance)

            nearest = sorted(filtered.items(), key=lambda entry: entry[1][1])
            for listing_id, (item, distance) in nearest[:max_detail_checks]:
                try:
                    details = await self.mcp_client.get_listing_details(
                        session, listing_id=listing_id, policy=policy
                    )
                except RuntimeError:
                    logger.warning("Airbnb 숙소 상세 조회 실패 (%s)", listing_id)
                    continue
                if not details or not (details.get("details") or details.get("structuredDisplayPrice")):
                    continue
                total_price = _parse_total_price_krw(details)
                if total_price is None:
                    total_price = _parse_total_price_krw(item)
                pending_reviews.append({
                    "listing_id": listing_id,
                    "listing_name": _extract_name(item),
                    "listing_url": _extract_listing_url(item, details),
                    "item": item,
                    "details": details,
                    "policy": policy,
                    "distance_km": distance,
                    "total_price": total_price,
                    "search_scope": search_scope,
                    "matched_search_filters": not item.get("listingParamOverrides"),
                })

        candidates = await asyncio.gather(*(
            self.candidate_review.review(**review_input) for review_input in pending_reviews
        ))
        return candidates, found_ids


def _build_search_location(origin: HotelOrigin, *, expanded: bool = False) -> str:
    tokens = origin.address.split()
    if tokens and tokens[0] == "대한민국":
        tokens = tokens[1:]
    if not tokens:
        return origin.hotel_name
    if expanded:
        # MCP는 반경을 받지 않으므로 넓은 행정구역으로 조회 후 직선거리로 거릅니다.
        if tokens[0].endswith("도") and len(tokens) > 1:
            return " ".join(tokens[:2])
        return tokens[0]

    for index, token in enumerate(tokens):
        if token.endswith(("동", "읍", "면", "리")):
            return " ".join(tokens[: index + 1])
    return " ".join(tokens[:2])
