"""API-029/030 models without an optional PostgreSQL import."""
from dataclasses import dataclass
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, StrictInt, StrictStr, field_validator
from .models import Stay


class BookingRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    quoteId: StrictStr = Field(min_length=1, max_length=80)
    expectedTotalAmount: StrictInt = Field(ge=0, le=9223372036854775807)
    currency: Literal["KRW"]
    guestReference: StrictStr = Field(min_length=1, max_length=80)

    @field_validator("quoteId", "guestReference")
    @classmethod
    def nonblank_identifier(cls, value: str):
        if not value.strip() or any(ord(character) < 32 or ord(character) == 127 for character in value):
            raise ValueError("Use a nonblank identifier without control characters")
        return value


class ConfirmedPrice(BaseModel):
    model_config = ConfigDict(extra="forbid")
    totalAmount: StrictInt = Field(ge=0)
    currency: Literal["KRW"]


class BookingConfirmed(BaseModel):
    model_config = ConfigDict(extra="forbid")
    requestId: str
    status: Literal["CONFIRMED"]
    supplierBookingId: str
    quoteId: str
    propertyId: str
    stay: Stay
    confirmedPrice: ConfirmedPrice
    confirmedAt: str


class BookingLookup(BaseModel):
    model_config = ConfigDict(extra="forbid")
    requestId: str
    status: Literal["PROCESSING", "CONFIRMED", "FAILED"]
    supplierBookingId: str | None
    quoteId: str
    propertyId: str
    confirmedPrice: ConfirmedPrice | None
    confirmedAt: str | None
    failureCode: str | None = None
    failedAt: str | None = None
    currentTotalAmount: StrictInt | None = Field(default=None, ge=0)
    currency: Literal["KRW"] | None = None


@dataclass(frozen=True)
class BookingOutcome:
    status: int
    body: dict
    replayed: bool = False


class BookingError(Exception):
    def __init__(self, status: int, code: str, detail: str):
        super().__init__(code)
        self.status, self.code, self.detail = status, code, detail
