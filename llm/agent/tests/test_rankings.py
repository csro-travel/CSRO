"""API-025 HTTP contracts: source snapshots remain the only price/period facts."""
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

from app.main import app
from app.rankings import RankingRequest, RankingUnavailable, rank_quotes


PATH = "/internal/v1/alternative-rankings"
TOKEN = "api-025-test-fixture"
EXAMPLE = {
    "caseId": "C-001", "bookingId": "YB-001",
    "requestedStay": {"checkInDate": "2026-11-10", "checkOutDate": "2026-11-13", "adults": 2},
    "originalStayTotal": {"amount": 450000, "currency": "KRW"},
    "quotes": [
        {"quoteId": f"QUOTE-{name}", "hotelName": f"{name}호텔",
         "checkInDate": "2026-11-10", "checkOutDate": "2026-11-13", "adults": 2,
         "distanceMeters": distance, "totalAmount": amount, "currency": "KRW",
         "checkedAt": "2026-11-09T10:15:00+09:00"}
        for name, distance, amount in (("A", 800, 480000), ("B", 1400, 510000), ("C", 2600, 465000))
    ],
}


class RankingHttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        environment = patch.dict("os.environ", {"AGENT_SERVICE_TOKEN": TOKEN})
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
                raise RuntimeError("견적 비교 테스트 서버를 시작할 수 없습니다.")
            time.sleep(.01)

    def post(self, payload=None, token=TOKEN):
        if payload is None:
            payload = EXAMPLE
        body = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
        headers = {"Content-Type": "application/json"}
        if token is not None:
            headers["Authorization"] = "Bearer " + token
        try:
            response = urlopen(Request(self.url + PATH, data=body, headers=headers), timeout=5)
        except HTTPError as error:
            response = error
        with response:
            return response.status, response.headers.get_content_type(), json.load(response)

    def assert_problem(self, response, status, code):
        actual_status, media_type, body = response
        self.assertEqual((actual_status, media_type), (status, "application/problem+json"))
        self.assertEqual(set(body), {"type", "title", "status", "detail", "code", "traceId"})
        self.assertEqual(body["code"], code)
        self.assertEqual(body["status"], status)
        self.assertTrue(body["traceId"].startswith("req-"))

    def test_canonical_example_ranks_actual_quote_ids_and_explains_facts(self):
        status, media_type, result = self.post()
        self.assertEqual((status, media_type), (200, "application/json"))
        self.assertEqual(set(result), {"caseId", "rankingVersion", "rankedQuoteIds", "reasons", "warnings"})
        self.assertEqual(result["caseId"], "C-001")
        self.assertEqual(result["rankingVersion"], "alternative-ranker-0.1")
        self.assertEqual(result["rankedQuoteIds"], ["QUOTE-A", "QUOTE-C", "QUOTE-B"])
        self.assertEqual([reason["quoteId"] for reason in result["reasons"]], result["rankedQuoteIds"])
        self.assertEqual([reason["reasonCodes"] for reason in result["reasons"]],
                         [["NEAREST"], ["LOWEST_PRICE"], ["ALTERNATIVE"]])
        self.assertIn("3박", result["reasons"][1]["summary"])
        self.assertIn("예약은 확정되지", result["warnings"][0])
        for forbidden in ("price", "totalAmount", "supplierBookingId", "status"):
            self.assertNotIn(forbidden, result)

    def test_rule_is_deterministic_and_preserves_input_snapshots(self):
        request = RankingRequest.model_validate(EXAMPLE)
        original = request.model_dump(mode="json")
        first = rank_quotes(request).model_dump()
        request.quotes.reverse()
        self.assertEqual(rank_quotes(request).model_dump(), first)
        request.quotes.reverse()
        self.assertEqual(request.model_dump(mode="json"), original)
        self.assertEqual(self.post()[2], self.post()[2])

    def test_lowest_price_reason_is_never_given_to_a_more_expensive_quote(self):
        body = copy.deepcopy(EXAMPLE)
        body["quotes"][0]["totalAmount"] = 1
        _, _, result = self.post(body)
        self.assertTrue(all("LOWEST_PRICE" not in reason["reasonCodes"] for reason in result["reasons"]))

    def test_one_night_is_a_separate_request_without_original_three_night_total(self):
        body = copy.deepcopy(EXAMPLE)
        del body["originalStayTotal"]
        body["requestedStay"]["checkOutDate"] = "2026-11-11"
        for quote in body["quotes"]:
            quote["checkOutDate"] = "2026-11-11"
            quote["quoteId"] += "-ONE-NIGHT"
            quote["totalAmount"] //= 3
        status, _, result = self.post(body)
        self.assertEqual(status, 200)
        self.assertIn("1박", result["reasons"][1]["summary"])
        self.assertNotIn("추가 부담", json.dumps(result, ensure_ascii=False))

    def test_period_or_occupancy_mismatch_is_not_compared(self):
        for field, value in (("checkOutDate", "2026-11-11"), ("checkInDate", "2026-11-11"), ("adults", 1)):
            with self.subTest(field=field):
                body = copy.deepcopy(EXAMPLE)
                body["quotes"][0][field] = value
                self.assert_problem(self.post(body), 422, "QUOTE_STAY_MISMATCH")
        body = copy.deepcopy(EXAMPLE)
        body["requestedStay"]["checkOutDate"] = "2026-11-10"
        self.assert_problem(self.post(body), 422, "QUOTE_STAY_MISMATCH")

    def test_non_krw_quotes_or_reference_are_not_silently_converted(self):
        body = copy.deepcopy(EXAMPLE)
        body["quotes"][0]["currency"] = "USD"
        self.assert_problem(self.post(body), 422, "CURRENCY_MISMATCH")
        body = copy.deepcopy(EXAMPLE)
        body["originalStayTotal"]["currency"] = "USD"
        self.assert_problem(self.post(body), 422, "CURRENCY_MISMATCH")

    def test_duplicate_ids_and_invalid_amount_types_are_400(self):
        body = copy.deepcopy(EXAMPLE)
        body["quotes"][1]["quoteId"] = "QUOTE-A"
        self.assert_problem(self.post(body), 400, "INVALID_RANKING_REQUEST")
        for value in (True, "480000", 480000.0, -1, None):
            with self.subTest(value=value):
                body = copy.deepcopy(EXAMPLE)
                body["quotes"][0]["totalAmount"] = value
                self.assert_problem(self.post(body), 400, "INVALID_RANKING_REQUEST")

    def test_malformed_and_nested_extra_fields_follow_source_schema(self):
        self.assert_problem(self.post(b"{"), 400, "INVALID_RANKING_REQUEST")
        body = copy.deepcopy(EXAMPLE)
        body["quotes"][0]["inventedAvailability"] = True
        self.assert_problem(self.post(body), 400, "INVALID_RANKING_REQUEST")
        body = copy.deepcopy(EXAMPLE)
        body["futureTopLevelField"] = "allowed by source schema"
        self.assertEqual(self.post(body)[0], 200)
        body["originalStayTotal"] = None
        self.assert_problem(self.post(body), 400, "INVALID_RANKING_REQUEST")
        for value in ([], body["quotes"] * 7):
            body["quotes"] = value
            self.assert_problem(self.post(body), 400, "INVALID_RANKING_REQUEST")

    def test_invalid_date_or_timezone_is_not_rewritten(self):
        for field, value in (("checkInDate", "2026-11-31"), ("checkedAt", "2026-11-09T10:15:00"),
                             ("checkedAt", 123)):
            with self.subTest(field=field, value=value):
                body = copy.deepcopy(EXAMPLE)
                body["quotes"][0][field] = value
                self.assert_problem(self.post(body), 400, "INVALID_RANKING_REQUEST")

    def test_internal_bearer_auth_is_shared_with_existing_agent(self):
        for token in (None, "wrong-token"):
            self.assert_problem(self.post(token=token), 401, "UNAUTHENTICATED_SERVICE")

    def test_provider_failures_are_controlled_without_returning_input(self):
        with patch("app.main.rank_quotes", side_effect=RankingUnavailable):
            self.assert_problem(self.post(), 503, "AGENT_UNAVAILABLE")
        with patch("app.main.rank_quotes", side_effect=RuntimeError("private provider payload")):
            with self.assertLogs("app.main", level="ERROR") as logs:
                result = self.post()
            self.assert_problem(result, 500, "INTERNAL_ERROR")
            self.assertNotIn("private provider payload", json.dumps(result))
            self.assertNotIn("private provider payload", "\n".join(logs.output))

    def test_openapi_exposes_existing_bearer_and_problem_media_type(self):
        with urlopen(self.url + "/openapi.json", timeout=5) as response:
            operation = json.load(response)["paths"][PATH]["post"]
        self.assertEqual(operation["security"], [{"HTTPBearer": []}])
        for code in ("400", "401", "422", "503", "500"):
            self.assertIn("application/problem+json", operation["responses"][code]["content"])


if __name__ == "__main__":
    unittest.main()
