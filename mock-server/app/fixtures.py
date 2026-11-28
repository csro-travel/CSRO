"""YB-001 follows Notion v0.1; YB-002..005 are explicit extension fixtures."""
from copy import deepcopy

STAY_DATES = ("2026-11-10", "2026-11-11", "2026-11-12")
UPDATED_AT = "2026-11-09T09:00:00+09:00"
OCCURRED_AT = "2026-11-09T10:05:00+09:00"
SENT_AT = "2026-11-09T10:06:00+09:00"

# No randomness: repeated requests receive the same scenario, even after restart.
SCENARIOS = {
    "SUP-BOOK-001": {"booking": "YB-001", "property": "HOTEL-001", "reconfirmation": "RC-001", "mode": "FIRST_NIGHT_FAILURE"},
    "SUP-BOOK-002": {"booking": "YB-002", "property": "HOTEL-002", "reconfirmation": "RC-002", "mode": "AVAILABLE"},
    "SUP-BOOK-003": {"booking": "YB-003", "property": "HOTEL-003", "reconfirmation": "RC-003", "mode": "HOTEL_NOT_FOUND"},
    "SUP-BOOK-004": {"booking": "YB-004", "property": "HOTEL-004", "reconfirmation": "RC-004", "mode": "PENDING"},
    "SUP-BOOK-005": {"booking": "YB-005", "property": "HOTEL-005", "reconfirmation": "RC-005", "mode": "UNAVAILABLE_SERVICE"},
}
# Demo 1: nine more confirmed bookings at HOTEL-002, all available like YB-002.
SCENARIOS.update({f"SUP-BOOK-{n}": {"booking": f"YB-{n}", "property": "HOTEL-002",
                                    "reconfirmation": f"RC-{n}", "mode": "AVAILABLE"} for n in range(101, 110)})
# Queue variety for the counselor screens: two more first-night failures found by the risk batch
# (one booking per hotel) and one more pending hotel reached only by a customer report.
SCENARIOS.update({f"SUP-BOOK-{n}": {"booking": f"YB-{n}", "property": f"HOTEL-{n}",
                                    "reconfirmation": f"RC-{n}", "mode": "FIRST_NIGHT_FAILURE"} for n in range(201, 203)})
SCENARIOS.update({f"SUP-BOOK-{n}": {"booking": f"YB-{n}", "property": f"HOTEL-{n}",
                                    "reconfirmation": f"RC-{n}", "mode": "PENDING"} for n in range(301, 302)})


def lookup(supplier_booking_id: str) -> dict | None:
    fixture = SCENARIOS.get(supplier_booking_id)
    return deepcopy(fixture) if fixture else None


def supplier_response(supplier_booking_id: str, fixture: dict) -> dict:
    return {
        "supplierBookingId": supplier_booking_id,
        "referenceBookingId": fixture["booking"],
        "propertyId": fixture["property"],
        "contractStatus": "CONFIRMED",
        "stay": {"checkInDate": "2026-11-10", "checkOutDate": "2026-11-13", "adults": 2, "rooms": 1},
        "updatedAt": UPDATED_AT,
    }


def hotel_response(request_id: str, supplier_booking_id: str, fixture: dict) -> dict:
    if fixture["mode"] == "PENDING":
        return {"reconfirmationRequestId": request_id, "reconfirmationId": fixture["reconfirmation"],
                "status": "PENDING", "receivedAt": OCCURRED_AT}
    all_available = fixture["mode"] == "AVAILABLE"
    nights = []
    for index, stay_date in enumerate(STAY_DATES):
        status = "AVAILABLE" if all_available else "UNAVAILABLE" if index == 0 else "UNKNOWN"
        message = (f"11월 {index + 10}일에는 객실을 제공할 수 있습니다." if all_available else
                   "11월 10일에는 객실을 제공할 수 없습니다." if index == 0 else "확인 중입니다.")
        nights.append({"stayDate": stay_date, "reportedProvisionStatus": status, "message": message})
    return {"reconfirmationRequestId": request_id, "reconfirmationId": fixture["reconfirmation"],
            "status": "RESPONDED", "supplierBookingId": supplier_booking_id, "propertyId": fixture["property"],
            "nights": nights, "occurredAt": OCCURRED_AT, "sentAt": SENT_AT}
