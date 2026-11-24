import importlib
from pathlib import Path
import os
import sys
from types import ModuleType
import unittest
from unittest.mock import patch
from datetime import date, datetime, timedelta, timezone

from fastapi.testclient import TestClient

PACKAGE = "alternative_search_safety_tests"
if PACKAGE not in sys.modules:
    package = ModuleType(PACKAGE)
    package.__path__ = [str(Path(__file__).resolve().parents[1])]
    sys.modules[PACKAGE] = package

api = importlib.import_module(PACKAGE + ".main")
app_module = importlib.import_module(PACKAGE + ".app")
scoring = importlib.import_module(PACKAGE + ".scoring_service")
schemas = importlib.import_module(PACKAGE + ".schemas")
ROUTE = "/api/v1/alternative-search/auto-search"


class SearchHttpTests(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(app_module.app)
        clock = patch.object(scoring, "datetime")
        controlled_clock = clock.start()
        controlled_clock.now.return_value = datetime(2026, 11, 24, 9, tzinfo=timezone(timedelta(hours=9)))
        self.addCleanup(clock.stop)
        start = date(2026, 12, 24)
        self.payload = {
            "caseId": "C-123", "bookingId": "YB-456", "hotelName": "확인 대상 호텔",
            "nightlyPrice": 150000, "nights": 3, "adults": 2,
            "checkInDate": start.isoformat(), "checkOutDate": (start + timedelta(days=3)).isoformat(),
        }

    def test_health_works_without_google_credentials_or_external_calls(self):
        with patch.dict(os.environ, {"GOOGLE_MAPS_API_KEY": ""}), \
                patch.object(scoring, "GoogleMapsLocationAgent") as google:
            response = self.client.get("/health")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"status": "ok"})
        google.assert_not_called()

    def test_invalid_booking_period_is_client_error_before_location_lookup(self):
        for changes in (
            {"checkInDate": "2026-11-10", "checkOutDate": "2026-11-13"},
            {"checkInDate": None, "checkOutDate": None},
            {"nights": 1},
        ):
            payload = dict(self.payload, **changes)
            with self.subTest(changes=changes), \
                    patch.object(api, "search_service", scoring.AlternativeSearchService()), \
                    patch.object(scoring, "GoogleMapsLocationAgent") as google:
                response = self.client.post(ROUTE, json=payload)
                self.assertEqual(response.status_code, 400)
                self.assertIsInstance(response.json()["detail"], str)
                google.assert_not_called()

    def test_missing_provider_configuration_is_controlled_and_not_zero_candidates(self):
        with patch.dict(os.environ, {"GOOGLE_MAPS_API_KEY": ""}), \
                patch.object(api, "search_service", scoring.AlternativeSearchService()):
            response = self.client.post(ROUTE, json=self.payload)
        self.assertEqual(response.status_code, 502)
        self.assertNotIn("top_recommendations", response.json())

    def test_response_keeps_requested_case_booking_and_period(self):
        captured = {}

        class FakeService:
            async def run_auto_search(self, **arguments):
                captured.update(arguments)
                return schemas.AlternativeSearchResponse(
                    case_id=arguments["case_id"], booking_id=arguments["booking_id"],
                    origin=schemas.HotelOrigin(hotel_name=arguments["hotel_name"], address="확인 주소",
                        latitude=33.5, longitude=126.5, location_source="TEST_ONLY",
                        verified_at=datetime.now(timezone.utc)),
                    policy=schemas.SearchPolicy(check_in_date=arguments["check_in_date"],
                        check_out_date=arguments["check_out_date"], nights=3, adults=2,
                        original_total_amount=450000, preferred_max_amount=540000),
                    searched_radius_km=10, searched_candidate_count=0, verified_candidate_count=0)

        with patch.object(api, "search_service", FakeService()):
            response = self.client.post(ROUTE, json=self.payload)
        self.assertEqual(response.status_code, 200)
        value = response.json()
        self.assertEqual((value["case_id"], value["booking_id"]), ("C-123", "YB-456"))
        self.assertEqual(value["policy"]["check_in_date"], self.payload["checkInDate"])
        self.assertEqual(value["policy"]["check_out_date"], self.payload["checkOutDate"])
        self.assertEqual(captured["check_in_date"], date.fromisoformat(self.payload["checkInDate"]))

    def test_provider_failure_does_not_leak_exception_text_or_report_empty_success(self):
        class BrokenService:
            async def run_auto_search(self, **arguments):
                raise RuntimeError("sensitive-provider-request-url-and-token")

        with patch.object(api, "search_service", BrokenService()), \
                self.assertLogs(api.logger, level="ERROR") as captured:
            response = self.client.post(ROUTE, json=self.payload)
        self.assertEqual(response.status_code, 502)
        self.assertNotIn("sensitive-provider", response.text)
        self.assertNotIn("sensitive-provider", " ".join(captured.output))
        self.assertNotIn("top_recommendations", response.json())


if __name__ == "__main__":
    unittest.main()
