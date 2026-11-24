import importlib
from pathlib import Path
import sys
from types import ModuleType
import unittest
from datetime import date

PACKAGE = "alternative_search_safety_tests"
if PACKAGE not in sys.modules:
    package = ModuleType(PACKAGE)
    package.__path__ = [str(Path(__file__).resolve().parents[1])]
    sys.modules[PACKAGE] = package

policy = importlib.import_module(PACKAGE + ".policy")
location = importlib.import_module(PACKAGE + ".location_agent")


class SearchPolicyTests(unittest.TestCase):
    def resolve(self, check_in, check_out, nights=3):
        return policy.resolve_search_policy(
            nightly_price=150000, nights=nights, adults=2,
            raw_check_in=check_in, raw_check_out=check_out, today=date(2026, 11, 24))

    def test_keeps_exact_future_booking_period(self):
        result = self.resolve(date(2026, 12, 8), date(2026, 12, 11))
        self.assertEqual(result.check_in_date, date(2026, 12, 8))
        self.assertEqual(result.check_out_date, date(2026, 12, 11))
        self.assertEqual(result.original_total_amount, 450000)
        self.assertFalse(result.used_future_fallback_dates)
        self.assertIsNone(result.search_note)

    def test_rejects_past_or_started_booking_instead_of_moving_it(self):
        for start, end in ((date(2026, 11, 10), date(2026, 11, 13)),
                           (date(2026, 11, 23), date(2026, 11, 26))):
            with self.subTest(start=start), self.assertRaises(policy.SearchPolicyError):
                self.resolve(start, end)

    def test_rejects_missing_or_inconsistent_period(self):
        for start, end, nights in ((None, None, 3), (date(2026, 12, 8), None, 3),
                                   (date(2026, 12, 8), date(2026, 12, 8), 3),
                                   (date(2026, 12, 8), date(2026, 12, 11), 1)):
            with self.subTest(start=start, end=end), self.assertRaises(policy.SearchPolicyError):
                self.resolve(start, end, nights)


class HotelIdentityTests(unittest.TestCase):
    def verify(self, requested, candidate, address="제주특별자치도 제주시", hint=None):
        return location._verify_candidate_match(
            hotel_name=requested, candidate_name=candidate,
            candidate_address=address, region_hint=hint)[0]

    def test_region_alone_does_not_identify_the_requested_hotel(self):
        self.assertFalse(self.verify("신라스테이 제주", "롯데시티호텔 제주", hint="제주"))
        self.assertFalse(self.verify("신라스테이 제주", "제주"))
        self.assertFalse(self.verify("", "제주"))

    def test_normalized_exact_name_is_accepted_but_branch_difference_is_not(self):
        self.assertTrue(self.verify("신라스테이 제주", "신라스테이제주", hint="제주"))
        self.assertTrue(self.verify("Hotel ABC", "hotel abc", address="서울 강남구", hint="서울 강남"))
        self.assertFalse(self.verify("신라스테이 제주", "신라스테이 서귀포"))

    def test_every_explicit_region_hint_must_match(self):
        self.assertFalse(self.verify("신라스테이 제주", "신라스테이 제주", hint="제주 서귀포"))


if __name__ == "__main__":
    unittest.main()
