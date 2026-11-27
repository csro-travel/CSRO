-- Run after Flyway in the dedicated local-chat-demo database, before opening the demo UI.
\set ON_ERROR_STOP on
BEGIN;

DO $demo$
DECLARE
    booking_key bigint;
    prior_start date;
    prior_end date;
BEGIN
    IF current_database() <> 'yeohaengbom_chat_demo' OR current_user <> 'yb_chat_demo' THEN
        RAISE EXCEPTION 'This fixture requires the dedicated local-chat-demo database and role';
    END IF;

    SELECT id, check_in_date, check_out_date INTO STRICT booking_key, prior_start, prior_end
    FROM bookings
    WHERE booking_code = 'YB-001' AND supplier_booking_id = 'SUP-BOOK-001'
    FOR UPDATE;

    IF (prior_start, prior_end) = (DATE '2026-11-10', DATE '2026-11-13') THEN
        IF EXISTS (SELECT 1 FROM cases WHERE booking_id = booking_key)
           OR (SELECT count(*) FROM booking_nights WHERE booking_id = booking_key) <> 3
           OR EXISTS (SELECT 1 FROM booking_nights WHERE booking_id = booking_key
                      AND (stay_date < prior_start OR stay_date >= prior_end OR fact_status <> 'UNCONFIRMED'
                           OR fulfillment_status <> 'UNKNOWN' OR accepted_assertion_id IS NOT NULL)) THEN
            RAISE EXCEPTION 'The November booking has demo activity. Use a fresh database for this fixture';
        END IF;

        UPDATE bookings
        SET check_in_date = DATE '2026-12-10', check_out_date = DATE '2026-12-13', updated_at = now()
        WHERE id = booking_key;

        UPDATE booking_nights
        SET stay_date = DATE '2026-12-10' + (stay_date - prior_start), updated_at = now()
        WHERE booking_id = booking_key;
    ELSIF (prior_start, prior_end) <> (DATE '2026-12-10', DATE '2026-12-13') THEN
        RAISE EXCEPTION 'YB-001 has an unexpected stay period';
    END IF;

    IF EXISTS (SELECT 1 FROM cases WHERE case_code = 'C-001' AND booking_id <> booking_key)
       OR EXISTS (SELECT 1 FROM cases WHERE booking_id = booking_key AND case_code <> 'C-001' AND status <> 'CLOSED') THEN
        RAISE EXCEPTION 'C-001 conflicts with an existing case';
    END IF;

    INSERT INTO cases (case_code, booking_id, status, priority)
    VALUES ('C-001', booking_key, 'OPEN', 'NORMAL')
    ON CONFLICT (case_code) DO NOTHING;
END
$demo$;

SELECT c.case_code, b.booking_code, b.check_in_date, b.check_out_date,
       array_agg(n.stay_date ORDER BY n.stay_date) AS stay_dates
FROM cases c JOIN bookings b ON b.id = c.booking_id
JOIN booking_nights n ON n.booking_id = b.id
WHERE c.case_code = 'C-001'
GROUP BY c.case_code, b.booking_code, b.check_in_date, b.check_out_date;
COMMIT;
