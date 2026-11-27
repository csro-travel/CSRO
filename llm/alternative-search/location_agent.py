from datetime import datetime, timezone
import os
import re
from typing import Any

import httpx

from .schemas import HotelOrigin

PLACES_TEXT_SEARCH_URL = "https://places.googleapis.com/v1/places:searchText"
GEOCODE_URL = "https://maps.googleapis.com/maps/api/geocode/json"


class HotelLocationError(RuntimeError):
    """호텔 위치 검색 또는 일치 검증 실패."""


def _tokenize(text: str) -> list[str]:
    cleaned = re.sub(r"[^\w가-힣\s]", " ", text.lower())
    return [token for token in cleaned.split() if len(token) >= 2]


def _build_query(hotel_name: str, region_hint: str | None) -> str:
    parts = [hotel_name.strip()]
    if region_hint and region_hint.strip():
        parts.append(region_hint.strip())
    return " ".join(parts)


def _verify_candidate_match(
        *,
        hotel_name: str,
        candidate_name: str,
        candidate_address: str,
        region_hint: str | None,
) -> tuple[bool, str]:
    normalized_hotel = re.sub(r"\s+", "", hotel_name.casefold())
    normalized_name = re.sub(r"\s+", "", candidate_name.casefold())
    normalized_address = candidate_address.casefold()

    exact_match = normalized_hotel in normalized_name
    tokens = _tokenize(hotel_name)
    matched_tokens = [
        token
        for token in tokens
        if token in candidate_name.casefold()
    ]
    token_match = len(matched_tokens) > len(tokens) / 2

    if not (exact_match or token_match):
        return False, f"숙소명 불일치: {candidate_name}"

    if region_hint:
        hint_tokens = _tokenize(region_hint)
        if hint_tokens and not any(
                token in normalized_address or token in normalized_name
                for token in hint_tokens
        ):
            return False, f"지역 힌트와 주소 불일치: {candidate_address}"

    return True, "일치"


class GoogleMapsLocationAgent:
    def __init__(
            self,
            api_key: str | None = None,
            *,
            timeout_seconds: float = 10.0,
    ) -> None:
        self.api_key = (api_key or os.getenv("GOOGLE_MAPS_API_KEY") or "").strip()
        if not self.api_key:
            raise ValueError("GOOGLE_MAPS_API_KEY 환경 변수가 설정되지 않았습니다.")
        self.timeout_seconds = timeout_seconds

    async def _search_places_text(
            self,
            client: httpx.AsyncClient,
            query: str,
            hotel_name: str,
            region_hint: str | None,
    ) -> dict[str, Any] | None:
        headers = {
            "Content-Type": "application/json",
            "X-Goog-Api-Key": self.api_key,
            "X-Goog-FieldMask": (
                "places.id,places.displayName,places.formattedAddress,places.location"
            ),
        }
        payload = {
            "textQuery": query,
            "languageCode": "ko",
            "regionCode": "KR",
            "maxResultCount": 5,
        }
        response = await client.post(
            PLACES_TEXT_SEARCH_URL,
            headers=headers,
            json=payload,
        )
        response.raise_for_status()

        places = response.json().get("places") or []
        if not places:
            return None

        for place in places:
            location = place.get("location") or {}
            address = place.get("formattedAddress")
            name = (place.get("displayName") or {}).get("text") or ""
            if not address or "latitude" not in location or "longitude" not in location:
                continue
            matched, _ = _verify_candidate_match(
                hotel_name=hotel_name,
                candidate_name=name,
                candidate_address=address,
                region_hint=region_hint,
            )
            if matched:
                return {
                    "name": name,
                    "address": address,
                    "latitude": float(location["latitude"]),
                    "longitude": float(location["longitude"]),
                    "source": "google_places_text_search",
                }
        return None

    async def _search_geocoding(
            self,
            client: httpx.AsyncClient,
            query: str,
    ) -> dict[str, Any] | None:
        response = await client.get(
            GEOCODE_URL,
            params={
                "address": query,
                "key": self.api_key,
                "language": "ko",
                "region": "kr",
            },
        )
        response.raise_for_status()

        data = response.json()
        if data.get("status") != "OK":
            return None

        results = data.get("results") or []
        if not results:
            return None

        result = results[0]
        geometry = (result.get("geometry") or {}).get("location") or {}
        address = result.get("formatted_address")
        if not address or "lat" not in geometry or "lng" not in geometry:
            return None

        # 지오코딩 결과의 이름은 호텔명으로 간주하지 않는다.
        return {
            "name": "",
            "address": address,
            "latitude": float(geometry["lat"]),
            "longitude": float(geometry["lng"]),
            "source": "google_geocoding",
        }

    async def resolve_hotel_origin(
            self,
            *,
            hotel_name: str,
            region_hint: str | None = None,
    ) -> HotelOrigin:
        if not hotel_name or not hotel_name.strip():
            raise HotelLocationError("호텔명이 비어 있습니다.")

        query = _build_query(hotel_name, region_hint)

        try:
            async with httpx.AsyncClient(timeout=self.timeout_seconds) as client:
                place = await self._search_places_text(client, query, hotel_name, region_hint)
                if place is None:
                    place = await self._search_geocoding(client, query)
        except httpx.HTTPError as exc:
            raise HotelLocationError("Google Maps 위치 조회 요청에 실패했습니다.") from exc

        if place is None:
            raise HotelLocationError(f"호텔 위치를 찾지 못했습니다. 검색어: {query}")

        # 호텔명 일치를 검증할 수 없는 단순 지오코딩 결과는 확정하지 않는다.
        if not place["name"]:
            raise HotelLocationError(
                "호텔명과 검색 결과의 일치를 확인할 수 없습니다. "
                "지역 힌트와 호텔명을 확인해 주세요."
            )

        matched, reason = _verify_candidate_match(
            hotel_name=hotel_name,
            candidate_name=place["name"],
            candidate_address=place["address"],
            region_hint=region_hint,
        )
        if not matched:
            raise HotelLocationError(f"호텔 위치 검증 실패: {reason}")

        return HotelOrigin(
            hotel_name=hotel_name.strip(),
            address=place["address"],
            latitude=place["latitude"],
            longitude=place["longitude"],
            location_source=place["source"],
            verified_at=datetime.now(timezone.utc),
        )
