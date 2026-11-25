"""API-002 browser retry checks using intercepted synthetic HTTP; no business DB writes.

Start the built frontend and set INCIDENT_BROWSER_BASE_URL if not using port 3101.
The Spring ownership, durable job and transaction checks run separately.
"""
import os
import sys

from playwright.sync_api import expect, sync_playwright


BASE = os.environ.get("INCIDENT_BROWSER_BASE_URL", "http://127.0.0.1:3101")


def run():
    with sync_playwright() as driver:
        launch = {"args": ["--no-sandbox"]}
        if os.environ.get("CHROMIUM_PATH") or sys.platform != "win32":
            launch["executable_path"] = os.environ.get("CHROMIUM_PATH", "/usr/bin/chromium")
        else:
            launch["channel"] = "chrome"
        browser = driver.chromium.launch(**launch)
        try:
            context = browser.new_context()
            received = []

            def report(route):
                request = route.request
                received.append((request.headers.get("idempotency-key"), request.post_data_json))
                if len(received) == 1:
                    return route.fulfill(status=502)
                return route.fulfill(status=201, json={
                    "reportId": "IR-001", "bookingId": "YB-001", "chatMessageId": "MSG-001",
                    "case": {"caseId": "C-029", "created": True, "status": "OPEN", "priority": "URGENT"},
                    "verificationStatus": "PENDING", "receivedAt": "2026-11-25T12:00:00+09:00",
                })

            context.route("**/demo/bookings/YB-001/incident-reports", report)
            context.route("**/demo/cases/C-029/messages*", lambda route: route.fulfill(json={
                "caseId": "C-029", "items": [], "pagination": {"hasMoreBefore": False, "nextBeforeMessageId": None},
                "activeOfferId": None, "currentSelection": None, "latestReplacementBooking": None,
                "effectiveAt": "2026-11-25T12:00:00+09:00", "clockMode": "REAL_TIME",
            }))
            page = context.new_page()
            page.goto(BASE + "/customer/bookings/YB-001")
            page.get_by_role("checkbox").uncheck()
            page.get_by_role("textbox").fill("도착 전에 호텔에서 예약을 찾을 수 없다고 안내했습니다.")
            page.get_by_role("button", name="체크인 문제 접수", exact=True).click()
            expect(page.locator('main [role="alert"]')).to_contain_text("같은 요청으로 다시 확인")
            expect(page.get_by_role("textbox")).to_be_disabled()
            expect(page.get_by_role("checkbox")).to_be_disabled()
            assert len(received) == 1
            page.reload()
            expect(page.get_by_role("textbox")).to_be_disabled()
            page.get_by_role("button", name="같은 요청으로 접수 결과 확인", exact=True).click()
            expect(page).to_have_url(BASE + "/customer/cases/C-029")
            assert received[0] == received[1], "Reload after unknown outcome must preserve BOTH key and original body"
            assert received[0][1] == {"issueType": "CHECKIN_FAILED", "atHotel": False, "message": "도착 전에 호텔에서 예약을 찾을 수 없다고 안내했습니다."}
            assert page.evaluate("sessionStorage.getItem('csro.incident-intent.v1:YB-001')") is None
            context.close()

            # Browser storage failure must block a write that could not be replayed after reload.
            context = browser.new_context()
            context.add_init_script("Storage.prototype.setItem = function () { throw new Error('storage denied'); }")
            unexpected_posts = []
            context.route("**/demo/bookings/**", lambda route: (unexpected_posts.append(route.request.url), route.abort()))
            page = context.new_page()
            page.goto(BASE + "/customer/bookings/YB-001")
            page.get_by_role("textbox").fill("예약 확인이 필요합니다.")
            page.get_by_role("button", name="체크인 문제 접수", exact=True).click()
            expect(page.locator('main [role="alert"]')).to_contain_text("전송하지 않았습니다")
            assert unexpected_posts == []
            context.close()
        finally:
            browser.close()
    print("API-002 customer browser retry and storage checks: passed")


if __name__ == "__main__":
    run()
