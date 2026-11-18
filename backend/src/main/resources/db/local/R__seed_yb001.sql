-- Local development fixture only. Enable db/local only in the local profile.
-- Simulation time: 2026-11-09, Asia/Seoul. This is not production data.
-- Inserts missing rows; does not reset an existing case, evidence, or booking status.

INSERT INTO users (subject_key, display_name, role, created_at)
VALUES
  ('demo-customer-001', '가상 고객 1', 'CUSTOMER', TIMESTAMPTZ '2026-11-09 09:00:00+09'),
  ('demo-customer-002', '가상 고객 2', 'CUSTOMER', TIMESTAMPTZ '2026-11-09 09:00:00+09'),
  ('demo-counselor-001', '가상 상담사 1', 'COUNSELOR', TIMESTAMPTZ '2026-11-09 09:00:00+09')
ON CONFLICT (subject_key) DO NOTHING;

INSERT INTO hotels (hotel_code, name, time_zone, data_source, created_at)
VALUES ('HOTEL-001', '제주 오션뷰 호텔', 'Asia/Seoul', 'SIMULATED',
        TIMESTAMPTZ '2026-11-09 09:00:00+09')
ON CONFLICT (hotel_code) DO NOTHING;

INSERT INTO bookings (
  booking_code, customer_id, hotel_id, supplier_code, supplier_booking_id,
  supplier_status, check_in_date, check_out_date, adults, rooms,
  original_total_amount, currency, created_at, updated_at
)
SELECT
  'YB-001', u.id, h.id, 'MOCK_SUPPLIER', 'SUP-BOOK-001',
  'CONFIRMED', DATE '2026-11-10', DATE '2026-11-13', 2, 1,
  450000, 'KRW', TIMESTAMPTZ '2026-11-09 09:00:00+09',
  TIMESTAMPTZ '2026-11-09 09:00:00+09'
FROM users u CROSS JOIN hotels h
WHERE u.subject_key = 'demo-customer-001' AND h.hotel_code = 'HOTEL-001'
ON CONFLICT (booking_code) DO NOTHING;

INSERT INTO booking_nights (
  booking_id, stay_date, fulfillment_status, fact_status, updated_at
)
SELECT b.id, b.check_in_date + d.day_offset, 'UNKNOWN', 'UNCONFIRMED',
       TIMESTAMPTZ '2026-11-09 09:00:00+09'
FROM bookings b
CROSS JOIN LATERAL generate_series(0, b.check_out_date - b.check_in_date - 1) AS d(day_offset)
WHERE b.booking_code = 'YB-001'
ON CONFLICT (booking_id, stay_date) DO NOTHING;

-- No cases, risks, hotel evidence, or verified unavailability are fabricated here.
-- The second customer and counselor support later ownership/role tests.
