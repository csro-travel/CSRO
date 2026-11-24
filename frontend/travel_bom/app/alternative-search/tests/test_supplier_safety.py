"""Offline supplier failure and price/fact-boundary regressions; no MCP process."""
from contextlib import asynccontextmanager, redirect_stdout
from datetime import date, datetime, timezone
import asyncio
import importlib
import io
import json
from pathlib import Path
import sys
from types import ModuleType, SimpleNamespace
import unittest


PACKAGE = "alternative_supplier_safety_test"
if PACKAGE not in sys.modules:
    package = ModuleType(PACKAGE)
    package.__path__ = [str(Path(__file__).resolve().parents[1])]
    sys.modules[PACKAGE] = package
supplier = importlib.import_module(PACKAGE + ".airbnb_agent")
schemas = importlib.import_module(PACKAGE + ".schemas")


def price_item(text, *, details=None):
    return {"structuredDisplayPrice": {
        "primaryLine": {"accessibilityLabel": text},
        "explanationData": {"priceDetails": details},
    }}


def result(payload, *, error=False):
    return SimpleNamespace(isError=error, content=[SimpleNamespace(text=json.dumps(payload))])


def policy():
    return schemas.SearchPolicy(
        check_in_date=date(2030, 11, 10), check_out_date=date(2030, 11, 13), nights=3,
        adults=2, original_total_amount=450000, preferred_max_amount=540000,
    )


class FakeSession:
    def __init__(self, value):
        self.value = value

    async def call_tool(self, *_args, **_kwargs):
        return self.value


class PriceEvidenceTests(unittest.TestCase):
    def test_explicit_whole_stay_krw_totals(self):
        for text in ("total ₩300,000", "₩300,000 total", "총액 300,000원",
                     "총 금액 KRW 300000", "KRW 300,000 total"):
            with self.subTest(text=text):
                self.assertEqual(supplier._parse_total_price_krw(price_item(text)), 300000)

    def test_nightly_foreign_and_incomplete_prices_remain_unknown(self):
        for text in ("₩100,000 per night", "total $1,200", "total USD 1200",
                     "₩100,000", "total 300,000", "총액 ₩300,000 세금 별도",
                     "total ₩300,000 before taxes", "1박 총액 ₩100,000",
                     "total ₩100,00", "total ₩300,000.50"):
            with self.subTest(text=text):
                self.assertIsNone(supplier._parse_total_price_krw(price_item(text)))

    def test_conflicting_totals_are_not_silently_selected(self):
        self.assertIsNone(supplier._parse_total_price_krw(price_item(
            "total ₩300,000", details="total ₩350,000")))
        self.assertIsNone(supplier._parse_total_price_krw(price_item(
            "total ₩300,000, original total ₩350,000")))

    def test_structurally_unknown_price_is_not_a_zero_or_exception(self):
        for item in ({}, {"structuredDisplayPrice": []},
                     {"structuredDisplayPrice": {"primaryLine": "unknown", "explanationData": []}}):
            with self.subTest(item=item):
                self.assertIsNone(supplier._parse_total_price_krw(item))


class SupplierFailureTests(unittest.IsolatedAsyncioTestCase):
    async def test_tool_error_does_not_become_no_inventory_or_leak_raw_content(self):
        client = supplier.AirbnbMcpClient()
        output = io.StringIO()
        with redirect_stdout(output):
            with self.assertRaisesRegex(supplier.SupplierResponseError, "^SUPPLIER_TOOL_ERROR$"):
                await client.search_listings(FakeSession(result(
                    {"error": "private provider diagnostic"}, error=True)), location="제주", policy=policy())
        self.assertEqual(output.getvalue(), "")

    async def test_valid_empty_search_is_a_successful_empty_list(self):
        values = await supplier.AirbnbMcpClient().search_listings(
            FakeSession(result({"searchResults": []})), location="제주", policy=policy())
        self.assertEqual(values, [])

    async def test_invalid_search_payloads_fail_instead_of_reporting_zero_results(self):
        for payload in ({"error": "upstream unavailable"}, {"searchResults": None},
                        {"searchResults": {}}, {"searchResults": ["invalid"]}):
            with self.subTest(payload=payload):
                with self.assertRaises(supplier.SupplierResponseError):
                    await supplier.AirbnbMcpClient().search_listings(
                        FakeSession(result(payload)), location="제주", policy=policy())

    async def test_invalid_detail_response_is_a_supplier_failure(self):
        with self.assertRaisesRegex(supplier.SupplierResponseError, "DETAIL_RESPONSE_INVALID"):
            await supplier.AirbnbMcpClient().get_listing_details(
                FakeSession(result({"error": "no details"})), listing_id="test-1", policy=policy())

    async def test_tool_wait_is_bounded_and_does_not_return_empty_inventory(self):
        class BlockedSession:
            async def call_tool(self, *_args, **_kwargs):
                await asyncio.Event().wait()
        with self.assertRaisesRegex(supplier.SupplierResponseError, "^SUPPLIER_TIMEOUT$"):
            await supplier.AirbnbMcpClient(timeout_seconds=.01).search_listings(
                BlockedSession(), location="제주", policy=policy())

    async def test_transport_failure_uses_a_stable_error(self):
        class FailedSession:
            async def call_tool(self, *_args, **_kwargs):
                raise RuntimeError("private transport diagnostic")
        with self.assertRaisesRegex(supplier.SupplierResponseError, "^SUPPLIER_CALL_FAILED$"):
            await supplier.AirbnbMcpClient().search_listings(
                FailedSession(), location="제주", policy=policy())

    async def test_url_and_generic_policy_section_do_not_confirm_facts(self):
        class FakeClient:
            @asynccontextmanager
            async def session(self):
                yield object()

            async def search_listings(self, *_args, **_kwargs):
                return [{
                    "id": "test-listing", "name": "검토용 숙소",
                    "demandStayListing": {"location": {"coordinate": {"latitude": 33.5, "longitude": 126.5}}},
                    **price_item("total ₩300,000"),
                }]

            async def get_listing_details(self, *_args, **_kwargs):
                return {"listingUrl": "https://www.airbnb.com/rooms/test-listing", "details": [
                    {"id": "POLICIES_DEFAULT", "title": "체크인 15:00"},
                ]}

        origin = schemas.HotelOrigin(
            hotel_name="테스트 호텔", address="제주특별자치도 제주시", latitude=33.5,
            longitude=126.5, location_source="test", verified_at=datetime.now(timezone.utc),
        )
        values, count = await supplier.AirbnbAlternativeSearchAgent(FakeClient()).search_and_verify(
            origin=origin, policy=policy(), radius_km=10,
        )
        self.assertEqual(count, 1)
        self.assertEqual(len(values), 1)
        candidate = values[0]
        self.assertIsNone(candidate.address)
        self.assertEqual(candidate.checks.address_and_listing_verified, schemas.VerificationStatus.UNCONFIRMED)
        self.assertEqual(candidate.checks.cancellation_policy_verified, schemas.VerificationStatus.UNCONFIRMED)
        self.assertIn("주소 및 숙소 실재성", candidate.unconfirmed_items)
        self.assertIn("취소 조건", candidate.unconfirmed_items)
        self.assertIn("취소 규정 미확인", candidate.cancellation_summary)


if __name__ == "__main__":
    unittest.main()
