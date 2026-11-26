-- Run explicitly with psql in the dedicated local-chat-demo database.
-- This fixture is separate from the automatic Flyway migration locations.
-- Adds only bookings for demo 1 (all nights available) and demo 3 (hotel reply pending).
-- No case, evidence, risk assessment, or night status is fabricated here.
\set ON_ERROR_STOP on

BEGIN;
SET LOCAL client_encoding TO 'UTF8';
SET LOCAL search_path TO pg_catalog, public;

DO $seed$
DECLARE
    -- Before the D-1 09:00 KST risk cutoff, so the risk batch assesses HOTEL-002.
    normal_reserved_at constant timestamptz := TIMESTAMPTZ '2026-11-06 10:00:00+09';
    -- After the cutoff, so YB-004 is never risk-assessed and arrives only by customer report.
    pending_reserved_at constant timestamptz := TIMESTAMPTZ '2026-11-09 15:00:00+09';
    normal_customer bigint;
    pending_customer bigint;
BEGIN
    IF current_database() <> 'yeohaengbom_chat_demo'
        OR current_user <> 'yb_chat_demo' OR session_user <> 'yb_chat_demo' THEN
        RAISE EXCEPTION 'This seed may only run in yeohaengbom_chat_demo as yb_chat_demo.';
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended('demo-scenarios-seed', 0));

    SELECT id INTO normal_customer FROM users
    WHERE subject_key = 'demo-customer-002' AND role = 'CUSTOMER' AND is_active;
    SELECT id INTO pending_customer FROM users
    WHERE subject_key = 'demo-customer-001' AND role = 'CUSTOMER' AND is_active;
    IF normal_customer IS NULL OR pending_customer IS NULL THEN
        RAISE EXCEPTION 'Run the local-chat-demo profile base fixture before this seed.';
    END IF;

    -- seed-chat-demo-case.sql uses YB-002 for a different hotel and stay. The Mock supplier
    -- answers SUP-BOOK-002 as YB-002, so the two fixtures cannot share one database.
    IF EXISTS (
        SELECT 1 FROM bookings b
        JOIN (SELECT 'YB-002' AS code, 'SUP-BOOK-002' AS supplier
              UNION ALL SELECT 'YB-' || n, 'SUP-BOOK-' || n FROM generate_series(101, 109) AS n
              UNION ALL SELECT 'YB-004', 'SUP-BOOK-004') expected
          ON b.booking_code = expected.code OR b.supplier_booking_id = expected.supplier
        WHERE b.booking_code <> expected.code OR b.supplier_booking_id <> expected.supplier
    ) THEN
        RAISE EXCEPTION 'Demo scenario booking identifier collision; existing data was left intact.';
    END IF;

    INSERT INTO hotels (hotel_code, name, time_zone, data_source, created_at)
    VALUES ('HOTEL-002', '서귀포 가든 호텔', 'Asia/Seoul', 'SIMULATED', TIMESTAMPTZ '2026-11-01 09:00:00+09'),
           ('HOTEL-004', '제주 하버 호텔', 'Asia/Seoul', 'SIMULATED', TIMESTAMPTZ '2026-11-01 09:00:00+09')
    ON CONFLICT (hotel_code) DO NOTHING;

    INSERT INTO bookings (booking_code, customer_id, hotel_id, supplier_code, supplier_booking_id,
                          supplier_status, check_in_date, check_out_date, adults, rooms,
                          original_total_amount, currency, created_at, updated_at)
    SELECT seed.code, seed.customer, h.id, 'MOCK_SUPPLIER', seed.supplier, 'CONFIRMED',
           DATE '2026-11-10', DATE '2026-11-13', 2, 1, 450000, 'KRW', seed.reserved_at, seed.reserved_at
    FROM (SELECT 'YB-002' AS code, 'SUP-BOOK-002' AS supplier, 'HOTEL-002' AS hotel,
                 normal_customer AS customer, normal_reserved_at AS reserved_at
          UNION ALL
          SELECT 'YB-' || n, 'SUP-BOOK-' || n, 'HOTEL-002', normal_customer, normal_reserved_at
          FROM generate_series(101, 109) AS n
          UNION ALL
          SELECT 'YB-004', 'SUP-BOOK-004', 'HOTEL-004', pending_customer, pending_reserved_at) seed
    JOIN hotels h ON h.hotel_code = seed.hotel
    ON CONFLICT (booking_code) DO NOTHING;

    INSERT INTO booking_nights (booking_id, stay_date, updated_at)
    SELECT b.id, b.check_in_date + day_offset, b.created_at
    FROM bookings b CROSS JOIN generate_series(0, 2) AS day_offset
    WHERE b.booking_code = 'YB-002' OR b.booking_code = 'YB-004'
       OR b.booking_code IN (SELECT 'YB-' || n FROM generate_series(101, 109) AS n)
    ON CONFLICT (booking_id, stay_date) DO NOTHING;
END
$seed$;

COMMIT;
