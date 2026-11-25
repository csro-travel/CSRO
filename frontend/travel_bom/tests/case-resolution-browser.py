"""Synthetic HTTP browser checks for check-in, typed task completion and closure.

Run against a built frontend with RESOLUTION_BROWSER_BASE_URL (default3100).
No business/supplier persistence is inferred from these intercepted responses.
"""
import copy
import importlib.util
import os
from pathlib import Path
import sys
from urllib.parse import urlsplit

from playwright.sync_api import expect, sync_playwright

spec = importlib.util.spec_from_file_location("replacement_browser", Path(__file__).with_name("case-replacements-browser.py"))
fixtures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixtures)
BASE = os.environ.get("RESOLUTION_BROWSER_BASE_URL", "http://127.0.0.1:3100")
ARRIVAL = "2026-11-10T15:00:00+09:00"
INTERNAL_NOTE = "상담사 내부 후속 메모: 시연용 원예약 조치 불필요 확인. 실제 취소·정산을 실행하지 않음"


def run():
    tasks = [{"taskId": f"TASK-{index}", "type": kind, "status": "TODO", "completionNote": None, "completedAt": None, "followupOutcome": None}
             for index, kind in enumerate(["CONTACT_CUSTOMER", "CHECKIN_CONFIRM", "ORIGINAL_FOLLOWUP"], 1)]
    state = {"version": 2, "clock": fixtures.NOW, "checkin": None, "closure": None, "tasks": tasks,
             "writes": [], "receipts": {}, "customer_requests": []}

    def summary(counselor=False):
        public_closure = None if not state["closure"] else {key: value for key, value in state["closure"].items() if key not in ("caseId", "reason", "version")}
        result = {"caseStatus": "CLOSED" if state["closure"] else "OPEN", "version": state["version"], "checkin": state["checkin"],
                  "closure": public_closure, "effectiveAt": state["clock"], "clockMode": "SYNTHETIC_DEMO", "isSynthetic": True}
        if counselor:
            dates = ["2026-11-10", "2026-11-11", "2026-11-12"]
            blockers = [] if state["checkin"] else [{"code": "CHECKIN_MISSING"}]
            blockers += [{"code": "REQUIRED_TASK_INCOMPLETE", "taskId": task["taskId"]} for task in tasks if task["status"] != "DONE"]
            result.update(coverage={"fullyCovered": state["checkin"] is not None, "requiredStayDates": dates,
                                    "coveredStayDates": dates if state["checkin"] else [], "uncoveredStayDates": [] if state["checkin"] else dates},
                          requiredTasks=copy.deepcopy(tasks), canClose=not blockers and not state["closure"], blockers=blockers)
            if state["closure"]:
                result["closure"] = {**public_closure, "reason": state["closure"]["reason"], "version": state["closure"]["version"]}
        return result

    with sync_playwright() as driver:
        launch = {"args": ["--no-sandbox"]}
        if os.environ.get("CHROMIUM_PATH") or sys.platform != "win32":
            launch["executable_path"] = os.environ.get("CHROMIUM_PATH", "/usr/bin/chromium")
        else:
            launch["channel"] = "chrome"
        browser = driver.chromium.launch(**launch)
        context = browser.new_context(viewport={"width": 1366, "height": 768})
        customer = None
        try:
            def route_api(route):
                request = route.request
                path = urlsplit(request.url).path
                if customer is not None and request.frame.page == customer:
                    state["customer_requests"].append((request.method, path))
                if request.method in ("POST", "PATCH"):
                    body, key = request.post_data_json, request.headers["idempotency-key"]
                    state["writes"].append((request.method, path, key, body))
                    if key in state["receipts"]:
                        receipt = state["receipts"][key]
                        return route.fulfill(status=receipt[0], json=receipt[1])
                    if path.endswith("/checkin-confirmations"):
                        assert body == {"checkedIn": True}
                        assert state["clock"] == ARRIVAL, "Client must not confirm future check-in using its PC date"
                        state["version"] += 1
                        state["checkin"] = {"checkinConfirmationId": "CHECKIN-ui", "replacementBookingId": fixtures.ID_A,
                            "status": "CUSTOMER_CONFIRMED", "checkinAt": ARRIVAL, "isSynthetic": True, "clockMode": "SYNTHETIC_DEMO"}
                        tasks[1].update(status="DONE", completionNote="시연 고객 직접 확인에 연결됨", completedAt=ARRIVAL)
                        receipt = {"caseId": "C-029", **state["checkin"]}
                        state["receipts"][key] = (201, receipt)
                        return route.fulfill(status=502)
                    if body["expectedVersion"] != state["version"]:
                        return route.fulfill(status=409, json={"code": "CASE_VERSION_CONFLICT", "currentVersion": state["version"], "detail": "사건 버전이 바뀌었습니다."})
                    if "/tasks/" in path:
                        task = next(task for task in tasks if task["taskId"] == path.rsplit("/", 1)[1])
                        assert task["type"] != "CHECKIN_CONFIRM"
                        if task["type"] == "ORIGINAL_FOLLOWUP":
                            assert body["followupOutcome"] == "NOT_REQUIRED"
                        state["version"] += 1
                        task.update(status="DONE", completionNote=body["completionNote"], completedAt=ARRIVAL,
                                    followupOutcome=body.get("followupOutcome"))
                        receipt = {"caseId": "C-029", "taskId": task["taskId"], "status": "DONE", "completionNote": body["completionNote"], "completedAt": ARRIVAL, "version": state["version"]}
                        state["receipts"][key] = (200, receipt)
                        return route.fulfill(status=502 if task["type"] == "CONTACT_CUSTOMER" else 200, json=receipt)
                    assert path.endswith("/closures") and summary(True)["canClose"]
                    state["version"] += 1
                    state["closure"] = {"closureId": "CLOSE-ui", "caseId": "C-029", "status": "CLOSED", "reason": body["reason"],
                                        "closedAt": ARRIVAL, "version": state["version"], "isSynthetic": True, "clockMode": "SYNTHETIC_DEMO"}
                    state["receipts"][key] = (201, copy.deepcopy(state["closure"]))
                    return route.fulfill(status=502)
                if path.startswith("/demo/replacement-bookings/"):
                    return route.fulfill(json={**fixtures.booking(status="CONFIRMED"), "effectiveAt": state["clock"]})
                if path == "/demo/cases":
                    items = [] if state["closure"] else [{"caseId": "C-029", "bookingId": "YB-001", "hotelName": "원래 호텔", "priority": "URGENT", "status": "OPEN", "summary": "입실·후속 확인 필요", "nextAction": None}]
                    return route.fulfill(json={"items": items, "pagination": {"page": 0, "size": 20, "totalElements": len(items), "hasNext": False}})
                if path.endswith("/messages"):
                    return route.fulfill(json={"caseId": "C-029", "items": [fixtures.offer()], "activeOfferId": "OFFER-1", "currentSelection": fixtures.choice(),
                        "effectiveAt": state["clock"], "clockMode": "SYNTHETIC_DEMO", "latestReplacementBooking": {"replacementBookingId": fixtures.ID_A, "selectionId": "SEL-1"},
                        "resolution": summary(), "pagination": {"hasMoreBefore": False, "nextBeforeMessageId": None}})
                return route.fulfill(json={"caseId": "C-029", "status": "CLOSED" if state["closure"] else "OPEN", "version": state["version"],
                    "booking": {"bookingId": "YB-001", "hotel": {"name": "원래 호텔"}, "supplierStatus": "CONFIRMED"}, "nights": [], "evidence": [],
                    "customerReports": [], "nextAction": None, "resolution": summary(True)})

            context.route("**/demo/**", route_api)
            counselor = context.new_page()
            counselor.goto(BASE + "/cases")
            counselor.get_by_role("button", name="C-029 사건 열기", exact=True).click()
            resolution = counselor.get_by_role("region", name="입실·후속 업무·사건 종료", exact=True)
            expect(resolution.get_by_role("button", name="사건 종료", exact=True)).to_be_disabled()
            expect(resolution.get_by_text("고객 본인의 직접 입실 확인으로만 완료됩니다.", exact=False)).to_be_visible()
            customer = context.new_page()
            customer.goto(BASE + "/customer/cases/C-029")
            checkin = customer.get_by_role("region", name="고객 입실 확인", exact=True)
            expect(checkin).to_have_attribute("data-resolution-state", "AWAITING_CHECKIN")
            expect(checkin.get_by_role("button", name="입실 확인", exact=True)).to_be_disabled()
            expect(checkin.get_by_text("체크아웃일 제외", exact=False)).to_be_visible()
            assert state["writes"] == []
            state["clock"] = ARRIVAL
            customer.get_by_role("button", name="새 메시지 확인", exact=True).click()
            expect(checkin).to_have_attribute("data-resolution-state", "READY_FOR_CHECKIN")
            checkin.get_by_role("checkbox", name="시연 상황에서 숙소에 입실했음을 확인했습니다", exact=True).check()
            checkin.get_by_role("button", name="입실 확인", exact=True).click(click_count=2)
            expect(checkin.get_by_text("처리 결과를 확인하지 못했습니다.", exact=False)).to_be_visible()
            customer.reload()
            expect(checkin).to_have_attribute("data-resolution-state", "CHECKED_IN")
            checkin.get_by_role("button", name="같은 요청으로 처리 결과 확인", exact=True).click()
            expect(checkin.get_by_text("처리 응답을 확인했습니다.", exact=False)).to_be_visible()
            checkin_writes = [item for item in state["writes"] if item[1].endswith("/checkin-confirmations")]
            assert len(checkin_writes) == 2 and checkin_writes[0][2:] == checkin_writes[1][2:]
            assert state["version"] == 3

            # The counselor still holds version2: refresh and explicit review are required after409.
            note = resolution.get_by_role("textbox", name="고객 연락 완료 메모", exact=True)
            note.fill("가상 예약번호와 3박 기간을 고객에게 안내함")
            resolution.get_by_role("button", name="고객 연락 완료 기록", exact=True).click()
            expect(resolution.get_by_role("button", name="최신 상태를 확인했습니다", exact=True)).to_be_visible()
            expect(resolution).to_have_attribute("data-resolution-version", "3")
            expect(resolution.get_by_role("button", name="고객 연락 완료 기록", exact=True)).to_be_disabled()
            resolution.get_by_role("button", name="최신 상태를 확인했습니다", exact=True).click()
            resolution.get_by_role("button", name="고객 연락 완료 기록", exact=True).click()
            expect(resolution.get_by_text("처리 결과를 확인하지 못했습니다.", exact=False)).to_be_visible()
            counselor.reload()
            counselor.get_by_role("button", name="처리 결과 확인이 필요한 C-029 열기", exact=True).click()
            resolution.get_by_role("button", name="같은 요청으로 처리 결과 확인", exact=True).click()
            expect(resolution.get_by_text("처리 응답을 확인했습니다.", exact=False)).to_be_visible()
            patches = [item for item in state["writes"] if item[1].endswith("/tasks/TASK-1")]
            assert len(patches) == 3 and patches[0][3]["expectedVersion"] == 2
            assert patches[1][2:] == patches[2][2:] and patches[1][3]["expectedVersion"] == 3 and patches[0][2] != patches[1][2]
            assert state["version"] == 4
            original = resolution.locator('[data-resolution-task-id="TASK-3"]')
            original.get_by_role("textbox", name="원예약 후속 업무 완료 메모", exact=True).fill(INTERNAL_NOTE)
            outcome = original.get_by_role("combobox", name="원예약 후속 업무 후속 처리 결과", exact=True)
            assert outcome.locator("option").all_text_contents() == ["확인한 결과를 선택하세요", "시연·수동 조치 완료 확인", "조치 불필요 사유 확인"]
            outcome.select_option("NOT_REQUIRED")
            original.get_by_role("checkbox", name="시연·수동 후속 조치 기록임을 확인했습니다", exact=True).check()
            original.get_by_role("button", name="원예약 후속 업무 완료 기록", exact=True).click()
            expect(resolution).to_have_attribute("data-resolution-state", "READY_TO_CLOSE")
            expect(resolution.get_by_text("필요한 전체 숙박일이 보장됩니다.", exact=True)).to_be_visible()
            assert state["version"] == 5
            reason = "고객 입실·전체 숙박일 보장·시연 후속 업무 확인 완료"
            resolution.get_by_role("textbox", name="사건 종료 사유", exact=True).fill(reason)
            resolution.get_by_role("checkbox", name="입실·전 숙박일 보장·필수 업무 상태를 확인했습니다", exact=True).check()
            resolution.get_by_role("button", name="사건 종료", exact=True).click()
            expect(resolution.get_by_text("처리 결과를 확인하지 못했습니다.", exact=False)).to_be_visible()
            counselor.reload()
            expect(counselor.get_by_text("현재 처리할 상담 사건이 없습니다", exact=True)).to_be_visible()
            counselor.get_by_role("button", name="처리 결과 확인이 필요한 C-029 열기", exact=True).click()
            expect(resolution).to_have_attribute("data-resolution-state", "CLOSED")
            resolution.get_by_role("button", name="같은 요청으로 처리 결과 확인", exact=True).click()
            expect(resolution.get_by_text("처리 응답을 확인했습니다.", exact=False)).to_be_visible()
            expect(resolution.get_by_text("사건 종료 완료", exact=True)).to_be_visible()
            closures = [item for item in state["writes"] if item[1].endswith("/closures")]
            assert len(closures) == 2 and closures[0][2:] == closures[1][2:] and state["version"] == 6
            customer.get_by_role("button", name="새 메시지 확인", exact=True).click()
            expect(checkin).to_have_attribute("data-resolution-state", "CLOSED")
            expect(customer.get_by_text(INTERNAL_NOTE, exact=True)).to_have_count(0)
            expect(customer.get_by_text(reason, exact=True)).to_have_count(0)
            assert all((method == "GET" and (path.endswith("/messages") or path.startswith("/demo/replacement-bookings/"))) or
                       (method == "POST" and path.endswith("/checkin-confirmations")) for method, path in state["customer_requests"])
            assert tasks[1]["status"] == "DONE" and tasks[2]["followupOutcome"] == "NOT_REQUIRED"
            print("PASS server-date check-in gating; customer-only check-in; same-key recovery for check-in/task/closed-case closure; stale-version explicit review; typed followup; required-task/coverage close gating; customer memo privacy")
        finally:
            browser.close()


if __name__ == "__main__":
    run()
