import json
import socket
import time
import unittest
from threading import Thread
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import uvicorn

from app.extractor import ExtractionUnavailable
from app.main import app


PATH = "/internal/v1/hotel-reply-extractions"
TEST_TOKEN = "api-024-test-fixture"
EXAMPLE = {
    "evidenceId": "EV-002",
    "bookingId": "YB-001",
    "hotelId": "HOTEL-001",
    "stayDates": ["2026-11-10", "2026-11-11", "2026-11-12"],
    "originalMessage": "11월 10일은 객실 제공이 어렵고 11월 11일과 12일은 아직 확인 중입니다.",
    "locale": "ko-KR",
    "messageOccurredAt": "2026-11-09T10:05:00+09:00",
    "hotelTimeZone": "Asia/Seoul",
}


class ExtractionHttpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        env = patch.dict("os.environ", {"AGENT_SERVICE_TOKEN": TEST_TOKEN})
        env.start()
        cls.addClassCleanup(env.stop)

        listener = socket.socket()
        cls.addClassCleanup(listener.close)
        listener.bind(("127.0.0.1", 0))
        cls.base_url = f"http://127.0.0.1:{listener.getsockname()[1]}"

        server = uvicorn.Server(uvicorn.Config(app, log_level="warning", access_log=False))
        thread = Thread(target=server.run, kwargs={"sockets": [listener]}, daemon=True)
        thread.start()

        def stop_server() -> None:
            server.should_exit = True
            thread.join(timeout=5)

        cls.addClassCleanup(stop_server)
        deadline = time.monotonic() + 5
        while not server.started:
            if not thread.is_alive() or time.monotonic() >= deadline:
                raise RuntimeError("테스트용 Agent 서버가 시작되지 않았습니다.")
            time.sleep(0.01)

    def post(self, payload: dict | bytes, token: str | None = TEST_TOKEN) -> tuple[int, str, dict]:
        body = payload if isinstance(payload, bytes) else json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        if token is not None:
            headers["Authorization"] = f"Bearer {token}"
        request = Request(self.base_url + PATH, data=body, headers=headers)
        try:
            response = urlopen(request, timeout=5)
        except HTTPError as error:
            response = error
        with response:
            return response.status, response.headers.get_content_type(), json.load(response)

    def assert_problem(self, response: tuple[int, str, dict], status: int, code: str) -> None:
        actual_status, content_type, problem = response
        self.assertEqual(actual_status, status)
        self.assertEqual(content_type, "application/problem+json")
        self.assertEqual(set(problem), {"type", "title", "status", "detail", "code", "traceId"})
        self.assertEqual(problem["status"], status)
        self.assertEqual(problem["code"], code)
        self.assertTrue(problem["traceId"].startswith("req-"))

    def test_notion_partial_extraction(self) -> None:
        status, content_type, body = self.post(EXAMPLE)
        self.assertEqual((status, content_type), (200, "application/json"))
        self.assertEqual(body, {
            "evidenceId": "EV-002",
            "analysisStatus": "EXTRACTED",
            "modelVersion": "hotel-reply-extractor-0.1",
            "claims": [{
                "stayDate": "2026-11-10",
                "reportedProvisionStatus": "UNAVAILABLE",
                "supportingText": "11월 10일은 객실 제공이 어렵고",
            }],
            "unresolvedStayDates": ["2026-11-11", "2026-11-12"],
            "requiresHumanVerification": True,
            "warnings": ["11월 11일과 12일의 객실 제공 가능 여부는 확인되지 않았습니다."],
        })

    def test_ambiguous_reply_covers_all_requested_dates(self) -> None:
        status, _, body = self.post({**EXAMPLE, "originalMessage": "아직 확인 중입니다."})
        self.assertEqual(status, 200)
        self.assertEqual(body["analysisStatus"], "AMBIGUOUS")
        self.assertEqual(body["claims"], [])
        self.assertEqual(body["unresolvedStayDates"], EXAMPLE["stayDates"])
        self.assertTrue(body["requiresHumanVerification"])

    def test_relative_date_uses_hotel_local_day(self) -> None:
        message = "내일은 객실 제공이 가능합니다."
        status, _, body = self.post({
            **EXAMPLE, "originalMessage": message, "messageOccurredAt": "2026-11-09T15:05:00Z",
        })
        self.assertEqual(status, 200)
        self.assertEqual(len(body["claims"]), 1)
        claim = body["claims"][0]
        self.assertEqual(claim["stayDate"], "2026-11-11")
        self.assertEqual(claim["reportedProvisionStatus"], "AVAILABLE")
        self.assertIn(claim["supportingText"], message)
        self.assertEqual(body["unresolvedStayDates"], ["2026-11-10", "2026-11-12"])

    def test_relative_date_without_occurrence_time_is_unresolved(self) -> None:
        status, _, body = self.post({
            **EXAMPLE, "originalMessage": "내일은 객실 제공이 가능합니다.", "messageOccurredAt": None,
        })
        self.assertEqual(status, 200)
        self.assertEqual(body["analysisStatus"], "AMBIGUOUS")
        self.assertEqual(body["unresolvedStayDates"], EXAMPLE["stayDates"])

    def test_conflicting_and_outside_dates_are_unresolved(self) -> None:
        status, _, body = self.post({
            **EXAMPLE,
            "originalMessage": (
                "11월 10일은 객실 제공이 가능합니다. 11월 10일은 객실 제공이 불가능합니다. "
                "11월 13일은 객실 제공이 가능합니다."
            ),
        })
        self.assertEqual(status, 200)
        self.assertEqual(body["analysisStatus"], "AMBIGUOUS")
        self.assertEqual(body["claims"], [])
        self.assertEqual(body["unresolvedStayDates"], EXAMPLE["stayDates"])

    def assert_extraction(self, message: str, expected_claims: list[tuple[str, str]]) -> None:
        status, content_type, body = self.post({**EXAMPLE, "originalMessage": message})
        self.assertEqual((status, content_type), (200, "application/json"))
        self.assertIs(body["requiresHumanVerification"], True)
        claims = body["claims"]
        claim_dates = [claim["stayDate"] for claim in claims]
        unresolved = body["unresolvedStayDates"]
        self.assertCountEqual(claim_dates + unresolved, EXAMPLE["stayDates"])
        for claim in claims:
            self.assertTrue(claim["supportingText"])
            self.assertIn(claim["supportingText"], message)

        self.assertEqual(
            (body["analysisStatus"], [(claim["stayDate"], claim["reportedProvisionStatus"]) for claim in claims]),
            ("EXTRACTED" if expected_claims else "AMBIGUOUS", expected_claims),
        )
        expected_dates = {day for day, _ in expected_claims}
        self.assertEqual(unresolved, [day for day in EXAMPLE["stayDates"] if day not in expected_dates])

    def test_correction_does_not_pick_one_conflicting_claim(self) -> None:
        self.assert_extraction(
            "11월 10일은 객실 제공이 가능합니다. 정정: 11월 10일은 객실 제공이 불가능합니다.",
            [],
        )

    def test_withdrawn_notice_leaves_previous_dates_unresolved(self) -> None:
        self.assert_extraction(
            "11월 10일은 객실 제공이 가능합니다. 정정: 방금 안내는 취소합니다.",
            [],
        )

    def test_deposit_condition_leaves_previous_availability_unresolved(self) -> None:
        self.assert_extraction(
            "11월 10일은 객실 제공이 가능합니다. 단, 보증금 입금이 확인되어야 합니다.",
            [],
        )

    def test_deposit_condition_keeps_the_next_date_unavailability(self) -> None:
        self.assert_extraction(
            "11월 10일은 객실 제공이 가능합니다. 단, 보증금 입금이 확인되어야 합니다. "
            "11월 11일은 객실 제공이 불가능합니다.",
            [("2026-11-11", "UNAVAILABLE")],
        )

    def test_unrelated_cancellation_fee_keeps_unavailability(self) -> None:
        self.assert_extraction(
            "11월 10일은 객실 제공이 불가능합니다. 취소 수수료는 별도 안내합니다.",
            [("2026-11-10", "UNAVAILABLE")],
        )

    def test_dated_withdrawal_keeps_an_unrelated_date(self) -> None:
        self.assert_extraction(
            "11월 10일은 객실 제공이 가능합니다. 11월 11일은 객실 제공이 불가능합니다. "
            "정정: 11월 10일 안내는 취소합니다.",
            [("2026-11-11", "UNAVAILABLE")],
        )

    def test_unspecified_subset_keeps_other_statements_intact(self) -> None:
        self.assert_extraction(
            "11월 12일은 객실 제공이 불가능합니다. 11월 10일과 11일은 객실 제공이 가능합니다. "
            "단, 일부 숙박일은 재확인이 필요합니다.",
            [("2026-11-12", "UNAVAILABLE")],
        )

    def test_undated_condition_qualifies_the_previous_claim(self) -> None:
        self.assert_extraction(
            "11월 10일은 객실 제공이 가능합니다. 단, 기존 고객이 취소해야 합니다.",
            [],
        )

    def test_undated_remainder_keeps_the_unavailable_claim(self) -> None:
        self.assert_extraction(
            "11월 10일은 객실 제공이 불가하고, 나머지는 확인 중입니다.",
            [("2026-11-10", "UNAVAILABLE")],
        )

    def test_dated_condition_qualifies_only_the_named_date(self) -> None:
        self.assert_extraction(
            "11월 10일은 객실 제공이 불가능합니다. 11월 11일은 객실 제공이 가능합니다. "
            "단, 11일은 기존 고객의 취소가 필요합니다.",
            [("2026-11-10", "UNAVAILABLE")],
        )

    def test_cancellation_if_clause_qualifies_the_previous_claim(self) -> None:
        self.assert_extraction(
            "10일은 객실 제공이 가능합니다. 단, 기존 손님이 취소하면 가능합니다.",
            [],
        )

    def test_condition_finds_the_named_date_after_its_reason(self) -> None:
        self.assert_extraction(
            "11월 10일은 객실 제공이 불가능합니다. 11월 11일은 객실 제공이 가능합니다. "
            "다만, 기존 예약 때문에 11월 11일은 다른 고객의 취소가 필요합니다.",
            [("2026-11-10", "UNAVAILABLE")],
        )

    def test_cancellation_if_clause_keeps_the_following_dated_claim(self) -> None:
        self.assert_extraction(
            "10일은 객실 제공이 가능합니다, 단, 기존 손님이 취소하면 가능합니다, "
            "11일은 객실 제공이 불가능합니다.",
            [("2026-11-11", "UNAVAILABLE")],
        )

    def test_named_condition_does_not_fall_back_to_the_previous_claim(self) -> None:
        two_claims = "11월 10일은 객실 제공이 가능합니다. 11월 11일은 객실 제공이 가능합니다. "
        for target, expected in (
            ("10일", [("2026-11-11", "AVAILABLE")]),
            ("13일", [("2026-11-10", "AVAILABLE"), ("2026-11-11", "AVAILABLE")]),
        ):
            with self.subTest(target=target):
                self.assert_extraction(two_claims + f"단, {target}은 기존 고객의 취소가 필요합니다.", expected)

    def test_undated_condition_does_not_attach_across_an_unrelated_sentence(self) -> None:
        self.assert_extraction(
            "11월 10일은 객실 제공이 가능합니다. 조식은 별도입니다. 단, 기존 고객의 취소가 필요합니다.",
            [("2026-11-10", "AVAILABLE")],
        )

    def test_inline_condition_keeps_the_following_dated_claim(self) -> None:
        self.assert_extraction(
            "11월 10일은 객실 제공이 가능합니다, 단, 11일까지 기존 고객이 취소해야 합니다, "
            "11월 11일은 객실 제공이 불가능합니다.",
            [("2026-11-11", "UNAVAILABLE")],
        )

    def test_undated_condition_qualifies_one_statement_with_multiple_dates(self) -> None:
        self.assert_extraction(
            "11월 10일과 11일은 객실 제공이 가능합니다. 다만 기존 고객이 취소하는 경우에 한합니다.",
            [],
        )

    def test_partial_date_in_list_keeps_its_month(self) -> None:
        status, _, body = self.post({
            **EXAMPLE,
            "stayDates": ["2026-11-10", "2026-11-12", "2026-12-12"],
            "originalMessage": "11월 10일과 12일은 객실 제공이 가능합니다.",
        })
        self.assertEqual(status, 200)
        self.assertEqual([claim["stayDate"] for claim in body["claims"]], ["2026-11-10", "2026-11-12"])
        self.assertEqual(body["unresolvedStayDates"], ["2026-12-12"])

    def test_out_of_range_relative_day_is_unresolved(self) -> None:
        status, _, body = self.post({
            **EXAMPLE,
            "stayDates": ["9999-12-31"],
            "originalMessage": "내일은 객실 제공이 가능합니다.",
            "messageOccurredAt": "9999-12-31T00:00:00+09:00",
        })
        self.assertEqual(status, 200)
        self.assertEqual(body["analysisStatus"], "AMBIGUOUS")
        self.assertEqual(body["unresolvedStayDates"], ["9999-12-31"])

    def test_missing_or_wrong_token(self) -> None:
        for token in (None, "different-test-token"):
            with self.subTest(token=token):
                self.assert_problem(self.post(EXAMPLE, token), 401, "UNAUTHENTICATED_SERVICE")

    def test_malformed_json_and_invalid_date_formats(self) -> None:
        self.assert_problem(self.post(b"{"), 400, "INVALID_EXTRACTION_REQUEST")
        for invalid_date in (1794268800, "2026-11-10T00:00:00Z", "2026-11-31"):
            with self.subTest(invalid_date=invalid_date):
                payload = {**EXAMPLE, "stayDates": [invalid_date]}
                self.assert_problem(self.post(payload), 400, "INVALID_EXTRACTION_REQUEST")

    def test_occurrence_time_is_required_and_must_have_an_offset(self) -> None:
        missing_field = {key: value for key, value in EXAMPLE.items() if key != "messageOccurredAt"}
        self.assert_problem(self.post(missing_field), 400, "INVALID_EXTRACTION_REQUEST")
        self.assert_problem(self.post({**EXAMPLE, "messageOccurredAt": "2026-11-09T10:05:00"}),
                            400, "INVALID_EXTRACTION_REQUEST")

    def test_invalid_stay_context(self) -> None:
        self.assert_problem(self.post({**EXAMPLE, "stayDates": []}), 422, "EMPTY_STAY_DATES")
        self.assert_problem(self.post({**EXAMPLE, "stayDates": ["2026-11-10"] * 2}), 422, "INVALID_STAY_CONTEXT")
        self.assert_problem(self.post({**EXAMPLE, "hotelTimeZone": "Unknown/Hotel"}), 422, "INVALID_STAY_CONTEXT")

    def test_unavailable_extractor(self) -> None:
        with patch("app.main.extract_claims", side_effect=ExtractionUnavailable):
            self.assert_problem(self.post(EXAMPLE), 503, "MODEL_UNAVAILABLE")

    def test_internal_error_does_not_include_message(self) -> None:
        with patch("app.main.extract_claims", side_effect=RuntimeError(EXAMPLE["originalMessage"])):
            with self.assertLogs("app.main", level="ERROR") as logs:
                response = self.post(EXAMPLE)
        self.assert_problem(response, 500, "INTERNAL_ERROR")
        self.assertNotIn(EXAMPLE["originalMessage"], response[2]["detail"])
        self.assertNotIn(EXAMPLE["originalMessage"], "\n".join(logs.output))
        self.assertIn(response[2]["traceId"], "\n".join(logs.output))

    def test_openapi_describes_bearer_and_problem_details(self) -> None:
        with urlopen(f"{self.base_url}/openapi.json", timeout=5) as response:
            operation = json.load(response)["paths"][PATH]["post"]
        self.assertIn({"HTTPBearer": []}, operation["security"])
        self.assertIn("application/problem+json", operation["responses"]["422"]["content"])
