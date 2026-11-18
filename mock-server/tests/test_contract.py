"""API-026/027 HTTP contract tests, without Spring or a business database.

Authoritative v0.1 YB-001 examples:
- API-026 https://app.notion.com/p/3542f2597c57826e9b9881d07057b57e
- API-027 https://app.notion.com/p/77e2f2597c578365bd3c015167a69602

The YB-002..005 scenarios are implementation fixtures, not extra promises in
those Notion examples. These tests verify observable JSON/status contracts;
they do not establish that Spring saved or verified any hotel claim.
"""
import copy
import unittest

from fastapi.testclient import TestClient

from app.main import create_app


TOKEN = "contract-test-token"
AUTH = {"Authorization": f"Bearer {TOKEN}"}
SUPPLIER_PATH = "/mock-supplier/v1/bookings/"
HOTEL_PATH = "/mock-hotel/v1/reconfirmations"
STAY_DATES = ["2026-11-10", "2026-11-11", "2026-11-12"]
PROBLEM_KEYS = {"type", "title", "status", "detail", "code", "traceId"}

# Copied from the source examples independently of the app's fixture module.
EXPECTED_SUPPLIER_001 = {
    "supplierBookingId": "SUP-BOOK-001",
    "referenceBookingId": "YB-001",
    "propertyId": "HOTEL-001",
    "contractStatus": "CONFIRMED",
    "stay": {
        "checkInDate": "2026-11-10",
        "checkOutDate": "2026-11-13",
        "adults": 2,
        "rooms": 1,
    },
    "updatedAt": "2026-11-09T09:00:00+09:00",
}
EXPECTED_HOTEL_001 = {
    "reconfirmationRequestId": "RECHECK-001",
    "reconfirmationId": "RC-001",
    "status": "RESPONDED",
    "supplierBookingId": "SUP-BOOK-001",
    "propertyId": "HOTEL-001",
    "nights": [
        {
            "stayDate": "2026-11-10",
            "reportedProvisionStatus": "UNAVAILABLE",
            "message": "11월 10일에는 객실을 제공할 수 없습니다.",
        },
        {
            "stayDate": "2026-11-11",
            "reportedProvisionStatus": "UNKNOWN",
            "message": "확인 중입니다.",
        },
        {
            "stayDate": "2026-11-12",
            "reportedProvisionStatus": "UNKNOWN",
            "message": "확인 중입니다.",
        },
    ],
    "occurredAt": "2026-11-09T10:05:00+09:00",
    "sentAt": "2026-11-09T10:06:00+09:00",
}


def request_for(number=1, request_id="RECHECK-001"):
    return {
        "reconfirmationRequestId": request_id,
        "supplierBookingId": f"SUP-BOOK-{number:03d}",
        "propertyId": f"HOTEL-{number:03d}",
        "stayDates": list(STAY_DATES),
    }


class MockContractTests(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(create_app(service_token=TOKEN))
        self.addCleanup(self.client.close)

    def hotel(self, body=None, headers=None):
        return self.client.post(
            HOTEL_PATH,
            json=request_for() if body is None else body,
            headers=AUTH if headers is None else headers,
        )

    def supplier(self, number=1, headers=None):
        return self.client.get(
            SUPPLIER_PATH + f"SUP-BOOK-{number:03d}",
            headers=AUTH if headers is None else headers,
        )

    def assert_problem(self, response, status, code=None):
        self.assertEqual(response.status_code, status, response.text)
        self.assertEqual(
            response.headers["content-type"].split(";", 1)[0],
            "application/problem+json",
        )
        body = response.json()
        self.assertEqual(set(body), PROBLEM_KEYS)
        self.assertEqual(body["type"], "about:blank")
        self.assertEqual(body["status"], status)
        for field in ("title", "detail", "code", "traceId"):
            self.assertIsInstance(body[field], str)
            self.assertTrue(body[field].strip(), field)
        if code is not None:
            self.assertEqual(body["code"], code)
        # FastAPI's default nested validation-error detail must not escape.
        self.assertNotIsInstance(body["detail"], (list, dict))
        return body

    def test_api026_matches_authoritative_yb001_example_exactly(self):
        response = self.supplier()
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(response.headers["content-type"].split(";", 1)[0], "application/json")
        self.assertEqual(response.json(), EXPECTED_SUPPLIER_001)

    def test_api027_matches_authoritative_yb001_example_exactly(self):
        response = self.hotel()
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(response.headers["content-type"].split(";", 1)[0], "application/json")
        self.assertEqual(response.json(), EXPECTED_HOTEL_001)

    def test_both_routes_require_valid_internal_bearer(self):
        for headers in ({}, {"Authorization": "Bearer wrong-token"},
                        {"Authorization": TOKEN}, {"Authorization": "Basic abc"}):
            with self.subTest(headers=headers):
                self.assert_problem(self.supplier(headers=headers), 401, "UNAUTHENTICATED_SERVICE")
                self.assert_problem(self.hotel(headers=headers), 401, "UNAUTHENTICATED_SERVICE")

    def test_header_name_is_case_insensitive(self):
        headers = {"authorization": f"Bearer {TOKEN}"}
        self.assertEqual(self.supplier(headers=headers).status_code, 200)
        self.assertEqual(self.hotel(headers=headers).status_code, 200)

    def test_supplier_and_hotel_booking_ids_have_distinct_namespaces(self):
        response = self.client.get(SUPPLIER_PATH + "YB-001", headers=AUTH)
        self.assert_problem(response, 404, "SUPPLIER_BOOKING_NOT_FOUND")

    def test_unknown_supplier_id_returns_source_specific_not_found(self):
        self.assert_problem(self.supplier(999), 404, "SUPPLIER_BOOKING_NOT_FOUND")
        self.assert_problem(self.hotel(request_for(999)), 404, "HOTEL_RESERVATION_NOT_FOUND")

    def test_hotel_claim_does_not_change_supplier_contract(self):
        before = self.supplier().json()
        hotel = self.hotel()
        self.assertEqual(hotel.status_code, 200)
        self.assertEqual(hotel.json()["nights"][0]["reportedProvisionStatus"], "UNAVAILABLE")
        after = self.supplier().json()
        self.assertEqual(after, before)
        self.assertEqual(after["contractStatus"], "CONFIRMED")
        self.assertNotIn("fulfillmentStatus", hotel.json()["nights"][0])
        self.assertNotIn("caseId", hotel.json())

    def test_request_id_round_trips_without_inventing_a_new_schema(self):
        response = self.hotel(request_for(request_id="RECHECK-INDEPENDENT-123"))
        expected = copy.deepcopy(EXPECTED_HOTEL_001)
        expected["reconfirmationRequestId"] = "RECHECK-INDEPENDENT-123"
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), expected)

    def test_same_unique_date_set_is_accepted_and_returned_in_canonical_order(self):
        body = request_for()
        body["stayDates"] = list(reversed(STAY_DATES))
        response = self.hotel(body)
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(response.json(), EXPECTED_HOTEL_001)

    def test_repeat_requests_return_fixed_scenario_and_do_not_cancel_booking(self):
        self.assertEqual(self.supplier().json(), self.supplier().json())
        first = self.hotel()
        second = self.hotel()
        self.assertEqual(first.status_code, 200)
        self.assertEqual(second.status_code, 200)
        self.assertEqual(first.json(), second.json())
        self.assertEqual(self.supplier().json(), EXPECTED_SUPPLIER_001)

    def test_yb002_implementation_fixture_is_normal_for_all_nights(self):
        supplier = self.supplier(2)
        response = self.hotel(request_for(2))
        self.assertEqual(supplier.status_code, 200, supplier.text)
        self.assertEqual(supplier.json()["referenceBookingId"], "YB-002")
        self.assertEqual(supplier.json()["contractStatus"], "CONFIRMED")
        self.assertEqual(response.status_code, 200, response.text)
        body = response.json()
        self.assertEqual(set(body), set(EXPECTED_HOTEL_001))
        self.assertEqual(body["supplierBookingId"], "SUP-BOOK-002")
        self.assertEqual(body["propertyId"], "HOTEL-002")
        self.assertEqual(body["status"], "RESPONDED")
        self.assertEqual([night["stayDate"] for night in body["nights"]], STAY_DATES)
        self.assertEqual([night["reportedProvisionStatus"] for night in body["nights"]], ["AVAILABLE"] * 3)
        for night in body["nights"]:
            self.assertEqual(set(night), {"stayDate", "reportedProvisionStatus", "message"})
            self.assertIsInstance(night["message"], str)
            self.assertTrue(night["message"])

    def test_yb003_fixture_keeps_contract_confirmed_when_hotel_cannot_find_booking(self):
        self.assertEqual(self.supplier(3).status_code, 200)
        self.assertEqual(self.supplier(3).json()["contractStatus"], "CONFIRMED")
        self.assert_problem(self.hotel(request_for(3)), 404, "HOTEL_RESERVATION_NOT_FOUND")
        self.assertEqual(self.supplier(3).json()["contractStatus"], "CONFIRMED")

    def test_yb004_pending_fixture_has_only_v01_202_fields(self):
        response = self.hotel(request_for(4, "RECHECK-PENDING-004"))
        self.assertEqual(response.status_code, 202, response.text)
        self.assertEqual(response.json(), {
            "reconfirmationRequestId": "RECHECK-PENDING-004",
            "reconfirmationId": "RC-004",
            "status": "PENDING",
            "receivedAt": "2026-11-09T10:05:00+09:00",
        })
        self.assertEqual(self.supplier(4).json()["contractStatus"], "CONFIRMED")

    def test_yb005_unavailable_fixture_is_a_service_error_not_a_booking_status(self):
        self.assert_problem(self.supplier(5), 503, "SUPPLIER_UNAVAILABLE")
        self.assert_problem(self.hotel(request_for(5)), 503)

    def test_stateless_fixture_does_not_claim_request_id_collision_management(self):
        # A durable/idempotent request store is explicitly outside this mock step.
        first = self.hotel(request_for(1, "REUSED-TEST-ID"))
        second = self.hotel(request_for(2, "REUSED-TEST-ID"))
        self.assertEqual(first.status_code, 200)
        self.assertEqual(second.status_code, 200)
        self.assertEqual(second.json()["supplierBookingId"], "SUP-BOOK-002")
        self.assertEqual(second.json()["reconfirmationRequestId"], "REUSED-TEST-ID")

    def test_valid_but_mismatched_property_is_422(self):
        body = request_for()
        body["propertyId"] = "HOTEL-002"
        self.assert_problem(self.hotel(body), 422)

    def test_valid_but_wrong_stay_date_set_is_422(self):
        wrong_sets = [STAY_DATES[:2], [*STAY_DATES, "2026-11-13"],
                      ["2026-11-11", "2026-11-12", "2026-11-13"]]
        for dates in wrong_sets:
            with self.subTest(dates=dates):
                body = request_for()
                body["stayDates"] = dates
                self.assert_problem(self.hotel(body), 422)

    def test_empty_and_duplicate_stay_dates_are_400(self):
        for dates in ([], [STAY_DATES[0], STAY_DATES[0], STAY_DATES[2]]):
            with self.subTest(dates=dates):
                body = request_for()
                body["stayDates"] = dates
                self.assert_problem(self.hotel(body), 400)

    def test_invalid_date_strings_are_400(self):
        for date_text in ("2026-02-30", "not-a-date", "2026-11-10T00:00:00+09:00"):
            with self.subTest(date=date_text):
                body = request_for()
                body["stayDates"][0] = date_text
                self.assert_problem(self.hotel(body), 400)

    def test_required_fields_and_extra_fields_are_rejected_as_400(self):
        for missing in request_for():
            with self.subTest(missing=missing):
                body = request_for()
                del body[missing]
                self.assert_problem(self.hotel(body), 400)
        body = request_for()
        body["customerId"] = "UNTRUSTED-EXTRA-FIELD"
        self.assert_problem(self.hotel(body), 400)

    def test_identifiers_are_nonblank_strict_strings(self):
        for field in ("reconfirmationRequestId", "supplierBookingId", "propertyId"):
            for value in ("", "   ", 123, True, None, {}, []):
                with self.subTest(field=field, value=value):
                    body = request_for()
                    body[field] = value
                    self.assert_problem(self.hotel(body), 400)

    def test_stay_dates_have_strict_array_and_item_types(self):
        for value in ("2026-11-10", 123, None, {}, [20261110], [1762732800.0], [None], [True]):
            with self.subTest(stayDates=value):
                body = request_for()
                body["stayDates"] = value
                self.assert_problem(self.hotel(body), 400)

    def test_malformed_json_and_non_object_bodies_are_flat_400_problems(self):
        malformed = self.client.post(HOTEL_PATH, content="{", headers={**AUTH, "Content-Type": "application/json"})
        self.assert_problem(malformed, 400)
        for value in (None, [], "not-an-object", 123):
            with self.subTest(body=value):
                response = self.client.post(HOTEL_PATH, json=value, headers=AUTH)
                self.assert_problem(response, 400)


if __name__ == "__main__":
    unittest.main(verbosity=2)
