-- Run explicitly with psql in the dedicated local-chat-demo database.
-- This fixture is separate from the automatic Flyway migration locations.
\set ON_ERROR_STOP on

BEGIN;
SET LOCAL client_encoding TO 'UTF8';
SET LOCAL search_path TO pg_catalog, public;

DO $seed$
<<seed>>
DECLARE
    booking_code constant text := 'YB-002';
    case_code constant text := 'C-002';
    supplier_booking_code constant text := 'SUP-BOOK-CHAT-002';
    report_code constant text := 'IR-CHAT-DEMO-002';
    report_key constant text := 'chat-demo-checkin-002';
    first_message_code constant text := 'MSG-CHAT-DEMO-002';
    hotel_reply_code constant text := 'CHAT-DEMO-YB-002-HOTEL-REPLY';
    first_night constant date := DATE '2026-11-22';
    reserved_at constant timestamptz := TIMESTAMPTZ '2026-11-10 11:00:00+09';
    reported_at constant timestamptz := TIMESTAMPTZ '2026-11-22 23:48:00+09';
    hotel_replied_at constant timestamptz := TIMESTAMPTZ '2026-11-22 23:54:00+09';
    hotel_reply_received_at constant timestamptz := TIMESTAMPTZ '2026-11-22 23:55:00+09';
    verified_at constant timestamptz := TIMESTAMPTZ '2026-11-22 23:57:00+09';
    task_due_at constant timestamptz := TIMESTAMPTZ '2026-11-23 00:15:00+09';
    report_message constant text := '안녕하세요. 오늘부터 3박 예약했고 지금 제주 오션뷰 호텔 프런트에 와 있는데, 오늘은 내줄 수 있는 객실이 없다고 합니다. 지금 어디에서 묵을 수 있는지 확인해 주실 수 있을까요?';
    hotel_reply constant text := $reply$제주 오션뷰 호텔 예약팀입니다. YB-002 예약을 확인했습니다.

11월 22일(첫날)에는 객실을 제공할 수 없습니다. 11월 23일과 24일은 객실 배정 여부를 확인 중이며, 배정 결과가 나오면 다시 안내드리겠습니다.$reply$;
    customer_pk bigint;
    counselor_pk bigint;
    hotel_pk bigint;
    booking_pk bigint;
    case_pk bigint;
    report_pk bigint;
    evidence_pk bigint;
    assertion_pk bigint;
    first_night_pk bigint;
BEGIN
    IF current_database() <> 'yeohaengbom_chat_demo'
        OR current_user <> 'yb_chat_demo' OR session_user <> 'yb_chat_demo' THEN
        RAISE EXCEPTION 'This seed may only run in yeohaengbom_chat_demo as yb_chat_demo.';
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended(report_key, 0));

    -- The report and its linked first message identify our existing fixture. A repeat
    -- leaves subsequent messages, assignments, and status changes as they are.
    IF EXISTS (
        SELECT 1
        FROM bookings b
        JOIN cases c ON c.booking_id = b.id
        JOIN incident_reports r ON r.booking_id = b.id AND r.case_id = c.id
        JOIN chat_messages m ON m.incident_report_id = r.id AND m.case_id = c.id
        WHERE b.booking_code = seed.booking_code AND b.supplier_booking_id = supplier_booking_code
          AND c.case_code = seed.case_code AND r.report_code = seed.report_code
          AND r.idempotency_key = report_key AND m.message_code = first_message_code
    ) THEN
        RAISE NOTICE 'C-002 already seeded.';
        RETURN;
    END IF;

    IF EXISTS (SELECT 1 FROM bookings b WHERE b.booking_code = seed.booking_code
               OR (b.supplier_code = 'MOCK_SUPPLIER' AND b.supplier_booking_id = supplier_booking_code))
        OR EXISTS (SELECT 1 FROM cases c WHERE c.case_code = seed.case_code)
        OR EXISTS (SELECT 1 FROM incident_reports r WHERE r.report_code = seed.report_code)
        OR EXISTS (SELECT 1 FROM chat_messages m WHERE m.message_code = first_message_code)
        OR EXISTS (SELECT 1 FROM evidence_records e WHERE e.provider_code = 'HOTEL-001'
                   AND e.provider_event_id = hotel_reply_code) THEN
        RAISE EXCEPTION 'C-002 fixture identifier collision; existing data was left intact.';
    END IF;

    SELECT id INTO customer_pk FROM users
    WHERE subject_key = 'demo-customer-002' AND role = 'CUSTOMER' AND is_active;
    SELECT id INTO counselor_pk FROM users
    WHERE subject_key = 'demo-counselor-001' AND role = 'COUNSELOR' AND is_active;
    SELECT id INTO hotel_pk FROM hotels
    WHERE hotel_code = 'HOTEL-001' AND data_source = 'SIMULATED';
    IF customer_pk IS NULL OR counselor_pk IS NULL OR hotel_pk IS NULL THEN
        RAISE EXCEPTION 'Run the local-chat-demo profile base fixture before seeding C-002.';
    END IF;

    INSERT INTO bookings (booking_code, customer_id, hotel_id, supplier_code, supplier_booking_id,
                          supplier_status, check_in_date, check_out_date, adults, rooms,
                          original_total_amount, currency, created_at, updated_at)
    VALUES (booking_code, customer_pk, hotel_pk, 'MOCK_SUPPLIER', supplier_booking_code,
            'CONFIRMED', first_night, first_night + 3, 2, 1, 450000, 'KRW', reserved_at, reserved_at)
    RETURNING id INTO booking_pk;

    INSERT INTO booking_nights (booking_id, stay_date, updated_at)
    SELECT booking_pk, first_night + day_offset, reserved_at
    FROM generate_series(0, 2) AS day_offset;

    INSERT INTO cases (case_code, booking_id, status, priority, created_at, updated_at)
    VALUES (case_code, booking_pk, 'OPEN', 'URGENT', reported_at, verified_at)
    RETURNING id INTO case_pk;

    INSERT INTO incident_reports (report_code, booking_id, case_id, customer_id, issue_type, at_hotel,
                                  message, idempotency_key, request_hash, response_snapshot, received_at)
    VALUES (report_code, booking_pk, case_pk, customer_pk, 'CHECKIN_FAILED', true, report_message,
            report_key, encode(sha256(convert_to(jsonb_build_object(
                'issueType', 'CHECKIN_FAILED', 'atHotel', true, 'message', report_message)::text, 'UTF8')), 'hex'),
            jsonb_build_object('reportId', report_code, 'bookingId', booking_code,
                'chatMessageId', first_message_code, 'case', jsonb_build_object(
                    'caseId', case_code, 'created', true, 'status', 'OPEN', 'priority', 'URGENT'),
                'verificationStatus', 'PENDING', 'receivedAt', reported_at), reported_at)
    RETURNING id INTO report_pk;

    INSERT INTO chat_messages (message_code, case_id, sender_user_id, sender_type, message_type,
                               body, incident_report_id, sent_at)
    VALUES (first_message_code, case_pk, customer_pk, 'CUSTOMER', 'TEXT', report_message, report_pk, reported_at);

    INSERT INTO evidence_records (booking_id, source_type, provider_code, provider_event_id,
                                  source_authenticated, raw_text, occurred_at, received_at,
                                  payload_hash, created_at)
    VALUES (booking_pk, 'HOTEL_API', 'HOTEL-001', hotel_reply_code, true, hotel_reply,
            hotel_replied_at, hotel_reply_received_at, encode(sha256(convert_to(hotel_reply, 'UTF8')), 'hex'),
            hotel_reply_received_at)
    RETURNING id INTO evidence_pk;

    SELECT id INTO STRICT first_night_pk FROM booking_nights n
    WHERE n.booking_id = booking_pk AND n.stay_date = first_night;
    INSERT INTO evidence_nights (booking_id, evidence_id, booking_night_id, reported_status, review_status,
                                 supporting_text, reviewed_by, reviewed_at, created_at)
    VALUES (booking_pk, evidence_pk, first_night_pk, 'UNAVAILABLE', 'ACCEPTED',
            '11월 22일(첫날)에는 객실을 제공할 수 없습니다.', counselor_pk, verified_at, hotel_reply_received_at)
    RETURNING id INTO assertion_pk;

    UPDATE booking_nights SET fulfillment_status = 'UNAVAILABLE', fact_status = 'VERIFIED',
                              accepted_assertion_id = assertion_pk, updated_at = verified_at
    WHERE id = first_night_pk;

    INSERT INTO case_tasks (case_id, booking_id, task_type, status, due_at, created_at, updated_at)
    VALUES (case_pk, booking_pk, 'CONTACT_CUSTOMER', 'TODO', task_due_at, verified_at, verified_at);

    RAISE NOTICE 'Seeded C-002 / YB-002.';
END;
$seed$;

COMMIT;
