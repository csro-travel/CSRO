"""Browser contract checks with synthetic intercepted HTTP; no business DB writes.

Start a built frontend, then run with OFFER_BROWSER_BASE_URL if not using port 3100.
Actual Spring persistence and role isolation are tested separately by integration tests.
"""
import os
import sys
from urllib.parse import parse_qs, urlsplit

from playwright.sync_api import expect, sync_playwright


BASE = os.environ.get("OFFER_BROWSER_BASE_URL", "http://127.0.0.1:3100")
CHECKED = "2026-11-09T10:15:00+09:00"
EFFECTIVE = "2026-11-09T10:20:00+09:00"
EXPIRES = "2026-11-09T10:25:00+09:00"


def quotes():
    return [{"quoteId": f"Q-{index}", "propertyId": f"H-{index if index < 4 else 1}",
             "propertyName": f"가상 {index}호텔", "roomName": "더블", "distanceMeters": index * 500,
             "totalAmount": 450000 + index * 10000, "currency": "KRW", "priceDifference": index * 10000,
             "checkedAt": CHECKED, "expiresAt": EXPIRES, "expired": False, "reason": None}
            for index in range(1, 5)]


def offer_message(offer_id="O-1", message_id="MSG-offer-1", partial=False):
    items = [{**quote, "displayOrder": index, "checkInDate": "2026-11-10", "checkOutDate": "2026-11-11" if partial else "2026-11-13",
              "adults": 2, "rooms": 1, "priceDifference": None if partial else quote["priceDifference"]}
             for index, quote in enumerate(quotes()[:3], 1)]
    return {"messageId": message_id, "senderType": "COUNSELOR", "messageType": "OFFER", "body": None,
            "sentAt": CHECKED, "offer": {"offerId": offer_id, "status": "ACTIVE", "items": items}}


def run():
    state = {"offer_posts": [], "selection_puts": [], "messages": [], "active": None,
             "choice": None, "effective": EFFECTIVE, "reads": [], "older": False, "held_selection": None}
    with sync_playwright() as driver:
        launch = {"args": ["--no-sandbox"]}
        if os.environ.get("CHROMIUM_PATH") or sys.platform != "win32":
            launch["executable_path"] = os.environ.get("CHROMIUM_PATH", "/usr/bin/chromium")
        else:
            launch["channel"] = "chrome"
        browser = driver.chromium.launch(**launch)
        context = browser.new_context(viewport={"width": 1300, "height": 1000})
        try:
            def route_api(route):
                request = route.request
                parsed = urlsplit(request.url)
                path = parsed.path
                if request.method == "GET":
                    state["reads"].append(path)
                if path == "/demo/cases":
                    return route.fulfill(json={"items": [{"caseId": "C-029", "bookingId": "YB-001", "hotelName": "원래 호텔", "priority": "URGENT", "status": "OPEN", "summary": "첫날 장애", "nextAction": None}],
                                               "pagination": {"page": 0, "size": 20, "totalElements": 1, "hasNext": False}})
                case_id = path.split("/")[3]
                if path.endswith("/messages"):
                    older = "beforeMessageId" in parse_qs(parsed.query)
                    return route.fulfill(json={"caseId": case_id, "items": state["messages"], "activeOfferId": state["active"],
                        "currentSelection": state["choice"], "effectiveAt": state["effective"], "clockMode": "SYNTHETIC_DEMO",
                        "pagination": {"hasMoreBefore": state["older"] and not older, "nextBeforeMessageId": "MSG-before" if state["older"] and not older else None}})
                if path.endswith("/quotes"):
                    return route.fulfill(json={"caseId": case_id, "effectiveAt": EFFECTIVE, "isSynthetic": True, "clockMode": "SYNTHETIC_DEMO",
                        "searchJob": {"searchJobId": "QS-ui", "status": "COMPLETED", "errorCode": None},
                        "groups": [{"coverageMode": "FULL_STAY", "stay": {"checkInDate": "2026-11-10", "checkOutDate": "2026-11-13", "adults": 2, "rooms": 1},
                                    "remainingUnconfirmedStayDates": [], "rankingAvailable": True, "quotes": quotes(), "warnings": []}]})
                if path.endswith("/offers"):
                    assert request.method == "POST"
                    state["offer_posts"].append((request.headers["idempotency-key"], request.post_data_json))
                    state.update(active="O-1", messages=[offer_message()])
                    if len(state["offer_posts"]) == 1:
                        return route.fulfill(status=502)
                    return route.fulfill(status=201, json={"caseId": case_id, "offerId": "O-1", "messageId": "MSG-offer-1", "sentAt": CHECKED})
                if path.endswith("/selection"):
                    assert request.method == "PUT"
                    state["selection_puts"].append((request.headers["idempotency-key"], request.post_data_json))
                    if state["held_selection"] == "hold":
                        state["held_selection"] = route
                        return
                    if len(state["selection_puts"]) == 1:
                        state["choice"] = {"selectionId": "SEL-1", "offerId": "O-1", "quoteId": "Q-1", "selectedAt": CHECKED}
                        return route.fulfill(status=502)
                    return route.fulfill(json={"caseId": case_id, "selectionId": "SEL-1", "offerId": "O-1", "quoteId": "Q-1", "selectedAt": CHECKED})
                return route.fulfill(json={"caseId": case_id, "status": "OPEN", "booking": {"bookingId": "YB-001", "hotel": {"name": "원래 호텔"}, "supplierStatus": "CONFIRMED"},
                    "nights": [], "evidence": [], "customerReports": [], "nextAction": None})

            context.route("**/demo/cases**", route_api)
            counselor = context.new_page()
            counselor.goto(BASE + "/cases")
            counselor.get_by_role("button", name="C-029 사건 열기", exact=True).click()
            counselor.get_by_role("tab", name="대체 숙소", exact=True).click()
            panel = counselor.get_by_role("region", name="대체 숙소 견적", exact=True)
            send = panel.get_by_role("button", name="선택한 3곳 고객에게 제안", exact=True)
            expect(send).to_be_disabled()
            panel.get_by_role("checkbox", name="가상 1호텔 고객 제안에 포함", exact=True).check()
            expect(panel.get_by_role("checkbox", name="가상 4호텔 고객 제안에 포함", exact=True)).to_be_disabled()
            panel.get_by_role("checkbox", name="가상 2호텔 고객 제안에 포함", exact=True).check()
            expect(send).to_be_disabled()
            panel.get_by_role("checkbox", name="가상 3호텔 고객 제안에 포함", exact=True).check()
            send.click()
            expect(panel.get_by_text("처리 결과를 확인하지 못했습니다.", exact=False)).to_be_visible()
            counselor.reload()
            counselor.get_by_role("button", name="C-029 사건 열기", exact=True).click()
            counselor.get_by_role("tab", name="대체 숙소", exact=True).click()
            panel.get_by_role("button", name="같은 요청으로 제안 전송 확인", exact=True).click()
            expect(panel.get_by_text("제안 전송 처리를 확인했습니다.", exact=False)).to_be_visible()
            assert state["offer_posts"][0] == state["offer_posts"][1]
            assert state["offer_posts"][0][1] == {"quoteIds": ["Q-1", "Q-2", "Q-3"]}
            chat = counselor.get_by_role("region", name="채팅", exact=True)
            expect(chat.get_by_text("동일 기간 원예약 대비 견적 차이:", exact=False)).to_have_count(3)

            state["reads"] = []
            customer = context.new_page()
            customer.goto(BASE + "/customer/cases/C-029")
            customer_chat = customer.get_by_role("region", name="채팅", exact=True)
            expect(customer.get_by_role("textbox")).to_have_count(0)
            select_first = customer.get_by_role("button", name="가상 1호텔 선택", exact=True)
            # Expiry uses the server's virtual judgment time, even on a later PC date.
            expect(select_first).to_be_enabled()
            select_first.click()
            expect(customer.get_by_text("처리 결과를 확인하지 못했습니다.", exact=False)).to_be_visible()
            customer.reload()
            state["choice"] = {"selectionId": "SEL-2", "offerId": "O-1", "quoteId": "Q-2", "selectedAt": CHECKED}
            customer.get_by_role("button", name="같은 요청으로 선택 결과 확인", exact=True).click()
            expect(customer_chat.get_by_text("현재 선택: 가상 2호텔 · 선택 완료 · 예약 진행 전", exact=True)).to_be_visible()
            assert state["selection_puts"][0] == state["selection_puts"][1], "Unknown selection must retain key AND body across reload"
            assert all(path.endswith("/messages") for path in state["reads"]), "Customer must not call counselor case/detail/quotes APIs"

            # An older-history read still carries authoritative current metadata.
            state["older"] = True
            customer.reload()
            expect(customer.get_by_role("button", name="이전 대화 불러오기", exact=True)).to_be_visible()
            state.update(active="O-2", choice=None, messages=[offer_message("O-2", "MSG-offer-2", partial=True)])
            customer.get_by_role("button", name="이전 대화 불러오기", exact=True).click()
            old_card = customer.locator('[data-chat-message-id="MSG-offer-1"]')
            expect(old_card.get_by_role("button", name="이전 제안 · 선택 불가", exact=True)).to_have_count(3)
            for button in old_card.get_by_role("button").all():
                expect(button).to_be_disabled()
            new_card = customer.locator('[data-chat-message-id="MSG-offer-2"]')
            expect(new_card.get_by_text("동일 기간 원예약 차액 비교 없음", exact=True)).to_have_count(3)
            state["effective"] = "2026-11-09T10:30:00+09:00"
            customer.get_by_role("button", name="새 메시지 확인", exact=True).click()
            expect(new_card.get_by_role("button", name="견적 기한 경과", exact=True)).to_have_count(3)
            for button in new_card.get_by_role("button").all():
                expect(button).to_be_disabled()
            print("PASS exact three distinct properties; same key/body retry across reload; server-authoritative selection/expiry; older-page metadata and cached-offer invalidation; customer read/selection-only UI")
        finally:
            browser.close()


if __name__ == "__main__":
    run()
