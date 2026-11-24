"""Opt-in supplier-owned PostgreSQL state; never connects to the Spring database.

Only this module lazily imports psycopg. The ordinary Mock keeps its original
database-free setup. Terminal booking rows and all-night inventory changes share
one transaction; returning an HTTP error happens only after that transaction.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass, field
from datetime import date, datetime, timedelta
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
from typing import Mapping
import uuid

from .availability import (AvailabilityQuote, AvailabilityResponse, AvailabilitySearch,
                           CHECK_OUT, FIRST_NIGHT, InvalidSearch, PRODUCTS, Product,
                           QuotePrice, _snapshot_quote_id)
from .booking_models import BookingError, BookingOutcome, BookingRequest

_TABLES = {"mock_products", "mock_night_inventory", "mock_quotes", "mock_booking_requests"}
_DDL = Path(__file__).resolve().parents[1] / "db" / "migration" / "V1__mock_supplier_schema.sql"
_SCHEMA_LOCK = 7720242915
_DISTANCE = {"HOTEL-A": 800, "HOTEL-B": 1400, "HOTEL-C": 2600}


@dataclass(frozen=True)
class MockDatabaseSettings:
    host: str
    port: int
    database: str
    user: str
    password: str = field(repr=False)
    testing: bool = False

    @classmethod
    def from_environment(cls, environment: Mapping[str, str] | None = None, *, testing: bool = False):
        values = os.environ if environment is None else environment
        expected = (25443, "yeohaengbom_mock_test", "yb_mock_test") if testing else (25442, "yeohaengbom_mock", "yb_mock")
        host = values.get("MOCK_DB_HOST", "127.0.0.1")
        port = values.get("MOCK_DB_PORT", str(expected[0]))
        database = values.get("MOCK_DB_NAME", expected[1])
        user = values.get("MOCK_DB_USER", expected[2])
        password = values.get("MOCK_DB_PASSWORD", "")
        if (host != "127.0.0.1" or port != str(expected[0]) or database != expected[1]
                or user != expected[2] or not password.strip()):
            raise RuntimeError("Exact isolated Mock PostgreSQL settings are required before connection")
        return cls(host, expected[0], database, user, password, testing)

    def validate(self):
        # Direct construction cannot bypass the same endpoint guard.
        checked = self.from_environment({"MOCK_DB_HOST": self.host, "MOCK_DB_PORT": str(self.port),
                "MOCK_DB_NAME": self.database, "MOCK_DB_USER": self.user, "MOCK_DB_PASSWORD": self.password}, testing=self.testing)
        if checked != self:
            raise RuntimeError("Invalid isolated Mock settings")


def _json(value) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), default=str)


def _stamp(value: datetime) -> str:
    return value.isoformat()


def _stay(row) -> dict:
    return {"checkInDate": row["check_in_date"].isoformat(), "checkOutDate": row["check_out_date"].isoformat(),
            "adults": row["adults"], "rooms": row["rooms"]}


class PostgresMockStore:
    def __init__(self, settings: MockDatabaseSettings, *, as_of: datetime):
        settings.validate()
        if not isinstance(as_of, datetime) or as_of.tzinfo is None or as_of.utcoffset() is None:
            raise RuntimeError("Mock virtual time requires an explicit timezone")
        self.settings, self.as_of = settings, as_of
        self._bootstrap()

    def _connect(self):
        try:
            import psycopg
            from psycopg.rows import dict_row
        except ImportError:
            raise RuntimeError("Install requirements-stateful.txt before enabling Mock PostgreSQL") from None
        try:
            connection = psycopg.connect(host=self.settings.host, port=self.settings.port,
                    dbname=self.settings.database, user=self.settings.user, password=self.settings.password,
                    connect_timeout=3, autocommit=True, row_factory=dict_row)
        except Exception:
            raise RuntimeError("Could not connect to the isolated Mock database") from None
        try:
            identity = connection.execute("SELECT current_database() AS db, current_user AS username").fetchone()
            if identity != {"db": self.settings.database, "username": self.settings.user}:
                raise RuntimeError("Mock database identity mismatch before schema or data access")
        except Exception:
            connection.close()
            raise RuntimeError("Mock database identity could not be verified before schema or data access") from None
        return connection

    @staticmethod
    def _fingerprint(connection) -> str:
        columns = connection.execute("""
            SELECT table_name,column_name,data_type,udt_name,is_nullable,column_default,is_identity,
                   identity_generation,character_maximum_length,numeric_precision,numeric_scale
            FROM information_schema.columns WHERE table_schema='public' ORDER BY table_name,ordinal_position
            """).fetchall()
        constraints = connection.execute("""
            SELECT t.relname AS table_name,c.conname,pg_get_constraintdef(c.oid,true) AS definition
            FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace
            WHERE n.nspname='public' ORDER BY t.relname,c.conname
            """).fetchall()
        indexes = connection.execute("SELECT tablename,indexname,indexdef FROM pg_indexes WHERE schemaname='public' ORDER BY tablename,indexname").fetchall()
        return hashlib.sha256(_json([columns, constraints, indexes]).encode()).hexdigest()

    def _bootstrap(self):
        ddl = _DDL.read_text(encoding="utf-8")
        ddl_hash = hashlib.sha256(ddl.encode()).hexdigest()
        with self._connect() as connection, connection.transaction():
            connection.execute("SELECT pg_advisory_xact_lock(%s)", (_SCHEMA_LOCK,))
            tables = {row["tablename"] for row in connection.execute("SELECT tablename FROM pg_tables WHERE schemaname='public'").fetchall()}
            if not tables:
                connection.execute(ddl)
                marker = "csro-mock-schema-v1:" + ddl_hash + ":" + self._fingerprint(connection)
                # A schema comment records migration/catalog hashes without a fifth business table.
                from psycopg import sql
                connection.execute(sql.SQL("COMMENT ON SCHEMA public IS {}").format(sql.Literal(marker)))
            elif tables != _TABLES:
                raise RuntimeError("Unexpected Mock schema; no automatic migration, repair or reset is allowed")
            marker = connection.execute("SELECT obj_description('public'::regnamespace,'pg_namespace') AS value").fetchone()["value"]
            expected = "csro-mock-schema-v1:" + ddl_hash + ":" + self._fingerprint(connection)
            if marker != expected:
                raise RuntimeError("Mock schema differs from its immutable migration; no automatic repair is allowed")
            self._seed_missing(connection)

    def _seed_missing(self, connection):
        for index, product in enumerate(PRODUCTS):
            values = (f"HOTEL-{product.letter}", f"{product.letter}호텔", f"ROOM-{product.letter}-{product.room_type}",
                      product.room_name, f"RATE-{product.letter}-{product.rate_plan}", 2, product.breakfast,
                      "NON_REFUNDABLE", Decimal("33.500000") + Decimal(index) * Decimal("0.005000"), Decimal("126.500000"))
            connection.execute("""
                INSERT INTO mock_products (property_code,property_name,room_type_code,room_name,rate_plan_code,
                    max_adults,breakfast_included,cancellation_policy,latitude,longitude)
                VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) ON CONFLICT DO NOTHING
                """, values)
            row = connection.execute("SELECT * FROM mock_products WHERE property_code=%s AND room_type_code=%s", (values[0], values[2])).fetchone()
            fields = ("property_code","property_name","room_type_code","room_name","rate_plan_code","max_adults",
                      "breakfast_included","cancellation_policy","latitude","longitude")
            if row is None or tuple(row[field] for field in fields) != values:
                raise RuntimeError("Mock product metadata is immutable; an existing fixture does not match")
            for offset, stock in enumerate(product.night_stock):
                connection.execute("""
                    INSERT INTO mock_night_inventory (product_id,stay_date,available_rooms,base_amount,mandatory_fees_amount)
                    VALUES (%s,%s,%s,%s,%s) ON CONFLICT DO NOTHING
                    """, (row["id"], FIRST_NIGHT + timedelta(days=offset), stock, product.daily_base, product.daily_fee))

    def search(self, body: AvailabilitySearch) -> AvailabilityResponse:
        stay = body.stay
        if stay.checkOutDate <= stay.checkInDate:
            raise InvalidSearch("INVALID_STAY_DATES", "체크아웃 날짜는 체크인 날짜보다 늦어야 합니다.")
        if stay.adults < 1 or stay.rooms < 1:
            raise InvalidSearch("INVALID_OCCUPANCY", "성인과 객실 수는 각각 1 이상이어야 합니다.")
        for attempt in range(3):
            try:
                return self._search_once(body)
            except Exception as failure:
                if getattr(failure, "sqlstate", None) not in {"40001", "40P01"} or attempt == 2:
                    raise
        raise RuntimeError("Mock quote search could not obtain a stable snapshot")

    def _search_once(self, body: AvailabilitySearch) -> AvailabilityResponse:
        stay = body.stay
        checked, expires = self.as_of, self.as_of + timedelta(minutes=10)
        quotes = []
        supported = body.originPropertyId == "HOTEL-001" and FIRST_NIGHT <= stay.checkInDate < stay.checkOutDate <= CHECK_OUT
        with self._connect() as connection, connection.transaction():
            connection.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
            if supported:
                for product in connection.execute("SELECT * FROM mock_products WHERE property_code IN ('HOTEL-A','HOTEL-B','HOTEL-C') ORDER BY property_code").fetchall():
                    nights = connection.execute("""
                        SELECT stay_date,available_rooms,base_amount,mandatory_fees_amount FROM mock_night_inventory
                        WHERE product_id=%s AND stay_date>=%s AND stay_date<%s ORDER BY stay_date
                        """, (product["id"], stay.checkInDate, stay.checkOutDate)).fetchall()
                    if len(nights) != (stay.checkOutDate-stay.checkInDate).days:
                        continue
                    available = min(night["available_rooms"] for night in nights)
                    distance = _DISTANCE[product["property_code"]]
                    base = sum(night["base_amount"] for night in nights) * stay.rooms
                    fees = sum(night["mandatory_fees_amount"] for night in nights) * stay.rooms
                    if (available < stay.rooms or product["max_adults"] * stay.rooms < stay.adults
                            or distance > body.filters.radiusMeters or (body.filters.maxAverageNightlyAmount is not None
                            and base+fees > body.filters.maxAverageNightlyAmount*len(nights))):
                        continue
                    quote = AvailabilityQuote(quoteId="pending", source="SIMULATED_SUPPLIER",
                        propertyId=product["property_code"], propertyName=product["property_name"], distanceMeters=distance,
                        roomTypeId=product["room_type_code"], roomName=product["room_name"], ratePlanId=product["rate_plan_code"],
                        maxAdults=product["max_adults"], breakfastIncluded=product["breakfast_included"], availableRoomCount=available,
                        price=QuotePrice(baseAmount=base,mandatoryFeesAmount=fees,totalAmount=base+fees,currency="KRW"),
                        cancellationPolicy=product["cancellation_policy"], checkedAt=_stamp(checked), expiresAt=_stamp(expires))
                    letter = product["property_code"][-1]
                    canonical = next(p for p in PRODUCTS if p.letter == letter)
                    reference = (checked.isoformat() == "2026-11-09T10:15:00+09:00"
                                 and expires.isoformat() == "2026-11-09T10:25:00+09:00"
                                 and all(n["base_amount"] == canonical.daily_base and n["mandatory_fees_amount"] == canonical.daily_fee
                                         and n["available_rooms"] == canonical.night_stock[(n["stay_date"]-FIRST_NIGHT).days] for n in nights))
                    if reference:
                        quote_id = _snapshot_quote_id(body, canonical, base, fees, available)
                    else:
                        identity = {"search":body.model_dump(mode="json",exclude_none=True),"quote":quote.model_dump(exclude={"quoteId"}),
                                    "nights":[{**n,"stay_date":n["stay_date"].isoformat()} for n in nights]}
                        quote_id = "QUOTE-" + letter + "-" + hashlib.sha256(_json(identity).encode()).hexdigest()
                    quote = quote.model_copy(update={"quoteId":quote_id})
                    values = (quote_id,product["id"],stay.checkInDate,stay.checkOutDate,stay.adults,stay.rooms,
                              base,fees,base+fees,"KRW",checked,expires)
                    connection.execute("""
                        INSERT INTO mock_quotes (quote_code,product_id,check_in_date,check_out_date,adults,rooms,base_amount,
                            mandatory_fees_amount,total_amount,currency,checked_at,expires_at)
                        VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) ON CONFLICT (quote_code) DO NOTHING
                        """, values)
                    saved = connection.execute("SELECT * FROM mock_quotes WHERE quote_code=%s", (quote_id,)).fetchone()
                    fields = ("quote_code","product_id","check_in_date","check_out_date","adults","rooms","base_amount",
                              "mandatory_fees_amount","total_amount","currency","checked_at","expires_at")
                    if saved is None or tuple(saved[f] for f in fields) != values:
                        raise RuntimeError("A Mock quote identifier cannot overwrite another immutable snapshot")
                    quotes.append(quote)
        return AvailabilityResponse(searchRequestId=body.searchRequestId,searchStatus="COMPLETED",stay=stay,
                                    checkedAt=_stamp(checked),currency="KRW",quotes=quotes)

    def book(self, request_id: str, body: dict) -> BookingOutcome:
        body = BookingRequest.model_validate(body).model_dump()
        request_hash = hashlib.sha256(_json(body).encode()).hexdigest()
        with self._connect() as connection, connection.transaction():
            connection.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s,0))", (request_id,))
            previous = connection.execute("SELECT * FROM mock_booking_requests WHERE request_code=%s", (request_id,)).fetchone()
            if previous is not None:
                if previous["request_hash"] != request_hash:
                    raise BookingError(409,"IDEMPOTENCY_KEY_REUSED","같은 예약 요청번호에 다른 본문을 사용할 수 없습니다.")
                return BookingOutcome(previous["http_status"], previous["response_payload"], True)
            quote = connection.execute("""
                SELECT q.*,p.property_code,p.max_adults FROM mock_quotes q JOIN mock_products p ON p.id=q.product_id
                WHERE q.quote_code=%s FOR UPDATE OF q
                """, (body["quoteId"],)).fetchone()
            if quote is None:
                raise BookingError(404,"QUOTE_NOT_FOUND","발급된 견적을 찾을 수 없습니다. 새 검색이 필요합니다.")
            already = connection.execute("SELECT 1 FROM mock_booking_requests WHERE quote_id=%s AND status='CONFIRMED'", (quote["id"],)).fetchone()
            if already:
                return self._failed(connection,request_id,request_hash,body,quote,409,"QUOTE_ALREADY_BOOKED","이미 예약에 사용된 견적입니다. 기존 요청 결과를 확인하세요.")
            if not quote["checked_at"] <= self.as_of < quote["expires_at"]:
                return self._failed(connection,request_id,request_hash,body,quote,409,"QUOTE_EXPIRED","선택한 견적이 만료되어 예약을 진행하지 않았습니다.")
            if (quote["adults"] > quote["max_adults"] * quote["rooms"] or quote["check_out_date"] <= quote["check_in_date"]):
                return self._failed(connection,request_id,request_hash,body,quote,422,"QUOTE_STAY_MISMATCH","견적의 기간·인원·객실 조건을 확인할 수 없습니다.")
            nights = connection.execute("""
                SELECT * FROM mock_night_inventory WHERE product_id=%s AND stay_date>=%s AND stay_date<%s
                ORDER BY stay_date FOR UPDATE
                """, (quote["product_id"],quote["check_in_date"],quote["check_out_date"])).fetchall()
            if len(nights) != (quote["check_out_date"]-quote["check_in_date"]).days:
                return self._failed(connection,request_id,request_hash,body,quote,409,"OUT_OF_STOCK","요청한 모든 숙박일의 객실을 확보할 수 없습니다.")
            current = sum(n["base_amount"]+n["mandatory_fees_amount"] for n in nights) * quote["rooms"]
            if body["expectedTotalAmount"] != quote["total_amount"] or current != body["expectedTotalAmount"]:
                return self._failed(connection,request_id,request_hash,body,quote,409,"PRICE_CHANGED","선택한 숙소의 가격이 변경되어 예약을 진행하지 않았습니다.",current)
            if any(n["available_rooms"] < quote["rooms"] for n in nights):
                return self._failed(connection,request_id,request_hash,body,quote,409,"OUT_OF_STOCK","요청한 모든 숙박일의 객실을 확보할 수 없습니다.")
            response = {"requestId":request_id,"status":"CONFIRMED","supplierBookingId":"ALT-"+uuid.uuid4().hex,
                        "quoteId":body["quoteId"],"propertyId":quote["property_code"],"stay":_stay(quote),
                        "confirmedPrice":{"totalAmount":body["expectedTotalAmount"],"currency":"KRW"},"confirmedAt":_stamp(self.as_of)}
            self._save(connection,request_id,request_hash,body,quote,"CONFIRMED",201,response)
            for night in nights:
                changed = connection.execute("""
                    UPDATE mock_night_inventory SET available_rooms=available_rooms-%s
                    WHERE product_id=%s AND stay_date=%s AND available_rooms>=%s
                    """, (quote["rooms"],quote["product_id"],night["stay_date"],quote["rooms"])).rowcount
                if changed != 1:
                    raise RuntimeError("Mock all-night inventory update did not retain its locked conditions")
            return BookingOutcome(201,response)

    def _failed(self, connection, request_id, request_hash, body, quote, status, code, detail, current=None):
        payload = {"type":"about:blank","title":"Not Found" if status==404 else "Unprocessable Content" if status==422 else "Conflict",
                   "status":status,"detail":detail,"code":code,"traceId":"req-"+uuid.uuid4().hex}
        if current is not None:
            payload.update(currentTotalAmount=current,currency="KRW")
        self._save(connection,request_id,request_hash,body,quote,"FAILED",status,payload)
        return BookingOutcome(status,payload)

    @staticmethod
    def _save(connection, request_id, request_hash, body, quote, state, status, payload):
        connection.execute("""
            INSERT INTO mock_booking_requests (request_code,quote_id,guest_reference,request_hash,expected_amount,status,
                supplier_booking_id,confirmed_amount,http_status,response_payload)
            VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s::jsonb)
            """, (request_id,quote["id"],body["guestReference"],request_hash,body["expectedTotalAmount"],state,
                    payload.get("supplierBookingId"),body["expectedTotalAmount"] if state=="CONFIRMED" else None,status,_json(payload)))

    def result(self, request_id: str) -> dict:
        with self._connect() as connection, connection.transaction():
            connection.execute("SET TRANSACTION READ ONLY")
            row = connection.execute("""
                SELECT r.*,q.quote_code,p.property_code FROM mock_booking_requests r JOIN mock_quotes q ON q.id=r.quote_id
                JOIN mock_products p ON p.id=q.product_id WHERE r.request_code=%s
                """, (request_id,)).fetchone()
            if row is None:
                raise BookingError(404,"BOOKING_REQUEST_NOT_FOUND","해당 요청 식별자의 처리 이력을 찾을 수 없습니다.")
            payload = row["response_payload"]
            if row["status"] == "CONFIRMED":
                return {key:payload[key] for key in ("requestId","status","supplierBookingId","quoteId","propertyId","confirmedPrice","confirmedAt")}
            result = {"requestId":request_id,"status":"FAILED","supplierBookingId":None,"quoteId":row["quote_code"],
                      "propertyId":row["property_code"],"confirmedPrice":None,"confirmedAt":None,
                      "failureCode":payload["code"],"failedAt":_stamp(row["created_at"])}
            if payload["code"] == "PRICE_CHANGED":
                result.update(currentTotalAmount=payload["currentTotalAmount"],currency="KRW")
            return result


def store_from_environment() -> PostgresMockStore:
    value = os.environ.get("MOCK_BOOKING_AS_OF", "")
    try:
        as_of = datetime.fromisoformat(value)
        if as_of.tzinfo is None or as_of.utcoffset() is None:
            raise ValueError()
    except ValueError:
        raise RuntimeError("MOCK_BOOKING_AS_OF must be an explicit ISO timestamp with timezone") from None
    return PostgresMockStore(MockDatabaseSettings.from_environment(),as_of=as_of)
