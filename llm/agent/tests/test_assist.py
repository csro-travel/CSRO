"""상담사 안내 초안: 규칙 기반 내용 경계와 언어 모델 폴백. 실제 외부 호출은 하지 않는다."""
import copy
import json
import socket
import time
import unittest
from threading import Thread
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import uvicorn

from app.assist import AssistRequest, generate, rule_based
from app.main import app


PATH = "/internal/v1/counselor-assist"
TOKEN = "counselor-assist-test-fixture"
EXAMPLE = {
    "caseId": "C-001", "kind": "GUIDANCE",
    "facts": {
        "hotelName": "제주 오션뷰 호텔", "bookingId": "YB-001",
        "checkInDate": "2026-11-10", "checkOutDate": "2026-11-13",
        "supplierStatus": "CONFIRMED", "caseStatus": "OPEN", "origin": "RISK_RECHECK",
        "nights": [
            {"stayDate": "2026-11-10", "fulfillmentStatus": "UNAVAILABLE", "factStatus": "VERIFIED"},
            {"stayDate": "2026-11-11", "fulfillmentStatus": "UNKNOWN", "factStatus": "UNCONFIRMED"},
            {"stayDate": "2026-11-12", "fulfillmentStatus": "AVAILABLE", "factStatus": "CONFLICT"},
        ],
        "openTasks": ["CONTACT_CUSTOMER"], "completedTasks": [], "customerReportCount": 0,
        "replacement": None, "checkinConfirmed": False, "closeReason": None,
    },
}
PROMISES = ("환불", "보상", "결제", "추가 부담")


def request(**facts):
    body = copy.deepcopy(EXAMPLE)
    body["kind"] = facts.pop("kind", "GUIDANCE")
    body["facts"].update(facts)
    return AssistRequest.model_validate(body)


def llm_reply(content):
    return {"choices": [{"message": {"content": content if isinstance(content, str) else json.dumps(content)}}]}


class RuleBasedTests(unittest.TestCase):
    def test_only_verified_nights_are_stated_as_facts(self):
        result = rule_based(request())
        self.assertEqual((result.source, result.model), ("RULES", None))
        self.assertEqual(result, rule_based(request()))
        draft = result.customerMessageDraft
        self.assertIn("11월 10일 숙박일은 호텔에서 객실 제공이 어려운 것으로 확인", draft)
        self.assertIn("11월 11일 숙박일은 아직 확인 중", draft)
        self.assertIn("11월 12일 숙박일은 확인 내용이 엇갈려", draft)
        self.assertIn("11월 12일 숙박일은 근거가 엇갈려 확인이 필요", result.summary)
        for text in (draft, result.summary):
            self.assertNotIn("가능", text)
            self.assertNotIn("정상", text)
        self.assertTrue(1 <= len(result.nextActions) <= 4)
        self.assertTrue(any("호텔에 직접" in action for action in result.nextActions))
        self.assertTrue(any("정상 제공이라고 안내하지" in warning for warning in result.warnings))

    def test_customer_draft_never_promises_money_and_confirmed_replacement_is_not_checkin(self):
        confirmed = {"status": "CONFIRMED", "propertyName": "A호텔"}
        for facts in ({}, {"replacement": confirmed}, {"replacement": {"status": "FAILED", "propertyName": "A호텔"}}):
            draft = rule_based(request(**facts)).customerMessageDraft
            for word in PROMISES:
                self.assertNotIn(word, draft)
        result = rule_based(request(replacement=confirmed))
        self.assertIn("입실하신 뒤 입실 확인을 부탁", result.customerMessageDraft)
        self.assertNotIn("입실이 완료", result.customerMessageDraft)
        self.assertTrue(any("예약 확정은 입실 완료가 아닙니다" in action for action in result.nextActions))

    def test_closure_summary_reports_handling_without_a_customer_draft(self):
        result = rule_based(request(
            kind="CLOSURE_SUMMARY", caseStatus="CLOSED", closeReason="REPLACEMENT_CHECKED_IN", openTasks=[],
            completedTasks=[{"type": "CONTACT_CUSTOMER", "note": "전화로 안내함"}], checkinConfirmed=True,
            replacement={"status": "CONFIRMED", "propertyName": "A호텔"}))
        self.assertIsNone(result.customerMessageDraft)
        self.assertEqual(result.nextActions, [])
        for part in ("11월 10일 숙박일의 객실 제공 불가가 확인", "대체 숙소 A호텔 예약이 확정", "고객 입실 확인이 기록",
                     "고객 안내(메모: 전화로 안내함)", "확인되지 않은 채 남아"):
            self.assertIn(part, result.summary)
        self.assertTrue(3 <= result.summary.count("니다.") <= 5)
        self.assertLessEqual(len(result.summary), 400)


class LanguageModelFallbackTests(unittest.TestCase):
    def test_valid_reply_is_adopted_and_only_structured_facts_are_sent(self):
        sent = []

        def transport(key, payload):
            sent.append((key, payload))
            return llm_reply({"summary": "요약입니다.", "nextActions": ["고객에게 안내하세요."],
                              "customerMessageDraft": "안녕하세요. 확인 중입니다."})

        with patch.dict("os.environ", {"ASSIST_LLM_MODEL": ""}):
            result = generate(request(), api_key="test-key", transport=transport)
        self.assertEqual((result.source, result.model, result.summary), ("LLM", "gpt-4o-mini", "요약입니다."))
        key, payload = sent[0]
        self.assertEqual(key, "test-key")
        self.assertEqual((payload["temperature"], payload["response_format"]), (0.2, {"type": "json_object"}))
        self.assertLessEqual(payload["max_tokens"], 600)
        self.assertEqual(json.loads(payload["messages"][1]["content"]), EXAMPLE["facts"])
        self.assertNotIn("test-key", json.dumps(payload))

    def test_invalid_or_failed_reply_falls_back_to_rules_without_leaking_details(self):
        expected = rule_based(request())
        invalid = [
            llm_reply("not json"),
            llm_reply({"summary": "요약", "nextActions": ["조치"]}),
            llm_reply({"summary": "가" * 401, "nextActions": ["조치"], "customerMessageDraft": "초안"}),
            llm_reply({"summary": "요약", "nextActions": ["조치"] * 6, "customerMessageDraft": "초안"}),
            llm_reply({"summary": "요약", "nextActions": [1], "customerMessageDraft": "초안"}),
            llm_reply({"summary": "요약", "nextActions": ["조치"], "customerMessageDraft": "초안", "refund": 1}),
            {"error": {"message": "secret upstream text"}},
        ]
        for reply in invalid:
            with self.subTest(reply=reply), self.assertLogs("app.assist", level="WARNING") as logs:
                result = generate(request(), api_key="test-key", transport=lambda key, payload: reply)
                self.assertEqual((result.source, result.model), ("RULES", None))
                self.assertEqual(result.customerMessageDraft, expected.customerMessageDraft)
                self.assertIn("형식 검증", result.warnings[0])
                self.assertNotIn("secret upstream text", "".join(logs.output) + result.model_dump_json())

        def failing(key, payload):
            raise TimeoutError("secret upstream text " + key)

        with self.assertLogs("app.assist", level="WARNING") as logs:
            result = generate(request(), api_key="test-key", transport=failing)
        self.assertEqual(result.source, "RULES")
        self.assertEqual(result.warnings[0], "언어 모델을 사용할 수 없어 규칙 기반으로 생성했습니다.")
        self.assertNotIn("test-key", "".join(logs.output) + result.model_dump_json())
        self.assertNotIn("secret upstream text", "".join(logs.output))

    def test_closure_summary_reply_must_not_carry_actions_or_a_draft(self):
        closure = request(kind="CLOSURE_SUMMARY", caseStatus="CLOSED", closeReason="REPLACEMENT_CHECKED_IN", openTasks=[])
        accepted = llm_reply({"summary": "요약입니다.", "nextActions": [], "customerMessageDraft": None})
        result = generate(closure, api_key="test-key", transport=lambda key, payload: accepted)
        self.assertEqual((result.source, result.nextActions, result.customerMessageDraft), ("LLM", [], None))
        for extra in ({"nextActions": ["조치"], "customerMessageDraft": None},
                      {"nextActions": [], "customerMessageDraft": "초안"}):
            reply = llm_reply({"summary": "요약입니다.", **extra})
            with self.subTest(extra=extra), self.assertLogs("app.assist", level="WARNING"):
                result = generate(closure, api_key="test-key", transport=lambda key, payload: reply)
                self.assertEqual((result.source, result.nextActions, result.customerMessageDraft), ("RULES", [], None))

    def test_missing_key_never_calls_the_transport(self):
        def transport(key, payload):
            raise AssertionError("transport must not be called")

        result = generate(request(), api_key=" ", transport=transport)
        self.assertEqual((result.source, result.model), ("RULES", None))
        self.assertIn("설정되지 않아", result.warnings[0])


class AssistHttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        environment = patch.dict("os.environ", {"AGENT_SERVICE_TOKEN": TOKEN, "OPENAI_API_KEY": ""})
        environment.start()
        cls.addClassCleanup(environment.stop)
        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        cls.addClassCleanup(listener.close)
        cls.url = f"http://127.0.0.1:{listener.getsockname()[1]}"
        server = uvicorn.Server(uvicorn.Config(app, log_level="warning", access_log=False))
        thread = Thread(target=server.run, kwargs={"sockets": [listener]}, daemon=True)
        thread.start()

        def stop():
            server.should_exit = True
            thread.join(timeout=5)

        cls.addClassCleanup(stop)
        deadline = time.monotonic() + 5
        while not server.started:
            if not thread.is_alive() or time.monotonic() >= deadline:
                raise RuntimeError("상담사 안내 초안 테스트 서버를 시작할 수 없습니다.")
            time.sleep(.01)

    def post(self, payload, token=TOKEN):
        headers = {"Content-Type": "application/json"}
        if token is not None:
            headers["Authorization"] = "Bearer " + token
        try:
            response = urlopen(Request(self.url + PATH, data=json.dumps(payload).encode(), headers=headers), timeout=5)
        except HTTPError as error:
            response = error
        with response:
            return response.status, response.headers.get_content_type(), json.load(response)

    def test_without_a_key_the_endpoint_answers_from_rules_and_rejects_unknown_input(self):
        status, media_type, body = self.post(EXAMPLE)
        self.assertEqual((status, media_type), (200, "application/json"))
        self.assertEqual(set(body), {"caseId", "kind", "source", "model", "summary", "nextActions",
                                     "customerMessageDraft", "warnings"})
        self.assertEqual((body["caseId"], body["kind"], body["source"], body["model"]), ("C-001", "GUIDANCE", "RULES", None))
        self.assertEqual(self.post(EXAMPLE, token="wrong")[0], 401)
        for change in ({"kind": "AUTO_SEND"}, {"reportText": "원문"}):
            status, media_type, problem = self.post({**EXAMPLE, **change})
            self.assertEqual((status, media_type, problem["code"]), (400, "application/problem+json", "INVALID_ASSIST_REQUEST"))
        nested = copy.deepcopy(EXAMPLE)
        nested["facts"]["customerMessage"] = "원문"
        self.assertEqual(self.post(nested)[2]["code"], "INVALID_ASSIST_REQUEST")


if __name__ == "__main__":
    unittest.main()
