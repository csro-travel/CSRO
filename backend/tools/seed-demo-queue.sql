-- Run explicitly with psql in the dedicated local-chat-demo database, after seed-demo-scenarios.sql.
-- Adds only bookings so the counselor queue has several cases in progress at once.
-- No case, evidence, risk assessment, or night status is fabricated here: cases appear only when
-- the risk batch re-checks these bookings or the customer reports a check-in problem.
\set ON_ERROR_STOP on

BEGIN;
SET LOCAL client_encoding TO 'UTF8';
SET LOCAL search_path TO pg_catalog, public;

DO $seed$
DECLARE
    -- At the D-1 09:00 KST risk cutoff, like YB-001, so the risk batch assesses these hotels.
    assessed_reserved_at constant timestamptz := TIMESTAMPTZ '2026-11-09 09:00:00+09';
    -- After the cutoff, so these are never risk-assessed and arrive only by customer report.
    reported_reserved_at constant timestamptz := TIMESTAMPTZ '2026-11-09 15:00:00+09';
    customer bigint;
BEGIN
    IF current_database() <> 'yeohaengbom_chat_demo'
        OR current_user <> 'yb_chat_demo' OR session_user <> 'yb_chat_demo' THEN
        RAISE EXCEPTION 'This seed may only run in yeohaengbom_chat_demo as yb_chat_demo.';
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended('demo-queue-seed', 0));

    -- The customer demo proxy signs in as demo-customer-001, who must own a booking to report or select.
    SELECT id INTO customer FROM users
    WHERE subject_key = 'demo-customer-001' AND role = 'CUSTOMER' AND is_active;
    IF customer IS NULL THEN
        RAISE EXCEPTION 'Run the local-chat-demo profile base fixture before this seed.';
    END IF;

    CREATE TEMP TABLE queue_seed (n text, hotel_name text, reserved_at timestamptz) ON COMMIT DROP;
    INSERT INTO queue_seed VALUES
        -- Mock hotel reply: first night unavailable, the rest unknown. Found by the risk batch.
        ('201', '애월 선셋 리조트', assessed_reserved_at),
        ('202', '성산 일출 호텔', assessed_reserved_at),
        ('203', '중문 팜 리조트', assessed_reserved_at),
        ('204', '함덕 비치 호텔', assessed_reserved_at),
        ('205', '협재 블루 호텔', assessed_reserved_at),
        -- Mock hotel reply: 202 pending, so the automatic re-check runs out of attempts.
        ('301', '표선 스테이 호텔', reported_reserved_at),
        ('302', '서귀포 올레 호텔', reported_reserved_at),
        ('303', '제주 공항 호텔', reported_reserved_at),
        -- Mock hotel reply: reservation not found (003), hotel service unavailable (005).
        ('003', '제주 센트럴 호텔', reported_reserved_at),
        ('005', '한림 포레스트 호텔', reported_reserved_at);

    IF EXISTS (
        SELECT 1 FROM bookings b JOIN queue_seed s
          ON b.booking_code = 'YB-' || s.n OR b.supplier_booking_id = 'SUP-BOOK-' || s.n
        WHERE b.booking_code <> 'YB-' || s.n OR b.supplier_booking_id <> 'SUP-BOOK-' || s.n
    ) THEN
        RAISE EXCEPTION 'Demo queue booking identifier collision; existing data was left intact.';
    END IF;

    INSERT INTO hotels (hotel_code, name, time_zone, data_source, created_at)
    SELECT 'HOTEL-' || n, hotel_name, 'Asia/Seoul', 'SIMULATED', TIMESTAMPTZ '2026-11-01 09:00:00+09' FROM queue_seed
    ON CONFLICT (hotel_code) DO NOTHING;

    INSERT INTO bookings (booking_code, customer_id, hotel_id, supplier_code, supplier_booking_id,
                          supplier_status, check_in_date, check_out_date, adults, rooms,
                          original_total_amount, currency, created_at, updated_at)
    SELECT 'YB-' || s.n, customer, h.id, 'MOCK_SUPPLIER', 'SUP-BOOK-' || s.n, 'CONFIRMED',
           DATE '2026-11-10', DATE '2026-11-13', 2, 1, 450000, 'KRW', s.reserved_at, s.reserved_at
    FROM queue_seed s JOIN hotels h ON h.hotel_code = 'HOTEL-' || s.n
    ON CONFLICT (booking_code) DO NOTHING;

    INSERT INTO booking_nights (booking_id, stay_date, updated_at)
    SELECT b.id, b.check_in_date + day_offset, b.created_at
    FROM bookings b CROSS JOIN generate_series(0, 2) AS day_offset
    WHERE b.booking_code IN (SELECT 'YB-' || n FROM queue_seed)
    ON CONFLICT (booking_id, stay_date) DO NOTHING;
END
$seed$;

COMMIT;
