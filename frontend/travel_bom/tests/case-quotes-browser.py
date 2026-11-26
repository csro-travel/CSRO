"""Quote UI contract checks using synthetic intercepted HTTP responses.

Start a built frontend first, then set QUOTE_BROWSER_BASE_URL if needed.
This verifies browser behavior, not Spring persistence or supplier inventory.
"""
import os
import sys
from urllib.parse import urlsplit

from playwright.sync_api import sync_playwright, expect


BASE = os.environ.get("QUOTE_BROWSER_BASE_URL", "http://127.0.0.1:3100")
CHECKED = "2026-11-09T10:15:00+09:00"
EXPIRES = "2026-11-09T10:25:00+09:00"


def quote_result(case_id="C-029", mode="full"):
    group = {
        "coverageMode": "FULL_STAY",
        "stay": {"checkInDate": "2026-11-10", "checkOutDate": "2026-11-13", "adults": 2, "rooms": 1},
        "remainingUnconfirmedStayDates": ["2026-11-11", "2026-11-12"],
        "rankingAvailable": True,
        "quotes": [{"quoteId": "QUOTE-A-ui", "propertyName": "가상 A호텔", "roomName": "더블",
                    "distanceMeters": 800, "totalAmount": 480000, "currency": "KRW", "priceDifference": 30000,
                    "checkedAt": CHECKED, "expiresAt": EXPIRES, "reason": "기존 호텔에서 가장 가까운 후보입니다."}],
        "warnings": ["견적 조회는 예약 확정이 아닙니다."],
    }
    result = {"caseId": case_id, "effectiveAt": "2026-11-09T10:20:00+09:00", "isSynthetic": True,
              "clockMode": "SYNTHETIC_DEMO", "searchJob": {"searchJobId": "QS-ui", "status": "COMPLETED", "errorCode": None},
              "groups": [group]}
    if mode == "none":
        result.update(effectiveAt=None, searchJob=None, groups=[])
    elif mode == "pending":
        result["searchJob"]["status"] = "PENDING"
        result["groups"] = []
    elif mode == "empty":
        group["quotes"] = []
    elif mode == "partial":
        group["coverageMode"] = "VERIFIED_FAILURE_ONLY"
        group["stay"]["checkOutDate"] = "2026-11-11"
        group["quotes"][0].update(quoteId="QUOTE-FIRST-ui", totalAmount=160000, priceDifference=None, reason=None)
    elif mode == "bad-partial":
        group["coverageMode"] = "VERIFIED_FAILURE_ONLY"
        group["stay"]["checkOutDate"] = "2026-11-11"
    elif mode == "expired":
        group["quotes"][0]["expired"] = True
    elif mode == "failed":
        result["searchJob"].update(status="FAILED", errorCode="SUPPLIER_UNAVAILABLE")
        result["groups"] = []
    elif mode == "invalid":
        result["caseId"] = "C-999"
    return result


def run():
    state = {"mode": "none", "posts": [], "held": None}
    with sync_playwright() as driver:
        executable = os.environ.get("CHROMIUM_PATH")
        launch = {"args": ["--no-sandbox"]}
        if executable or sys.platform != "win32":
            launch["executable_path"] = executable or "/usr/bin/chromium"
        else:
            launch["channel"] = "chrome"
        browser = driver.chromium.launch(**launch)
        try:
            page = browser.new_page(viewport={"width": 1100, "height": 900})

            def route_api(route):
                request = route.request
                path = urlsplit(request.url).path
                if path == "/demo/cases":
                    return route.fulfill(json={"items": [{"caseId": case, "bookingId": booking, "hotelName": name,
                                                         "priority": "URGENT", "status": "OPEN", "summary": "첫날 객실 제공 불가", "nextAction": None}
                                                        for case, booking, name in [("C-029", "YB-001", "원래 호텔"), ("C-030", "YB-002", "다른 호텔")]],
                                              "pagination": {"page": 0, "size": 20, "totalElements": 2, "hasNext": False}})
                case_id = path.split("/")[3]
                if path.endswith("/messages"):
                    return route.fulfill(json={"caseId": case_id, "items": [], "pagination": {"hasMoreBefore": False, "nextBeforeMessageId": None}})
                if path.endswith("/quote-searches"):
                    assert request.method == "POST"
                    assert request.post_data == "{}"
                    state["posts"].append(request.headers["idempotency-key"])
                    if len(state["posts"]) == 1:
                        return route.fulfill(status=502)
                    state["mode"] = "pending"
                    return route.fulfill(status=202, json={"caseId": case_id, "searchJobId": "QS-ui", "status": "PENDING"})
                if path.endswith("/quotes"):
                    assert request.method == "GET"
                    if state["mode"] == "delayed" and case_id == "C-029":
                        state["held"] = route
                        return
                    return route.fulfill(json=quote_result(case_id, state["mode"] if case_id == "C-029" else "none"))
                return route.fulfill(json={"caseId": case_id, "status": "OPEN", "booking": {"bookingId": "YB-001" if case_id == "C-029" else "YB-002",
                    "hotel": {"name": "원래 호텔" if case_id == "C-029" else "다른 호텔"}, "supplierStatus": "CONFIRMED"},
                    "nights": [], "evidence": [], "customerReports": [], "nextAction": None})

            page.route("**/demo/cases**", route_api)
            page.goto(BASE + "/cases")
            page.get_by_role("button", name="C-029 대응 건 열기", exact=True).click()
            page.get_by_role("tab", name="대체 숙소", exact=True).click()
            panel = page.get_by_role("region", name="대체 숙소 견적", exact=True)
            expect(panel.get_by_text("아직 검색하지 않았습니다. 대체 숙소 검색을 눌러 주세요.", exact=True)).to_be_visible()
            assert state["posts"] == [], "GET과 탭 조회만으로 검색을 접수하면 안 됩니다."
            panel.get_by_role("button", name="대체 숙소 검색", exact=True).click()
            expect(panel.get_by_role("alert")).to_contain_text("접수 결과를 확인하지 못했습니다")
            page.reload()
            page.get_by_role("button", name="C-029 대응 건 열기", exact=True).click()
            page.get_by_role("tab", name="대체 숙소", exact=True).click()
            panel.get_by_role("button", name="같은 요청으로 접수 확인", exact=True).click()
            expect(panel.get_by_text("검색 상태: 접수됨", exact=True)).to_be_visible()
            assert len(state["posts"]) == 2 and state["posts"][0] == state["posts"][1]
            expect(panel.locator("[data-quote-id]")).to_have_count(0)
            expect(panel.get_by_role("button", name="대체 숙소 검색", exact=True)).to_be_disabled()

            state["mode"] = "full"
            panel.get_by_role("button", name="새로고침", exact=True).click()
            expect(panel.locator("[data-quote-id=QUOTE-A-ui]")).to_be_visible()
            expect(panel.get_by_text("3박 총액 ₩480,000", exact=True)).to_be_visible()
            expect(panel.get_by_text("시연용 가상 판단 시각:", exact=False)).to_be_visible()
            expect(panel.get_by_text("동일 기간 원예약 대비 견적 차이:", exact=False)).to_be_visible()
            expect(panel.get_by_text("견적 유효 기한 경과 · 새 검색 필요", exact=True)).to_have_count(0)

            state["mode"] = "expired"
            panel.get_by_role("button", name="새로고침", exact=True).click()
            expect(panel.get_by_text("견적 유효 기한 경과 · 새 검색 필요", exact=True)).to_be_visible()

            state["mode"] = "partial"
            panel.get_by_role("button", name="새로고침", exact=True).click()
            expect(panel.get_by_text("장애가 확인된 숙박일의 후보", exact=True)).to_be_visible()
            expect(panel.get_by_text("1박 총액 ₩160,000", exact=True)).to_be_visible()
            expect(panel.get_by_text("동일 기간 원예약 대비 견적 차이:", exact=False)).to_have_count(0)
            expect(panel.get_by_text("원래 호텔 확인 필요:", exact=False)).to_contain_text("2026-11-11")
            for mode, message in [("empty", "조건에 맞는 대체 숙소 견적이 없습니다."), ("failed", "SUPPLIER_UNAVAILABLE"), ("invalid", "사건과 견적 응답을 확인하지 못했습니다."), ("bad-partial", "사건과 견적 응답을 확인하지 못했습니다.")]:
                state["mode"] = mode
                with page.expect_response("**/demo/cases/C-029/quotes"):
                    panel.get_by_role("button", name="새로고침", exact=True).click()
                expect(panel.get_by_role("button", name="새로고침", exact=True)).to_be_enabled()
                expect(panel.get_by_text(message, exact=False)).to_be_visible()
                expect(panel.locator("[data-quote-id]")).to_have_count(0)

            state["mode"] = "delayed"
            panel.get_by_role("button", name="새로고침", exact=True).click()
            page.get_by_role("button", name="← 상담 대응 목록", exact=True).click()
            page.get_by_role("button", name="C-030 대응 건 열기", exact=True).click()
            page.get_by_role("tab", name="대체 숙소", exact=True).click()
            expect(panel.get_by_role("heading", name="C-030 대체 숙소 견적", exact=True)).to_be_visible()
            if state["held"] is not None:
                try:
                    state["held"].fulfill(json=quote_result())
                except Exception:
                    pass  # The old case request is expected to have been aborted.
            expect(panel.get_by_text("아직 검색하지 않았습니다.", exact=False)).to_be_visible()
            expect(panel.locator("[data-quote-id]")).to_have_count(0)
            print("PASS quote states, same-key retry, partial-period amounts, read-only navigation and case isolation")
        finally:
            browser.close()


if __name__ == "__main__":
    run()
