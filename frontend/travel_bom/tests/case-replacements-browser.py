"""Synthetic HTTP browser checks for API-015/016 UX and client retry boundaries.

Start a built frontend; set REPLACEMENT_BROWSER_BASE_URL if not using port3100.
These intercepted responses do not verify Spring or supplier persistence.
"""
import os
import sys
from urllib.parse import urlsplit

from playwright.sync_api import expect, sync_playwright


BASE = os.environ.get("REPLACEMENT_BROWSER_BASE_URL", "http://127.0.0.1:3100")
NOW = "2026-11-09T10:20:00+09:00"
ID_A, ID_B = "BRQ-" + "a" * 64, "BRQ-" + "b" * 64


def choice(number=1):
    return {"selectionId": f"SEL-{number}", "offerId": f"OFFER-{number}", "quoteId": f"Q-{number}-1", "selectedAt": NOW}


def offer(number=1):
    return {"messageId": f"MSG-{number}", "senderType": "COUNSELOR", "messageType": "OFFER", "body": None, "sentAt": NOW,
            "offer": {"offerId": f"OFFER-{number}", "status": "ACTIVE", "items": [
                {"quoteId": f"Q-{number}-{index}", "displayOrder": index, "propertyName": f"가상 {number}-{index}호텔", "distanceMeters": 500,
                 "checkInDate": "2026-11-10", "checkOutDate": "2026-11-13", "adults": 2, "rooms": 1, "totalAmount": 480000,
                 "currency": "KRW", "priceDifference": 30000, "checkedAt": "2026-11-09T10:15:00+09:00", "expiresAt": "2026-11-09T10:25:00+09:00"}
                for index in range(1, 4)]}}


def booking(identifier=ID_A, number=1, status="PROCESSING"):
    confirmed = status == "CONFIRMED"
    failed = status == "FAILED"
    return {"replacementBookingId": identifier, "caseId": "C-029", "selectionId": f"SEL-{number}", "offerId": f"OFFER-{number}",
            "quoteId": f"Q-{number}-1", "status": status,
            "stay": {"checkInDate": "2026-11-10", "checkOutDate": "2026-11-13", "adults": 2, "rooms": 1},
            "expectedPrice": {"totalAmount": 480000, "currency": "KRW"}, "supplierBookingId": "ALT-UI" if confirmed else None,
            "confirmedPrice": {"totalAmount": 480000, "currency": "KRW"} if confirmed else None, "confirmedAt": NOW if confirmed else None,
            "failureCode": "PRICE_CHANGED" if failed else None, "currentPrice": {"totalAmount": 498000, "currency": "KRW"} if failed else None,
            "manualRequired": status == "RESULT_UNKNOWN", "nextCheckAt": None, "updatedAt": NOW,
            "isSynthetic": True, "effectiveAt": NOW, "clockMode": "SYNTHETIC_DEMO"}


def run():
    state = {"pointer": None, "choice": choice(), "active": "OFFER-1", "messages": [offer()], "posts": [], "records": {},
             "read_error": None, "customer_reads": [], "selection_writes": 0, "hold_customer": False, "held": None}
    with sync_playwright() as driver:
        launch = {"args": ["--no-sandbox"]}
        if os.environ.get("CHROMIUM_PATH") or sys.platform != "win32":
            launch["executable_path"] = os.environ.get("CHROMIUM_PATH", "/usr/bin/chromium")
        else:
            launch["channel"] = "chrome"
        browser = driver.chromium.launch(**launch)
        context = browser.new_context(viewport={"width": 1400, "height": 1000})
        customer = None
        try:
            def route_api(route):
                request = route.request
                path = urlsplit(request.url).path
                if customer is not None and request.frame.page == customer:
                    state["customer_reads"].append((request.method, path))
                if path.startswith("/demo/replacement-bookings/"):
                    assert request.method == "GET", "Status polling must never execute a write"
                    identifier = path.rsplit("/", 1)[1]
                    if state["hold_customer"] and identifier == ID_A and request.frame.page == customer:
                        state["held"] = route
                        return
                    if state["read_error"]:
                        return route.fulfill(status=state["read_error"], json={"detail": "상태를 조회할 수 없습니다."})
                    return route.fulfill(json=state["records"][identifier])
                if path == "/demo/cases":
                    return route.fulfill(json={"items": [{"caseId": "C-029", "bookingId": "YB-001", "hotelName": "원래 호텔", "priority": "URGENT", "status": "OPEN", "summary": "첫날 장애", "nextAction": None}],
                                               "pagination": {"page": 0, "size": 20, "totalElements": 1, "hasNext": False}})
                if path.endswith("/messages"):
                    return route.fulfill(json={"caseId": "C-029", "items": state["messages"], "activeOfferId": state["active"], "currentSelection": state["choice"],
                                               "effectiveAt": NOW, "clockMode": "SYNTHETIC_DEMO", "latestReplacementBooking": state["pointer"],
                                               "pagination": {"hasMoreBefore": False, "nextBeforeMessageId": None}})
                if path.endswith("/replacement-bookings"):
                    assert request.method == "POST"
                    state["posts"].append((request.headers["idempotency-key"], request.post_data_json))
                    if len(state["posts"]) == 1:
                        return route.fulfill(status=502)
                    number = 1 if request.post_data_json["selectionId"] == "SEL-1" else 2
                    identifier = ID_A if number == 1 else ID_B
                    state["pointer"] = {"replacementBookingId": identifier, "selectionId": f"SEL-{number}"}
                    state["records"][identifier] = booking(identifier, number)
                    if number == 2:
                        return route.fulfill(status=409, json={"detail": "이미 접수된 선택입니다.", "code": "SELECTION_ALREADY_REQUESTED", "replacementBookingId": identifier})
                    return route.fulfill(status=202, json={"caseId": "C-029", "selectionId": "SEL-1", "replacementBookingId": identifier, "acceptedAt": NOW})
                if path.endswith("/selection"):
                    state["selection_writes"] += 1
                    return route.fulfill(status=409)
                return route.fulfill(json={"caseId": "C-029", "status": "OPEN", "booking": {"bookingId": "YB-001", "hotel": {"name": "원래 호텔"}, "supplierStatus": "CONFIRMED"},
                                           "nights": [], "evidence": [], "customerReports": [], "nextAction": None})

            context.route("**/demo/**", route_api)
            counselor = context.new_page()
            counselor.goto(BASE + "/cases")
            counselor.get_by_role("button", name="C-029 대응 건 열기", exact=True).click()
            panel = counselor.get_by_role("region", name="대체 예약 처리", exact=True)
            request_button = panel.get_by_role("button", name="대체 예약 진행", exact=True)
            expect(request_button).to_be_enabled()
            request_button.click(click_count=2)
            expect(panel.get_by_text("예약 접수 결과를 확인하지 못했습니다.", exact=False)).to_be_visible()
            assert len(state["posts"]) == 1, "Double click must issue one intention"
            counselor.reload()
            counselor.get_by_role("button", name="C-029 대응 건 열기", exact=True).click()
            panel.get_by_role("button", name="같은 요청으로 예약 접수 확인", exact=True).click()
            expect(panel.locator('[data-replacement-status="PROCESSING"]')).to_be_visible()
            assert state["posts"][0] == state["posts"][1], "Unknown receipt must preserve key AND selection across reload"
            assert state["posts"][0][1] == {"selectionId": "SEL-1"}
            expect(counselor.get_by_text("현재 선택: 가상 1-1호텔 · 가상 예약 처리 중", exact=True)).to_be_visible()
            expect(request_button).to_be_disabled()

            customer = context.new_page()
            customer.goto(BASE + "/customer/cases/C-029")
            customer_panel = customer.get_by_role("region", name="대체 예약 처리", exact=True)
            expect(customer_panel.locator('[data-replacement-status="PROCESSING"]')).to_be_visible()
            expect(customer.get_by_role("button", name="대체 예약 진행", exact=True)).to_have_count(0)
            for button in customer.locator('[data-chat-message-id="MSG-1"]').get_by_role("button").all():
                expect(button).to_be_disabled()

            refresh = panel.get_by_role("button", name="예약 처리 상태 새로고침", exact=True)
            state["records"][ID_A] = booking(status="RESULT_UNKNOWN")
            refresh.click()
            expect(panel.locator('[data-replacement-status="RESULT_UNKNOWN"]')).to_be_visible()
            expect(panel.get_by_text("자동 확인을 마쳤습니다.", exact=False)).to_be_visible()
            counselor.set_viewport_size({"width": 1366, "height": 768})
            transcript = counselor.get_by_role("region", name="대화 내역", exact=True)
            assert transcript.bounding_box()["height"] >= 200, "Booking status must not collapse the chat transcript on laptop screens"
            for status in (404, 503):
                state["read_error"] = status
                refresh.click()
                expect(panel.get_by_role("alert")).to_contain_text("조회 오류는 예약 실패를 의미하지 않습니다")
                expect(panel.locator('[data-replacement-status="RESULT_UNKNOWN"]')).to_be_visible()
                expect(request_button).to_be_disabled()
            assert len(state["posts"]) == 2
            state["read_error"] = None
            state["records"][ID_A] = {**booking(status="CONFIRMED"), "confirmedPrice": {"totalAmount": 498000, "currency": "KRW"}}
            refresh.click()
            expect(panel.get_by_role("alert")).to_contain_text("응답을 확인하지 못했습니다")
            expect(panel.locator('[data-replacement-status="RESULT_UNKNOWN"]')).to_be_visible()
            state["records"][ID_A] = booking(status="CONFIRMED")
            refresh.click()
            expect(panel.locator('[data-replacement-status="CONFIRMED"]')).to_be_visible()
            expect(panel.get_by_text("가상 예약번호 ALT-UI", exact=False)).to_be_visible()
            expect(panel.get_by_text("실제 입실·원예약 후속 처리·대응 종료", exact=False)).to_be_visible()
            expect(counselor.get_by_text("현재 선택: 가상 1-1호텔 · 가상 예약 확정", exact=True)).to_be_visible()
            customer_panel.get_by_role("button", name="예약 처리 상태 새로고침", exact=True).click()
            expect(customer_panel.locator('[data-replacement-status="CONFIRMED"]')).to_be_visible()
            customer.reload()
            expect(customer_panel.locator('[data-replacement-status="CONFIRMED"]')).to_be_visible()

            state["records"][ID_A] = booking(status="FAILED")
            refresh.click()
            expect(panel.locator('[data-replacement-status="FAILED"]')).to_be_visible()
            expect(panel.get_by_text("확인된 변경 가격", exact=False)).to_be_visible()
            expect(request_button).to_be_disabled()
            customer_panel.get_by_role("button", name="예약 처리 상태 새로고침", exact=True).click()
            expect(customer_panel.locator('[data-replacement-status="FAILED"]')).to_be_visible()
            expect(customer.get_by_role("button", name="가상 1-2호텔 선택", exact=True)).to_be_disabled()

            # A new offer/selection can proceed while the previous FAILED receipt remains discoverable.
            state.update(choice=choice(2), active="OFFER-2", messages=[offer(), offer(2)])
            counselor.get_by_role("button", name="새 메시지 확인", exact=True).click()
            expect(request_button).to_be_enabled()
            customer.get_by_role("button", name="새 메시지 확인", exact=True).click()
            expect(customer.get_by_role("button", name="가상 2-2호텔 선택", exact=True)).to_be_enabled()
            state["hold_customer"] = True
            customer_panel.get_by_role("button", name="예약 처리 상태 새로고침", exact=True).click()
            expect(customer_panel.get_by_text("예약 처리 상태 확인 중…", exact=True)).to_be_visible()
            request_button.click()
            expect(panel.locator('[data-replacement-status="PROCESSING"]')).to_be_visible()
            expect(panel.get_by_text("이미 접수된 선택입니다.", exact=False)).to_be_visible()
            customer.get_by_role("button", name="새 메시지 확인", exact=True).click()
            expect(customer_panel.locator('[data-replacement-status="PROCESSING"]')).to_be_visible()
            if state["held"]:
                try:
                    state["held"].fulfill(json=booking(status="CONFIRMED"))
                except Exception:
                    pass  # The pointer change should abort the old GET.
            expect(customer_panel.get_by_text(ID_B, exact=False)).to_be_visible()
            expect(customer_panel.get_by_text("가상 예약번호 ALT-UI", exact=False)).to_have_count(0)
            assert len(state["posts"]) == 3 and state["selection_writes"] == 0
            assert all(method == "GET" and (path.endswith("/messages") or path.startswith("/demo/replacement-bookings/"))
                       for method, path in state["customer_reads"]), "Customer has read-only replacement access without case/detail calls"
            print("PASS double-click deduplication; same-key/body unknown retry after reload; immutable receipt/status separation; readonly status failure; all four booking states; failed-offer/new-selection gating; existing-selection discovery; stale GET cancellation; customer read-only results")
        finally:
            browser.close()


if __name__ == "__main__":
    run()
