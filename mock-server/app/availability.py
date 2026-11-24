"""API-028 deterministic quote search. No reservation, inventory hold, or DB writes.

The reference three-night product data follows Notion v0.1. Shorter periods and
multiple-room prices are explicit implementation fixtures: each requested night
uses the same per-room daily base/fee and all nights must have stock.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, timedelta
import hashlib
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, StrictInt, StrictStr, field_validator, model_validator

CHECKED_AT = "2026-11-09T10:15:00+09:00"
EXPIRES_AT = "2026-11-09T10:25:00+09:00"
FIRST_NIGHT = date(2026, 11, 10)
CHECK_OUT = date(2026, 11, 13)


class SearchWireModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class SearchStay(SearchWireModel):
    checkInDate: date
    checkOutDate: date
    # Preserve the canonical schema minimum while reporting integer business
    # violations as the contract's INVALID_OCCUPANCY (422), not syntax (400).
    adults: StrictInt = Field(json_schema_extra={"minimum": 1})
    rooms: StrictInt = Field(json_schema_extra={"minimum": 1})

    @field_validator("checkInDate", "checkOutDate", mode="before")
    @classmethod
    def iso_date_string(cls, value):
        if not isinstance(value, str) or date.fromisoformat(value).isoformat() != value:
            raise ValueError("Use YYYY-MM-DD dates")
        return value


class SearchFilters(SearchWireModel):
    radiusMeters: StrictInt = Field(ge=1, le=20000)
    currency: Literal["KRW"]
    maxAverageNightlyAmount: StrictInt | None = Field(default=None, ge=1)

    @field_validator("maxAverageNightlyAmount", mode="before")
    @classmethod
    def omitted_not_null(cls, value):
        if value is None:
            raise ValueError("Omit the optional limit rather than supplying null")
        return value


class AvailabilitySearch(SearchWireModel):
    searchRequestId: StrictStr = Field(min_length=1)
    originPropertyId: StrictStr = Field(min_length=1)
    stay: SearchStay
    filters: SearchFilters

    @field_validator("searchRequestId", "originPropertyId")
    @classmethod
    def nonblank(cls, value):
        if not value.strip():
            raise ValueError("Identifier must not be blank")
        return value


class QuotePrice(SearchWireModel):
    baseAmount: StrictInt = Field(ge=0)
    mandatoryFeesAmount: StrictInt = Field(ge=0)
    totalAmount: StrictInt = Field(ge=0)
    currency: Literal["KRW"]

    @model_validator(mode="after")
    def total_matches(self):
        if self.totalAmount != self.baseAmount + self.mandatoryFeesAmount:
            raise ValueError("Quote total must include base amount and mandatory fees")
        return self


class AvailabilityQuote(SearchWireModel):
    quoteId: str
    source: Literal["SIMULATED_SUPPLIER"]
    propertyId: str
    propertyName: str
    distanceMeters: int = Field(ge=0)
    roomTypeId: str
    roomName: str
    ratePlanId: str
    maxAdults: int = Field(ge=1)
    breakfastIncluded: bool
    availableRoomCount: int = Field(ge=0)
    price: QuotePrice
    cancellationPolicy: Literal["NON_REFUNDABLE"]
    checkedAt: str
    expiresAt: str

    @model_validator(mode="after")
    def expiry_after_check(self):
        checked, expiry = datetime.fromisoformat(self.checkedAt), datetime.fromisoformat(self.expiresAt)
        if checked.tzinfo is None or expiry.tzinfo is None or expiry <= checked:
            raise ValueError("Quote timestamps require a timezone and a later expiry")
        return self


class AvailabilityResponse(SearchWireModel):
    searchRequestId: str
    searchStatus: Literal["COMPLETED"]
    stay: SearchStay
    checkedAt: str
    currency: Literal["KRW"]
    quotes: list[AvailabilityQuote] = Field(max_length=100)

    @model_validator(mode="after")
    def consistent_quotes(self):
        ids = [quote.quoteId for quote in self.quotes]
        if len(ids) != len(set(ids)):
            raise ValueError("Quote IDs must be unique within a search")
        if self.stay.checkOutDate <= self.stay.checkInDate or min(self.stay.adults, self.stay.rooms) < 1:
            raise ValueError("Response must preserve a valid stay")
        for quote in self.quotes:
            if (quote.availableRoomCount < self.stay.rooms
                    or quote.maxAdults * self.stay.rooms < self.stay.adults
                    or quote.checkedAt != self.checkedAt or quote.price.currency != self.currency):
                raise ValueError("Quote stock, occupancy, timestamp and currency must match the search")
        return self


@dataclass(frozen=True)
class Product:
    letter: str
    distance: int
    room_type: str
    room_name: str
    rate_plan: str
    breakfast: bool
    daily_base: int
    daily_fee: int
    night_stock: tuple[int, int, int]


PRODUCTS = (
    Product("A", 800, "DOUBLE", "디럭스 더블", "BREAKFAST", True, 150000, 10000, (1, 1, 1)),
    Product("B", 1400, "TWIN", "스탠다드 트윈", "STANDARD", False, 155000, 15000, (2, 2, 2)),
    Product("C", 2600, "DOUBLE", "스탠다드 더블", "STANDARD", False, 145000, 10000, (1, 1, 1)),
)


class InvalidSearch(Exception):
    def __init__(self, code: str, detail: str):
        self.code, self.detail = code, detail


def search_quotes(body: AvailabilitySearch, products: tuple[Product, ...] = PRODUCTS) -> AvailabilityResponse:
    stay = body.stay
    if stay.checkOutDate <= stay.checkInDate:
        raise InvalidSearch("INVALID_STAY_DATES", "체크아웃 날짜는 체크인 날짜보다 늦어야 합니다.")
    if stay.adults < 1 or stay.rooms < 1:
        raise InvalidSearch("INVALID_OCCUPANCY", "성인과 객실 수는 각각 1 이상이어야 합니다.")
    quotes = []
    # An unsupported origin/period has no fixture inventory. Never silently move
    # dates or return another hotel's distance for the requested origin.
    supported = (body.originPropertyId == "HOTEL-001"
                 and FIRST_NIGHT <= stay.checkInDate < stay.checkOutDate <= CHECK_OUT)
    if supported:
        nights = (stay.checkOutDate - stay.checkInDate).days
        indexes = [(stay.checkInDate + timedelta(days=day) - FIRST_NIGHT).days for day in range(nights)]
        for product in products:
            available = min(product.night_stock[index] for index in indexes)
            if (product.distance > body.filters.radiusMeters or stay.adults > 2 * stay.rooms
                    or available < stay.rooms):
                continue
            base = product.daily_base * nights * stay.rooms
            fees = product.daily_fee * nights * stay.rooms
            limit = body.filters.maxAverageNightlyAmount
            if limit is not None and base + fees > limit * nights:
                continue
            canonical = (stay.checkInDate == FIRST_NIGHT and stay.checkOutDate == CHECK_OUT
                         and stay.adults == 2 and stay.rooms == 1)
            identity = f"{stay.checkInDate}|{stay.checkOutDate}|{stay.adults}|{stay.rooms}"
            suffix = "" if canonical else "-" + hashlib.sha256(identity.encode()).hexdigest()[:16]
            quotes.append(AvailabilityQuote(
                quoteId=f"QUOTE-{product.letter}{suffix}", source="SIMULATED_SUPPLIER",
                propertyId=f"HOTEL-{product.letter}", propertyName=f"{product.letter}호텔",
                distanceMeters=product.distance, roomTypeId=f"ROOM-{product.letter}-{product.room_type}",
                roomName=product.room_name, ratePlanId=f"RATE-{product.letter}-{product.rate_plan}",
                maxAdults=2, breakfastIncluded=product.breakfast, availableRoomCount=available,
                price=QuotePrice(baseAmount=base, mandatoryFeesAmount=fees, totalAmount=base + fees,
                                 currency="KRW"), cancellationPolicy="NON_REFUNDABLE",
                checkedAt=CHECKED_AT, expiresAt=EXPIRES_AT))
    return AvailabilityResponse(searchRequestId=body.searchRequestId, searchStatus="COMPLETED", stay=stay,
                                checkedAt=CHECKED_AT, currency="KRW", quotes=quotes)
