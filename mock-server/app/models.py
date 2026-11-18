"""Camel-case v0.1 wire models; validation distinguishes syntax from business mismatch."""
from datetime import date
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, StrictStr, field_validator


class WireModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ReconfirmationRequest(WireModel):
    reconfirmationRequestId: StrictStr = Field(min_length=1)
    supplierBookingId: StrictStr = Field(min_length=1)
    propertyId: StrictStr = Field(min_length=1)
    stayDates: list[date] = Field(min_length=1)

    @field_validator("reconfirmationRequestId", "supplierBookingId", "propertyId")
    @classmethod
    def nonblank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("Identifier must not be blank")
        return value

    @field_validator("stayDates", mode="before")
    @classmethod
    def iso_date_strings(cls, values):
        if not isinstance(values, list) or any(not isinstance(v, str) for v in values):
            raise ValueError("stayDates must be a date-string array")
        for value in values:
            parsed = date.fromisoformat(value)
            if parsed.isoformat() != value:
                raise ValueError("Use YYYY-MM-DD dates")
        return values

    @field_validator("stayDates")
    @classmethod
    def unique_dates(cls, values: list[date]) -> list[date]:
        if len(values) != len(set(values)):
            raise ValueError("stayDates must not contain duplicate dates")
        return values


class Stay(WireModel):
    checkInDate: str
    checkOutDate: str
    adults: int
    rooms: int


class SupplierBooking(WireModel):
    supplierBookingId: str
    referenceBookingId: str
    propertyId: str
    contractStatus: Literal["CONFIRMED"]
    stay: Stay
    updatedAt: str


class Night(WireModel):
    stayDate: str
    reportedProvisionStatus: Literal["AVAILABLE", "UNAVAILABLE", "UNKNOWN"]
    message: str


class HotelReply(WireModel):
    reconfirmationRequestId: str
    reconfirmationId: str
    status: Literal["RESPONDED"]
    supplierBookingId: str
    propertyId: str
    nights: list[Night]
    occurredAt: str
    sentAt: str


class HotelPending(WireModel):
    reconfirmationRequestId: str
    reconfirmationId: str
    status: Literal["PENDING"]
    receivedAt: str


class Problem(WireModel):
    type: Literal["about:blank"] = "about:blank"
    title: str
    status: int
    detail: str
    code: str
    traceId: str
