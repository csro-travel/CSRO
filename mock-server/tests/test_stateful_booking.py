"""Core booking invariants against the dedicated PostgreSQL fixture only."""
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, timedelta
import json
import os
import threading
import unittest
from unittest.mock import Mock
import uuid

from fastapi.testclient import TestClient

from app.availability import AvailabilitySearch, search_quotes
from app.booking_models import BookingError, BookingOutcome
from app.main import create_app
from app.stateful import MockDatabaseSettings, PostgresMockStore

AT = datetime.fromisoformat("2026-11-09T10:20:00+09:00")
CHECKED = AT - timedelta(minutes=5)
EXPIRES = AT + timedelta(minutes=5)
TOKEN = "mock-booking-test-service-token"


def search_body(request_id="SEARCH-001"):
    return AvailabilitySearch.model_validate({"searchRequestId":request_id,"originPropertyId":"HOTEL-001",
        "stay":{"checkInDate":"2026-11-10","checkOutDate":"2026-11-13","adults":2,"rooms":1},
        "filters":{"radiusMeters":3000,"maxAverageNightlyAmount":300000,"currency":"KRW"}})


class BookingHttpBoundaryTest(unittest.TestCase):
    def test_default_mode_does_not_claim_to_reserve_or_query_supplier_state(self):
        client = TestClient(create_app(TOKEN))
        headers = {"Authorization":"Bearer "+TOKEN,"Idempotency-Key":"BRQ-default"}
        body = {"quoteId":"QUOTE-A","expectedTotalAmount":480000,"currency":"KRW","guestReference":"GUEST-001"}
        self.assertEqual(client.post("/mock-supplier/v1/replacement-bookings",headers=headers,json=body).status_code,503)
        self.assertEqual(client.get("/mock-supplier/v1/replacement-booking-requests/BRQ-default",headers=headers).status_code,503)

    def test_stateful_routes_require_auth_strict_body_key_and_loopback_without_client_clock(self):
        store = Mock()
        store.book.return_value = BookingOutcome(201,{"requestId":"BRQ-test","status":"CONFIRMED"})
        store.result.return_value = {"requestId":"BRQ-test","status":"CONFIRMED","supplierBookingId":"ALT-test",
            "quoteId":"QUOTE-A","propertyId":"HOTEL-A","confirmedPrice":{"totalAmount":480000,"currency":"KRW"},"confirmedAt":AT.isoformat()}
        client = TestClient(create_app(TOKEN,booking_store=store),base_url="http://127.0.0.1:8001",client=("127.0.0.1",50000))
        body = {"quoteId":"QUOTE-A","expectedTotalAmount":480000,"currency":"KRW","guestReference":"GUEST-001"}
        headers = {"Authorization":"Bearer "+TOKEN,"Idempotency-Key":"BRQ-test"}
        self.assertEqual(client.post("/mock-supplier/v1/replacement-bookings",json=body).status_code,401)
        self.assertEqual(client.post("/mock-supplier/v1/replacement-bookings",headers={"Authorization":"Bearer "+TOKEN},json=body).status_code,400)
        self.assertEqual(client.post("/mock-supplier/v1/replacement-bookings",headers=headers,json={**body,"asOf":AT.isoformat()}).status_code,400)
        self.assertEqual(client.post("/mock-supplier/v1/replacement-bookings",headers=headers,json={**body,"expectedTotalAmount":True}).status_code,400)
        self.assertEqual(client.post("/mock-supplier/v1/replacement-bookings",headers=headers,json=body).status_code,201)
        self.assertEqual(client.get("/mock-supplier/v1/replacement-booking-requests/BRQ-test",headers=headers).status_code,200)
        self.assertEqual(store.book.call_count,1)
        self.assertEqual(store.result.call_count,1)
        remote = TestClient(create_app(TOKEN,booking_store=store),base_url="http://127.0.0.1:8001",client=("192.0.2.5",50000))
        self.assertEqual(remote.post("/mock-supplier/v1/replacement-bookings",headers=headers,json=body).status_code,403)
        self.assertEqual(store.book.call_count,1)


@unittest.skipUnless(os.environ.get("MOCK_STATEFUL_TEST") == "true", "requires the identity-guarded temporary Mock PostgreSQL")
class StatefulBookingPostgresTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.settings = MockDatabaseSettings.from_environment(testing=True)
        cls.store = PostgresMockStore(cls.settings,as_of=AT)

    def setUp(self):
        self.suffix = uuid.uuid4().hex[:12]
        self.product, self.quote = self.fixture()
        self.key = "BRQ-" + self.suffix
        self.body = {"quoteId":self.quote,"expectedTotalAmount":480000,"currency":"KRW","guestReference":"GUEST-test"}

    def fixture(self, stock=(1,1,1)):
        suffix = uuid.uuid4().hex[:12]
        with self.store._connect() as connection,connection.transaction():
            product = connection.execute("""
                INSERT INTO mock_products (property_code,property_name,room_type_code,room_name,rate_plan_code,
                    max_adults,cancellation_policy,latitude,longitude)
                VALUES (%s,'가상 검사 숙소',%s,'가상 검사 객실',%s,2,'NON_REFUNDABLE',0,0) RETURNING id
                """, ("HOTEL-T-"+suffix,"ROOM-T-"+suffix,"RATE-T-"+suffix)).fetchone()["id"]
            for offset,count in enumerate(stock):
                connection.execute("INSERT INTO mock_night_inventory VALUES (%s,%s,%s,150000,10000)",
                    (product,date(2026,11,10)+timedelta(days=offset),count))
            quote = self.quote_for(connection,product)
        return product,quote

    def quote_for(self, connection, product):
        code = "QUOTE-T-"+uuid.uuid4().hex
        connection.execute("""
            INSERT INTO mock_quotes (quote_code,product_id,check_in_date,check_out_date,adults,rooms,
                base_amount,mandatory_fees_amount,total_amount,checked_at,expires_at)
            VALUES (%s,%s,DATE '2026-11-10',DATE '2026-11-13',2,1,450000,30000,480000,%s,%s)
            """, (code,product,CHECKED,EXPIRES))
        return code

    def stock(self):
        with self.store._connect() as connection:
            return [row["available_rooms"] for row in connection.execute(
                "SELECT available_rooms FROM mock_night_inventory WHERE product_id=%s ORDER BY stay_date",(self.product,))]

    def count(self):
        with self.store._connect() as connection:
            return connection.execute("SELECT count(*) AS count FROM mock_booking_requests WHERE request_code LIKE %s",(self.key+"%",)).fetchone()["count"]

    def test_concurrent_identical_key_has_one_booking_and_one_all_night_decrement(self):
        barrier = threading.Barrier(2)
        def book():
            barrier.wait(timeout=5)
            return self.store.book(self.key,self.body)
        with ThreadPoolExecutor(max_workers=2) as pool:
            futures = [pool.submit(book) for _ in range(2)]
            first,second = [future.result(timeout=10) for future in futures]
        self.assertEqual((first.status,second.status),(201,201))
        self.assertEqual(first.body,second.body)
        self.assertEqual(sorted([first.replayed,second.replayed]),[False,True])
        self.assertEqual(self.stock(),[0,0,0])
        self.assertEqual(self.count(),1)

    def test_different_search_quotes_competing_for_last_room_cannot_partially_book(self):
        with self.store._connect() as connection:
            other_quote = self.quote_for(connection,self.product)
        barrier = threading.Barrier(2)
        def book(key,quote):
            barrier.wait(timeout=5)
            return self.store.book(key,{**self.body,"quoteId":quote})
        with ThreadPoolExecutor(max_workers=2) as pool:
            futures = [pool.submit(book,self.key,self.quote),pool.submit(book,self.key+"-other",other_quote)]
            outcomes = [future.result(timeout=10) for future in futures]
        self.assertEqual(sorted(outcome.status for outcome in outcomes),[201,409])
        self.assertEqual(next(outcome.body["code"] for outcome in outcomes if outcome.status==409),"OUT_OF_STOCK")
        self.assertEqual(self.stock(),[0,0,0])
        self.assertEqual(self.count(),2)

    def test_middle_night_stock_change_preserves_all_other_nights_and_failed_result(self):
        with self.store._connect() as connection:
            connection.execute("UPDATE mock_night_inventory SET available_rooms=0 WHERE product_id=%s AND stay_date=DATE '2026-11-11'",(self.product,))
        first = self.store.book(self.key,self.body)
        self.assertEqual((first.status,first.body["code"]),(409,"OUT_OF_STOCK"))
        self.assertEqual(self.stock(),[1,0,1])
        self.assertEqual(self.store.book(self.key,self.body).body,first.body)
        result = self.store.result(self.key)
        self.assertEqual((result["status"],result["failureCode"]),("FAILED","OUT_OF_STOCK"))
        self.assertIsNotNone(datetime.fromisoformat(result["failedAt"]).tzinfo)
        self.assertEqual(self.count(),1)

    def test_changed_price_records_failure_and_replays_original_current_price(self):
        with self.store._connect() as connection:
            connection.execute("UPDATE mock_night_inventory SET base_amount=156000 WHERE product_id=%s AND stay_date=DATE '2026-11-11'",(self.product,))
        first = self.store.book(self.key,self.body)
        self.assertEqual((first.status,first.body["code"],first.body["currentTotalAmount"]),(409,"PRICE_CHANGED",486000))
        with self.store._connect() as connection:
            connection.execute("UPDATE mock_night_inventory SET base_amount=157000 WHERE product_id=%s AND stay_date=DATE '2026-11-11'",(self.product,))
        replay = self.store.book(self.key,self.body)
        self.assertTrue(replay.replayed)
        self.assertEqual(replay.body,first.body)
        self.assertEqual(self.store.result(self.key)["currentTotalAmount"],486000)
        self.assertEqual(self.stock(),[1,1,1])
        with self.store._connect() as connection:
            self.assertEqual(connection.execute("SELECT total_amount FROM mock_quotes WHERE quote_code=%s",(self.quote,)).fetchone()["total_amount"],480000)

    def test_same_key_different_body_never_creates_another_result(self):
        self.store.book(self.key,self.body)
        with self.assertRaises(BookingError) as caught:
            self.store.book(self.key,{**self.body,"guestReference":"GUEST-different"})
        self.assertEqual((caught.exception.status,caught.exception.code),(409,"IDEMPOTENCY_KEY_REUSED"))
        self.assertEqual(self.count(),1)
        self.assertEqual(self.stock(),[0,0,0])

    def test_same_quote_different_key_is_not_booked_twice_even_with_remaining_stock(self):
        self.product,self.quote = self.fixture((2,2,2))
        self.body["quoteId"] = self.quote
        self.assertEqual(self.store.book(self.key,self.body).status,201)
        outcome = self.store.book(self.key+"-other",self.body)
        self.assertEqual((outcome.status,outcome.body["code"]),(409,"QUOTE_ALREADY_BOOKED"))
        self.assertEqual(self.stock(),[1,1,1])
        self.assertEqual(self.count(),2)

    def test_expiry_boundary_records_failed_result_without_stock_change(self):
        expired = PostgresMockStore(self.settings,as_of=EXPIRES)
        result = expired.book(self.key,self.body)
        self.assertEqual((result.status,result.body["code"]),(409,"QUOTE_EXPIRED"))
        self.assertEqual(self.stock(),[1,1,1])
        self.assertEqual(self.store.book(self.key,self.body).body,result.body)

    def test_restart_replays_confirmed_result_after_expiry_and_get_is_read_only(self):
        confirmed = self.store.book(self.key,self.body)
        restarted = PostgresMockStore(self.settings,as_of=EXPIRES+timedelta(days=1))
        self.assertEqual(restarted.book(self.key,self.body).body,confirmed.body)
        before = (self.stock(),self.count())
        first = restarted.result(self.key)
        for _ in range(3):
            self.assertEqual(restarted.result(self.key),first)
        self.assertEqual(first["supplierBookingId"],confirmed.body["supplierBookingId"])
        self.assertEqual((self.stock(),self.count()),before)

    def test_unknown_quote_and_unknown_get_are_preaccept_reads_without_request_rows(self):
        with self.assertRaises(BookingError) as caught:
            self.store.book(self.key,{**self.body,"quoteId":"QUOTE-does-not-exist"})
        self.assertEqual((caught.exception.status,caught.exception.code),(404,"QUOTE_NOT_FOUND"))
        with self.assertRaises(BookingError) as caught:
            self.store.result(self.key)
        self.assertEqual((caught.exception.status,caught.exception.code),(404,"BOOKING_REQUEST_NOT_FOUND"))
        self.assertEqual(self.count(),0)
        self.assertEqual(self.stock(),[1,1,1])

    def test_z_search_registers_immutable_quotes_and_reads_latest_per_night_values_without_reset(self):
        reference = PostgresMockStore(self.settings,as_of=CHECKED)
        canonical = reference.search(search_body())
        initial_b = next(q for q in canonical.quotes if q.propertyId=="HOTEL-B")
        # The first run verifies exact canonical fixtures. Later runs deliberately
        # retain prior fixture price changes rather than resetting existing data.
        if initial_b.price.totalAmount == 510000:
            self.assertEqual([q.quoteId for q in canonical.quotes],["QUOTE-A","QUOTE-B","QUOTE-C"])
            self.assertEqual([q.price.totalAmount for q in canonical.quotes],[480000,510000,465000])
        self.assertEqual(reference.search(search_body()),canonical)
        with reference._connect() as connection:
            product_b = connection.execute("SELECT id FROM mock_products WHERE property_code='HOTEL-B'").fetchone()["id"]
            old_base = connection.execute("SELECT base_amount FROM mock_night_inventory WHERE product_id=%s AND stay_date=DATE '2026-11-11'",(product_b,)).fetchone()["base_amount"]
            connection.execute("UPDATE mock_night_inventory SET base_amount=base_amount+15000 WHERE product_id=%s AND stay_date=DATE '2026-11-11'",(product_b,))
        restarted = PostgresMockStore(self.settings,as_of=AT)
        latest = restarted.search(search_body("SEARCH-"+self.suffix))
        quote_b = next(q for q in latest.quotes if q.propertyId=="HOTEL-B")
        self.assertEqual(quote_b.price.totalAmount,initial_b.price.totalAmount+15000)
        self.assertNotEqual(quote_b.quoteId,initial_b.quoteId)
        with restarted._connect() as connection:
            self.assertEqual(connection.execute("SELECT total_amount FROM mock_quotes WHERE quote_code=%s",(initial_b.quoteId,)).fetchone()["total_amount"],initial_b.price.totalAmount)
            self.assertEqual(connection.execute("SELECT base_amount FROM mock_night_inventory WHERE product_id=%s AND stay_date=DATE '2026-11-11'",(product_b,)).fetchone()["base_amount"],old_base+15000)


if __name__ == "__main__":
    unittest.main()
