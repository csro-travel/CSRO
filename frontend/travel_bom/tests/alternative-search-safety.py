"""Browser regression with synthetic booking/search HTTP responses only.

Start the built frontend separately. This does not test Google, MCP, Spring,
authentication, or business-DB integration. No application services are started.
"""
from __future__ import annotations

import asyncio
from copy import deepcopy
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import urlsplit

from playwright.async_api import async_playwright, expect

BASE = os.getenv("CSRO_ALT_SEARCH_TEST_URL", "http://127.0.0.1:18081").rstrip("/")
ORIGIN = "{0.scheme}://{0.netloc}".format(urlsplit(BASE))
ROUTE = "/alternative-search/C-002/YB-002"
SEARCH_BUTTON = re.compile(r"^(대체 숙소 자동 탐색|탐색 중\.\.\.)$")
BOOKING = {
    "bookingId": "YB-002",
    "hotel": {"hotelId": "SYNTHETIC-HOTEL-002", "name": "가상 서울 호텔"},
    "stay": {"checkInDate": "2099-12-10", "checkOutDate": "2099-12-13", "nights": 3, "adults": 2},
    "bookingStatus": "CONFIRMED",
    "originalPrice": {"amount": 450000, "currency": "KRW"},
    "support": {"activeCaseId": "C-002", "needsAttention": True},
}
RESULT = {
    "case_id": "C-002", "booking_id": "YB-002",
    "policy": {"check_in_date": "2099-12-10", "check_out_date": "2099-12-13",
               "nights": 3, "preferred_max_amount": 540000},
    "searched_radius_km": 20,
    "top_recommendations": [{
        "listing_id": "SYNTHETIC-STAY-002", "name": "가상 후보 A", "distance_km": 12.5,
        "total_price_krw": None, "checked_at": "2099-12-01T09:00:00+09:00",
        "unconfirmed_items": ["세금·수수료 포함 총액 미확인"],
    }],
}
CORS = {"Access-Control-Allow-Origin": ORIGIN,
        "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
        "Access-Control-Allow-Headers": "content-type, accept"}


async def fulfill(route, status, body):
    await route.fulfill(status=status, content_type="application/json", headers=CORS,
                        body=json.dumps(body, ensure_ascii=False))


def assert_search_payload(payload):
    assert payload == {
        "caseId": "C-002", "bookingId": "YB-002", "hotelName": "가상 서울 호텔",
        "nightlyPrice": 150000, "nights": 3, "adults": 2,
        "checkInDate": "2099-12-10", "checkOutDate": "2099-12-13",
    }, "Search must use the routed reservation, full period, and no invented region hint"


async def check_search_screen(browser):
    context = await browser.new_context(viewport={"width": 1440, "height": 1000})
    page = await context.new_page()
    page.set_default_timeout(20000)
    booking_seen, release_booking = asyncio.Event(), asyncio.Event()
    search_seen, release_search = asyncio.Event(), asyncio.Event()
    failure_seen, release_failure = asyncio.Event(), asyncio.Event()
    booking_requests, searches = [], []

    async def booking_response(route):
        if route.request.method == "OPTIONS":
            return await route.fulfill(status=204, headers=CORS)
        booking_requests.append(route.request)
        booking_seen.set()
        await release_booking.wait()
        await fulfill(route, 200, BOOKING)

    async def search_response(route):
        if route.request.method == "OPTIONS":
            return await route.fulfill(status=204, headers=CORS)
        assert route.request.method == "POST"
        payload = route.request.post_data_json
        assert_search_payload(payload)
        searches.append(payload)
        if len(searches) == 1:
            search_seen.set()
            await release_search.wait()
            return await fulfill(route, 200, RESULT)
        if len(searches) == 2:
            failure_seen.set()
            await release_failure.wait()
            return await fulfill(route, 502, {"detail": "[합성 검사] 공급사 응답을 확인할 수 없습니다."})
        result = deepcopy(RESULT)
        if len(searches) == 3:
            result["booking_id"] = "YB-OTHER"
        elif len(searches) == 4:
            result["policy"]["check_out_date"] = "2099-12-14"
        else:
            raise AssertionError("Unexpected duplicate search request")
        await fulfill(route, 200, result)

    await context.route("http://localhost:8080/api/v1/bookings/**", booking_response)
    await context.route("http://127.0.0.1:8000/api/v1/alternative-search/auto-search", search_response)
    try:
        await page.goto(BASE + ROUTE, wait_until="domcontentloaded")
        await asyncio.wait_for(booking_seen.wait(), timeout=20)
        button = page.get_by_role("button", name=SEARCH_BUTTON)
        await expect(button).to_be_disabled()
        assert len(booking_requests) == 1
        assert urlsplit(booking_requests[0].url).path == "/api/v1/bookings/YB-002"
        assert "x-demo-subject" not in booking_requests[0].headers
        release_booking.set()
        await expect(button).to_be_enabled()
        await expect(page.get_by_text("예약번호 YB-002", exact=True)).to_be_visible()

        await button.click()
        await asyncio.wait_for(search_seen.wait(), timeout=20)
        await expect(button).to_be_disabled()
        release_search.set()
        table = page.get_by_role("table")
        await expect(table.get_by_text("가상 후보 A", exact=True)).to_be_visible()
        await expect(table.get_by_text("금액 미확인", exact=True)).to_have_count(1)
        await expect(table.get_by_text("0원", exact=True)).to_have_count(0)
        comparison = page.get_by_role("heading", name="대체 숙소 비교 결과", exact=True).locator("..")
        summary = comparison.locator("dl")
        await expect(summary.get_by_text("2099-12-10 ~ 2099-12-13 · 3박", exact=True)).to_be_visible()
        await expect(summary.get_by_text("직선거리 20km", exact=True)).to_be_visible()
        await expect(page.get_by_text(re.compile("전달 완료|전달되었습니다"))).to_have_count(0)
        await expect(page.get_by_role("button", name="상담사에게 전달", exact=True)).to_have_count(0)

        await button.click()
        await asyncio.wait_for(failure_seen.wait(), timeout=20)
        await expect(button).to_be_disabled()
        await expect(table.get_by_text("가상 후보 A", exact=True)).to_have_count(0)
        release_failure.set()
        await expect(page.get_by_role("alert").filter(has_text="[합성 검사] 공급사 응답을 확인할 수 없습니다.")).to_be_visible()
        await expect(button).to_be_enabled()

        for _ in range(2):
            await button.click()
            await expect(page.get_by_role("alert").filter(has_text="검색 결과의 예약·기간·금액을 확인할 수 없습니다.")).to_be_visible()
            await expect(button).to_be_enabled()
            await expect(table.get_by_text("가상 후보 A", exact=True)).to_have_count(0)
        assert len(searches) == 4
    except Exception:
        await page.screenshot(path=str(Path(tempfile.gettempdir()) / "csro-alternative-search-safety-failure.png"),
                              full_page=True)
        raise
    finally:
        release_booking.set()
        release_search.set()
        release_failure.set()
        await context.close()


async def check_mismatched_case(browser):
    context = await browser.new_context()
    page = await context.new_page()
    page.set_default_timeout(20000)
    searches = []

    async def booking_response(route):
        data = deepcopy(BOOKING)
        data["support"]["activeCaseId"] = "C-999"
        await fulfill(route, 200, data)

    async def unexpected_search(route):
        searches.append(route.request.url)
        await route.abort()

    await context.route("http://localhost:8080/api/v1/bookings/**", booking_response)
    await context.route("http://127.0.0.1:8000/api/v1/alternative-search/auto-search", unexpected_search)
    try:
        await page.goto(BASE + ROUTE, wait_until="domcontentloaded")
        await expect(page.get_by_role("alert").filter(has_text="사건과 예약의 연결이 일치하지 않습니다.")).to_be_visible()
        await expect(page.get_by_role("button", name=SEARCH_BUTTON)).to_be_disabled()
        assert searches == [], "A mismatched case must not reach the search API"
    finally:
        await context.close()


async def main():
    executable = os.getenv("CSRO_BROWSER_EXECUTABLE")
    if not executable and os.name != "nt":
        executable = "/usr/bin/chromium"
    launch = {"headless": True}
    if executable:
        launch["executable_path"] = executable
    else:
        launch["channel"] = "chrome"
    if os.name != "nt":
        launch["args"] = ["--no-sandbox"]
    async with async_playwright() as playwright:
        browser = await playwright.chromium.launch(**launch)
        try:
            await check_search_screen(browser)
            await check_mismatched_case(browser)
        finally:
            await browser.close()
    print(json.dumps({"status": "passed", "syntheticApiFixtures": True,
                      "bookingId": "YB-002", "caseId": "C-002", "searchRequests": 4,
                      "checks": ["route identity", "loading guards", "no subject spoof header", "full stay",
                                 "unknown price", "actual search summary", "no fake handoff",
                                 "visible failure and stale-result removal", "mismatched response rejection",
                                 "mismatched case blocked"]}, ensure_ascii=False))


if __name__ == "__main__":
    asyncio.run(main())
