from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
import asyncio
import json
import math
import re
from typing import Any, AsyncIterator


from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

from .policy import (
    calculate_score,
    collect_approval_reasons,
    collect_unconfirmed_items,
    has_hard_failure,
)
from .schemas import (
    AlternativeCandidate,
    CandidateChecks,
    HotelOrigin,
    SearchPolicy,
    VerificationStatus,
)


class SupplierResponseError(RuntimeError):
    """A stable supplier failure; raw MCP content must not enter errors or logs."""


def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    radius = 6371.0
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    delta_phi = math.radians(lat2 - lat1)
    delta_lambda = math.radians(lon2 - lon1)
    value = (
            math.sin(delta_phi / 2) ** 2
            + math.cos(phi1)
            * math.cos(phi2)
            * math.sin(delta_lambda / 2) ** 2
    )
    value = min(1.0, max(0.0, value))
    return 2 * radius * math.atan2(math.sqrt(value), math.sqrt(1 - value))


def _extract_json(result: Any) -> dict[str, Any]:
    for content in getattr(result, "content", []) or []:
        text = getattr(content, "text", None)
        if not text:
            continue
        try:
            parsed = json.loads(text)
        except json.JSONDecodeError:
            start, end = text.find("{"), text.rfind("}")
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
    """Read a reported whole-stay KRW total, never infer it from a nightly rate.

    Missing currency, mixed amounts and excluded fees are unconfirmed. A number
    scraped from the page is not enough evidence to call it a stay's KRW total.
    """
    price = item.get("structuredDisplayPrice")
    if not isinstance(price, dict):
        return None
    primary = price.get("primaryLine")
    explanation = price.get("explanationData")
    texts = (
        explanation.get("priceDetails") if isinstance(explanation, dict) else None,
        primary.get("accessibilityLabel") if isinstance(primary, dict) else None,
    )
    amount = r"(?:[1-9][0-9]{0,2}(?:,[0-9]{3})+|0|[1-9][0-9]*)"
    money = re.compile(
        rf"(?<![0-9.,])(?:₩|\bKRW\s*)\s*({amount})(?![0-9.]|,[0-9])"
        rf"|(?<![0-9.,])({amount})(?![0-9.]|,[0-9])\s*원",
        re.IGNORECASE,
    )
    total_label = re.compile(r"\btotal\b|총액|총\s*금액|총\s*요금|합계|전체\s*(?:숙박\s*)?(?:금액|요금)", re.IGNORECASE)
    not_total = re.compile(
        r"per\s*night|/\s*night|nightly|박\s*당|1\s*박|"
        r"excluding|before\s+(?:tax|fee)|plus\s+(?:tax|fee)|세금\s*별도|수수료\s*별도|"
        r"(?:세금|수수료)[^.;\n]*불포함|[$€£¥]|\b(?:USD|EUR|GBP|JPY|CNY)\b",
        re.IGNORECASE,
    )
    totals: set[int] = set()
    for text in texts:
        if not isinstance(text, str) or not total_label.search(text) or not_total.search(text):
            continue
        values = {int((match.group(1) or match.group(2)).replace(",", "")) for match in money.finditer(text)}
        if len(values) != 1:
            return None
        totals.update(values)
    return next(iter(totals)) if len(totals) == 1 else None


def _extract_name(item: dict[str, Any]) -> str:
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
    demand = item.get("demandStayListing") or {}
    location = demand.get("location") or {}
    coordinate = location.get("coordinate") or {}
    lat, lng = coordinate.get("latitude"), coordinate.get("longitude")
    if isinstance(lat, (int, float)) and isinstance(lng, (int, float)):
        return float(lat), float(lng)
    return None, None


def _extract_listing_url(
        item: dict[str, Any],
        details: dict[str, Any],
) -> str | None:
    url = details.get("listingUrl") or item.get("url")
    return str(url) if url else None


def _analyze_details(
        payload: dict[str, Any],
) -> tuple[bool, bool, str, float]:
    detail_sections = payload.get("details") or []
    has_amenities = False
    has_policy = False
    amenity_groups: list[Any] = []

    for section in detail_sections:
        if not isinstance(section, dict):
            continue
        section_id = section.get("id")
        if section_id == "AMENITIES_DEFAULT":
            has_amenities = True
            groups = section.get("seeAllAmenitiesGroups")
            if isinstance(groups, dict):
                amenity_groups = list(groups.values())
            elif isinstance(groups, list):
                amenity_groups = groups
        elif section_id == "POLICIES_DEFAULT":
            has_policy = True

    cancellation_summary = (
        "정책 섹션 수신 · 취소 규정 미확인" if has_policy else "취소 규정 미확인"
    )
    # 그룹 수는 필수 편의시설 충족을 의미하지 않으므로 점수 근거로 사용하지 않는다.
    amenity_ratio = 0.0
    return has_amenities, has_policy, cancellation_summary, amenity_ratio

def _extract_json_from_mcp_result(result: Any) -> dict[str, Any]:
    """MCP 응답의 text 콘텐츠에서 JSON 객체를 추출한다."""
    if getattr(result, "isError", False):
        raise SupplierResponseError("SUPPLIER_TOOL_ERROR")
    structured = getattr(result, "structuredContent", None)
    if isinstance(structured, dict) and structured:
        return structured
    for item in getattr(result, "content", []) or []:
        text = getattr(item, "text", None)
        if not text:
            continue

        try:
            parsed = json.loads(text)
            if isinstance(parsed, dict):
                return parsed
        except json.JSONDecodeError:
            # 텍스트에 설명이 앞뒤로 붙은 경우 JSON 객체 부분만 시도
            start = text.find("{")
            end = text.rfind("}")
            if start >= 0 and end > start:
                try:
                    parsed = json.loads(text[start : end + 1])
                    if isinstance(parsed, dict):
                        return parsed
                except json.JSONDecodeError:
                    continue

    raise SupplierResponseError("SUPPLIER_RESPONSE_INVALID")



class AirbnbMcpClient:
    def __init__(
            self,
            *,
            command: str = "npx",
            args: list[str] | None = None,
            timeout_seconds: float = 60.0,
    ) -> None:
        if not math.isfinite(timeout_seconds) or timeout_seconds <= 0:
            raise ValueError("A positive supplier timeout is required")
        self.timeout_seconds = timeout_seconds
        self.server_params = StdioServerParameters(
            command=command,
            args=args
            if args is not None
            else ["-y", "@openbnb/mcp-server-airbnb"],
        )

    @asynccontextmanager
    async def session(self) -> AsyncIterator[ClientSession]:
        try:
            async with asyncio.timeout(self.timeout_seconds):
                async with stdio_client(self.server_params) as (read, write):
                    async with ClientSession(
                        read, write, read_timeout_seconds=timedelta(seconds=self.timeout_seconds),
                    ) as session:
                        await session.initialize()
                        yield session
        except TimeoutError:
            raise SupplierResponseError("SUPPLIER_TIMEOUT") from None

    async def _call_tool(self, session: ClientSession, name: str, arguments: dict[str, Any]) -> Any:
        try:
            return await asyncio.wait_for(
                session.call_tool(name, arguments=arguments), timeout=self.timeout_seconds,
            )
        except TimeoutError:
            raise SupplierResponseError("SUPPLIER_TIMEOUT") from None
        except Exception:
            raise SupplierResponseError("SUPPLIER_CALL_FAILED") from None


    async def search_listings(
        self,
        session: ClientSession,
        *,
        location: str,
        policy: SearchPolicy,
        max_price: int | None = None,
        cursor: str | None = None,
    ) -> list[dict[str, Any]]:
        arguments: dict[str, Any] = {
            "location": location,
            "checkin": policy.check_in_date.isoformat(),
            "checkout": policy.check_out_date.isoformat(),
            "adults": policy.adults,
            "ignoreRobotsText": True,
        }

        if max_price is not None and max_price > 0:
            arguments["maxPrice"] = max_price

        if cursor:
            arguments["cursor"] = cursor

        result = await self._call_tool(session, "airbnb_search", arguments)
        parsed = _extract_json_from_mcp_result(result)
        results = parsed.get("searchResults")
        if not isinstance(results, list) or any(not isinstance(item, dict) for item in results):
            raise SupplierResponseError("SUPPLIER_SEARCH_RESPONSE_INVALID")
        return results
    
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

        result = await self._call_tool(session, "airbnb_listing_details", arguments)
        parsed = _extract_json_from_mcp_result(result)
        if not isinstance(parsed.get("details"), list):
            raise SupplierResponseError("SUPPLIER_DETAIL_RESPONSE_INVALID")
        return parsed




class AirbnbAlternativeSearchAgent:
    def __init__(self, mcp_client: AirbnbMcpClient | None = None) -> None:
        self.mcp_client = mcp_client or AirbnbMcpClient()

    async def search_and_verify(
            self,
            *,
            origin: HotelOrigin,
            policy: SearchPolicy,
            radius_km: float,
            max_detail_checks: int = 8,
    ) -> tuple[list[AlternativeCandidate], int]:
        candidates: list[AlternativeCandidate] = []

        async with self.mcp_client.session() as session:
            raw_items = await self.mcp_client.search_listings(
                session,
                location=_build_search_location(origin)
,
                policy=policy,
            )

            filtered: list[tuple[dict[str, Any], float | None]] = []
            for item in raw_items:
                lat, lng = _extract_coordinates(item)
                if lat is None or lng is None:
                    # 위치를 확인할 수 없는 후보는 거리 조건을 검증할 수 없어 제외한다.
                    continue
                distance = round(
                    haversine_km(origin.latitude, origin.longitude, lat, lng),
                    2,
                )
                if distance <= radius_km:
                    filtered.append((item, distance))

            for item, distance in filtered[:max_detail_checks]:
                listing_id = str(item.get("id") or "")
                if not listing_id:
                    continue

                details = await self.mcp_client.get_listing_details(
                    session,
                    listing_id=listing_id,
                    policy=policy,
                )
                if not details:
                    continue

                has_amenities, has_policy, cancel_summary, amenity_ratio = (
                    _analyze_details(details)
                )
                total_price = _parse_total_price_krw(item)
                listing_url = _extract_listing_url(item, details)

                # 응답에 실제 인원 수용 정보가 없으므로 확정으로 처리하지 않는다.
                checks = CandidateChecks(
                    full_stay_available=VerificationStatus.UNCONFIRMED,
                    guest_capacity_ok=VerificationStatus.UNCONFIRMED,
                    # A URL alone verifies neither the address nor the listing identity.
                    address_and_listing_verified=VerificationStatus.UNCONFIRMED,
                    room_and_checkin_verified=VerificationStatus.UNCONFIRMED,
                    accessibility_and_amenities_ok=(
                        VerificationStatus.UNCONFIRMED
                        if has_amenities
                        else VerificationStatus.UNCONFIRMED
                    ),
                    # A generic policies section may contain only house/check-in rules.
                    cancellation_policy_verified=VerificationStatus.UNCONFIRMED,
                )

                unconfirmed = collect_unconfirmed_items(checks)
                if total_price is None:
                    unconfirmed.append("세금·수수료 포함 총액 미확인")

                approval_reasons = collect_approval_reasons(
                    distance_km=distance,
                    total_price=total_price,
                    preferred_max_amount=policy.preferred_max_amount,
                    primary_radius_km=policy.primary_radius_km,
                    unconfirmed_items=unconfirmed,
                )

                score = calculate_score(
                    distance_km=distance,
                    total_price=total_price,
                    preferred_max_amount=policy.preferred_max_amount,
                    checks=checks,
                    amenities_confirmed_ratio=amenity_ratio,
                    flexible_cancellation=False,
                )

                candidate = AlternativeCandidate(
                    listing_id=listing_id,
                    name=_extract_name(item),
                    address=None,
                    listing_url=listing_url,
                    source="airbnb_mcp",
                    checked_at=datetime.now(timezone.utc),
                    distance_km=distance,
                    total_price_krw=total_price,
                    price_diff_krw=(
                        total_price - policy.original_total_amount
                        if total_price is not None
                        else None
                    ),
                    cancellation_summary=cancel_summary,
                    checks=checks,
                    unconfirmed_items=unconfirmed,
                    approval_required_reasons=approval_reasons,
                    score_breakdown=score,
                )

                if not has_hard_failure(candidate.checks):
                    candidates.append(candidate)

        return candidates, len(raw_items)

def _build_search_location(origin: HotelOrigin) -> str:
    """전체 주소 대신 시·군·구와 동/읍/면/리 단위의 검색어를 만든다."""
    address = (origin.address or "").strip()
    tokens = address.split()

    # 예: 제주특별자치도 제주시 건입동 서부두2길 20
    # 결과: 제주특별자치도 제주시 건입동
    for index, token in enumerate(tokens):
        if token.endswith(("동", "읍", "면", "리")):
            return " ".join(tokens[: index + 1])

    # 행정동 토큰을 찾지 못했을 때는 너무 긴 주소 대신 앞부분 사용
    if len(tokens) >= 2:
        return " ".join(tokens[:2])

    return address or origin.hotel_name
