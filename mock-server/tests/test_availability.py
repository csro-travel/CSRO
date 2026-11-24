"""API-028 wire contract and quote safety; no Spring or database required."""
from copy import deepcopy
from dataclasses import replace
from datetime import datetime
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from app.availability import PRODUCTS, AvailabilitySearch, search_quotes
from app.main import create_app

PATH = "/mock-supplier/v1/availability-searches"
TOKEN = "availability-contract-token"
AUTH = {"Authorization": "Bearer " + TOKEN}
DEFAULT_BODY = object()
REQUEST = {
    "searchRequestId": "SEARCH-001", "originPropertyId": "HOTEL-001",
    "stay": {"checkInDate": "2026-11-10", "checkOutDate": "2026-11-13", "adults": 2, "rooms": 1},
    "filters": {"radiusMeters": 3000, "currency": "KRW", "maxAverageNightlyAmount": 300000},
}


def expected_quote(letter, distance, room, room_name, rate, breakfast, count, base, fees):
    # Independent transcription of Notion v0.1, not derived from app products.
    return {
        "quoteId": f"QUOTE-{letter}", "source": "SIMULATED_SUPPLIER", "propertyId": f"HOTEL-{letter}",
        "propertyName": f"{letter}호텔", "distanceMeters": distance,
        "roomTypeId": f"ROOM-{letter}-{room}", "roomName": room_name,
        "ratePlanId": f"RATE-{letter}-{rate}", "maxAdults": 2, "breakfastIncluded": breakfast,
        "availableRoomCount": count,
        "price": {"baseAmount": base, "mandatoryFeesAmount": fees, "totalAmount": base + fees, "currency": "KRW"},
        "cancellationPolicy": "NON_REFUNDABLE", "checkedAt": "2026-11-09T10:15:00+09:00",
        "expiresAt": "2026-11-09T10:25:00+09:00",
    }


EXPECTED = {
    "searchRequestId": "SEARCH-001", "searchStatus": "COMPLETED", "stay": REQUEST["stay"],
    "checkedAt": "2026-11-09T10:15:00+09:00", "currency": "KRW",
    "quotes": [
        expected_quote("A", 800, "DOUBLE", "디럭스 더블", "BREAKFAST", True, 1, 450000, 30000),
        expected_quote("B", 1400, "TWIN", "스탠다드 트윈", "STANDARD", False, 2, 465000, 45000),
        expected_quote("C", 2600, "DOUBLE", "스탠다드 더블", "STANDARD", False, 1, 435000, 30000),
    ],
}


class AvailabilityTests(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(create_app(service_token=TOKEN))
        self.addCleanup(self.client.close)

    def search(self, body=DEFAULT_BODY, headers=None):
        return self.client.post(PATH, json=REQUEST if body is DEFAULT_BODY else body,
                                headers=AUTH if headers is None else headers)

    def problem(self, response, status, code):
        self.assertEqual(response.status_code, status, response.text)
        self.assertEqual(response.headers["content-type"], "application/problem+json")
        payload = response.json()
        self.assertEqual(payload["code"], code)
        self.assertEqual(payload["status"], status)
        self.assertEqual(payload["traceId"], response.headers["x-request-id"])

    def test_reference_three_night_response_exactly_matches_notion(self):
        response = self.search()
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(response.json(), EXPECTED)
        self.assertNotIn("supplierBookingId", response.json())
        for quote in response.json()["quotes"]:
            self.assertGreater(datetime.fromisoformat(quote["expiresAt"]), datetime.fromisoformat(quote["checkedAt"]))

    def test_search_requires_service_bearer_and_openapi_documents_it(self):
        self.problem(self.search(headers={}), 401, "UNAUTHENTICATED_SERVICE")
        self.problem(self.search(headers={"Authorization": "Bearer wrong"}), 401, "UNAUTHENTICATED_SERVICE")
        self.assertEqual(self.search(headers={"authorization": AUTH["Authorization"]}).status_code, 200)
        operation = self.client.get("/openapi.json").json()["paths"][PATH]["post"]
        self.assertEqual(operation["security"], [{"InternalServiceBearer": []}])
        for code in ("400", "401", "422", "503", "500"):
            self.assertEqual(set(operation["responses"][code]["content"]), {"application/problem+json"})

    def test_radius_and_average_price_limits_use_whole_stay_total(self):
        body = deepcopy(REQUEST)
        body["filters"]["radiusMeters"] = 800
        self.assertEqual([q["quoteId"] for q in self.search(body).json()["quotes"]], ["QUOTE-A"])
        body["filters"]["radiusMeters"] = 3000
        body["filters"]["maxAverageNightlyAmount"] = 155000
        self.assertEqual([q["quoteId"] for q in self.search(body).json()["quotes"]], ["QUOTE-C"])
        body["filters"]["maxAverageNightlyAmount"] = 154999
        response = self.search(body)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["quotes"], [])
        del body["filters"]["maxAverageNightlyAmount"]
        self.assertEqual(len(self.search(body).json()["quotes"]), 3)

    def test_empty_search_does_not_rewrite_dates_or_auto_fallback(self):
        for origin, checkin, checkout in (("OTHER-HOTEL", "2026-11-10", "2026-11-13"),
                ("HOTEL-001", "2026-11-09", "2026-11-13"), ("HOTEL-001", "2027-11-10", "2027-11-13")):
            body = deepcopy(REQUEST)
            body["originPropertyId"] = origin
            body["stay"].update(checkInDate=checkin, checkOutDate=checkout)
            response = self.search(body)
            self.assertEqual(response.status_code, 200, response.text)
            self.assertEqual(response.json()["quotes"], [])
            self.assertEqual(response.json()["stay"], body["stay"])

    def test_explicit_one_night_has_distinct_quote_ids_and_one_night_prices(self):
        body = deepcopy(REQUEST)
        body["searchRequestId"] = "SEARCH-FIRST-NIGHT"
        body["stay"]["checkOutDate"] = "2026-11-11"
        first = self.search(body).json()
        self.assertEqual(first["stay"], body["stay"])
        self.assertEqual([q["price"]["totalAmount"] for q in first["quotes"]], [160000, 170000, 155000])
        self.assertFalse({q["quoteId"] for q in first["quotes"]} & {"QUOTE-A", "QUOTE-B", "QUOTE-C"})
        body["searchRequestId"] = "ANOTHER-TRACE-ID"
        self.assertEqual([q["quoteId"] for q in first["quotes"]],
                         [q["quoteId"] for q in self.search(body).json()["quotes"]])
        body["stay"].update(checkInDate="2026-11-11", checkOutDate="2026-11-12")
        self.assertFalse({q["quoteId"] for q in first["quotes"]} &
                         {q["quoteId"] for q in self.search(body).json()["quotes"]})

    def test_occupancy_and_inventory_filter_all_requested_nights(self):
        body = deepcopy(REQUEST)
        body["stay"]["adults"] = 3
        self.assertEqual(self.search(body).json()["quotes"], [])
        body["stay"].update(adults=4, rooms=2)
        del body["filters"]["maxAverageNightlyAmount"]
        quotes = self.search(body).json()["quotes"]
        self.assertEqual([q["propertyId"] for q in quotes], ["HOTEL-B"])
        self.assertEqual(quotes[0]["price"]["totalAmount"], 1020000)
        no_last_night = tuple(replace(p, night_stock=(p.night_stock[0], p.night_stock[1], 0)) for p in PRODUCTS)
        full = search_quotes(AvailabilitySearch.model_validate(REQUEST), no_last_night)
        self.assertEqual(full.quotes, [])
        body = deepcopy(REQUEST)
        body["stay"]["checkOutDate"] = "2026-11-11"
        self.assertEqual(len(search_quotes(AvailabilitySearch.model_validate(body), no_last_night).quotes), 3)

    def test_repeats_and_trace_id_reuse_do_not_mutate_quotes_or_reserve_inventory(self):
        original = self.search().json()
        for _ in range(4):
            self.assertEqual(self.search().json(), original)
        body = deepcopy(REQUEST)
        body["filters"]["radiusMeters"] = 700  # Same trace ID, different legitimate search.
        self.assertEqual(self.search(body).json()["quotes"], [])
        self.assertEqual(self.search().json(), original)
        supplier = self.client.get("/mock-supplier/v1/bookings/SUP-BOOK-001", headers=AUTH).json()
        self.assertEqual(supplier["contractStatus"], "CONFIRMED")

    def test_invalid_date_order_and_nonpositive_occupancy_are_semantic_422(self):
        for checkin, checkout in (("2026-11-10", "2026-11-10"), ("2026-11-13", "2026-11-10")):
            body = deepcopy(REQUEST)
            body["stay"].update(checkInDate=checkin, checkOutDate=checkout)
            self.problem(self.search(body), 422, "INVALID_STAY_DATES")
        for key in ("adults", "rooms"):
            body = deepcopy(REQUEST)
            body["stay"][key] = 0
            self.problem(self.search(body), 422, "INVALID_OCCUPANCY")

    def test_schema_errors_are_flat_400_with_search_specific_code(self):
        bodies = []
        for parent in (None, "stay", "filters"):
            body = deepcopy(REQUEST)
            (body if parent is None else body[parent])["unknown"] = True
            bodies.append(body)
        for parent, key, values in (("stay", "adults", (True, 1.5, "2", None)),
                ("stay", "checkInDate", ("2026-02-30", "20261110", 20261110)),
                ("filters", "radiusMeters", (0, 20001, "3000", True)),
                ("filters", "currency", ("USD", None)),
                ("filters", "maxAverageNightlyAmount", (0, None, "300000"))):
            for value in values:
                body = deepcopy(REQUEST)
                body[parent][key] = value
                bodies.append(body)
        for key in REQUEST:
            body = deepcopy(REQUEST)
            del body[key]
            bodies.append(body)
        for body in bodies + [[], 1, "invalid", None]:
            with self.subTest(body=body):
                self.problem(self.search(body), 400, "INVALID_SEARCH_REQUEST")
        response = self.client.post(PATH, content="{", headers={**AUTH, "Content-Type": "application/json"})
        self.problem(response, 400, "INVALID_SEARCH_REQUEST")

    def test_supplier_search_failure_is_controlled_and_does_not_break_reconfirmation(self):
        with TestClient(create_app(service_token=TOKEN, availability_enabled=False)) as client:
            self.problem(client.post(PATH, json=REQUEST, headers=AUTH), 503, "SUPPLIER_UNAVAILABLE")
            response = client.get("/mock-supplier/v1/bookings/SUP-BOOK-001", headers=AUTH)
            self.assertEqual(response.status_code, 200)

    def test_unexpected_search_failure_returns_sanitized_500(self):
        with patch("app.main.search_quotes", side_effect=RuntimeError("private-details")):
            with TestClient(create_app(service_token=TOKEN), raise_server_exceptions=False) as client:
                response = client.post(PATH, json=REQUEST, headers=AUTH)
                self.assertEqual(response.status_code, 500)
                self.assertEqual(response.json()["code"], "INTERNAL_ERROR")
                self.assertNotIn("private-details", response.text)


if __name__ == "__main__":
    unittest.main()
