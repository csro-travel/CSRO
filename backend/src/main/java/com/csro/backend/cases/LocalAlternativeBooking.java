package com.csro.backend.cases;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@Service
@Profile("local-chat-demo")
@ConditionalOnProperty(name = "airbnb.demo.booking.enabled", havingValue = "true")
class LocalAlternativeBooking {
    private static final String DEMO = "ABSIM-";
    private final JdbcClient jdbc;
    private final CaseAccess access;

    LocalAlternativeBooking(JdbcClient jdbc, CaseAccess access, ApplicationContext context) {
        if (!context.containsBean("localChatDemoAuthentication")) {
            throw new IllegalStateException("Airbnb booking demo requires verified local-chat-demo");
        }
        this.jdbc = jdbc;
        this.access = access;
    }

    private record Context(long id, long customer, String status, LocalDate checkInDate,
                           LocalDate checkOutDate, int adults, int rooms) {
        void requireOpen() {
            if ("CLOSED".equals(status)) throw conflict("CASE_CLOSED");
        }
    }

    record Action(String messageId, String propertyName, long totalAmount, boolean available, String bookingId) {}

    private CaseAccess.ActiveUser counselor(Principal principal, String caseId) {
        var user = access.chatUser(principal, caseId);
        if (!user.isCounselor()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
        return user;
    }

    private Context context(Principal principal, String caseId) {
        counselor(principal, caseId);
        return context(caseId, false);
    }

    private Context context(String caseId, boolean lock) {
        if (lock) lockBooking(caseId);
        return jdbc.sql("""
                SELECT c.id, b.customer_id AS customer, c.status, b.check_in_date, b.check_out_date, b.adults, b.rooms
                FROM cases c JOIN bookings b ON b.id = c.booking_id
                WHERE c.case_code = :case
                """ + (lock ? " FOR UPDATE OF c" : ""))
                .param("case", caseId).query(Context.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND"));
    }

    void lockBooking(String caseId) {
        jdbc.sql("""
                SELECT b.id FROM bookings b JOIN cases c ON c.booking_id = b.id
                WHERE c.case_code = :case FOR UPDATE OF b
                """).param("case", caseId).query(Long.class).optional();
    }

    void stage(String caseId, long counselorId, UUID searchId, UUID requestId, List<String> listingIds,
               JsonNode result, String sentAt) {
        var ctx = context(caseId, false);
        ctx.requireOpen();
        String code = DEMO + requestId;
        if (jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM replacement_requests WHERE case_id = :case
                    UNION ALL
                    SELECT 1 FROM offers WHERE case_id = :case AND status = 'ACTIVE'
                      AND offer_code NOT LIKE 'ABSIM-%'
                )
                """).param("case", ctx.id()).query(Boolean.class).single()) {
            throw conflict("AIRBNB_DEMO_CASE_ALREADY_IN_USE");
        }
        long job = jdbc.sql("SELECT id FROM jobs WHERE job_code = :code AND case_id = :case")
                .param("case", ctx.id()).param("code", "LOCAL-ALT-" + searchId).query(Long.class).single();
        jdbc.sql("UPDATE offers SET status = 'SUPERSEDED' WHERE case_id = :case AND status = 'ACTIVE' AND offer_code LIKE 'ABSIM-%'")
                .param("case", ctx.id()).update();
        jdbc.sql("UPDATE alternative_selections SET superseded_at = clock_timestamp() WHERE case_id = :case AND superseded_at IS NULL AND offer_id IN (SELECT id FROM offers WHERE offer_code LIKE 'ABSIM-%')")
                .param("case", ctx.id()).update();
        long offer = jdbc.sql("""
                INSERT INTO offers (offer_code, case_id, sent_by, sent_at, idempotency_key, request_hash)
                VALUES (:code, :case, :user, CAST(:sentAt AS timestamptz), :code, :hash) RETURNING id
                """).param("code", code).param("case", ctx.id()).param("user", counselorId)
                .param("sentAt", sentAt).param("hash", hash(code)).query(Long.class).single();
        for (int index = 0; index < listingIds.size(); index++) {
            JsonNode candidate = candidate(result.path("top_recommendations"), listingIds.get(index));
            long quote = quote(candidate, result, ctx, job, requestId, index);
            jdbc.sql("INSERT INTO offer_items (offer_id, quote_id, display_order, case_id) VALUES (:offer, :quote, :rank, :case)")
                    .param("offer", offer).param("quote", quote).param("rank", index + 1).param("case", ctx.id()).update();
        }
    }

    private static JsonNode candidate(JsonNode candidates, String listingId) {
        for (JsonNode item : candidates) {
            if (listingId.equals(item.path("listing_id").asText())) return item;
        }
        throw invalid();
    }

    private long quote(JsonNode item, JsonNode result, Context ctx, long job, UUID requestId, int index) {
        String name = item.path("korean_name").asString("");
        if (name.isBlank()) name = item.path("name").asString("");
        JsonNode policy = "incident_night".equals(item.path("search_scope").asText())
                ? result.path("fallback_policy") : result.path("policy");
        LocalDate start;
        LocalDate end;
        try {
            start = LocalDate.parse(item.path("check_in_date").asString(policy.path("check_in_date").asText()));
            end = LocalDate.parse(item.path("check_out_date").asString(policy.path("check_out_date").asText()));
        } catch (java.time.format.DateTimeParseException exception) {
            throw invalid();
        }
        var price = item.path("total_price_krw");
        var distance = item.path("distance_km");
        if (name.isBlank() || name.length() > 160 || name.indexOf(0) >= 0 || !price.isIntegralNumber()
                || !price.canConvertToLong() || price.asLong() < 0 || !distance.isNumber()
                || distance.asDouble() < 0 || distance.asDouble() > Integer.MAX_VALUE / 1000.0
                || !start.isBefore(end) || start.isBefore(ctx.checkInDate()) || end.isAfter(ctx.checkOutDate())) {
            throw invalid();
        }
        String hotelCode = DEMO + hash(item.path("listing_id").asText()).substring(0, 34);
        jdbc.sql("""
                INSERT INTO hotels (hotel_code, name, data_source) VALUES (:code, :name, 'AIRBNB_DEMO')
                ON CONFLICT (hotel_code) DO NOTHING
                """).param("code", hotelCode).param("name", name).update();
        return jdbc.sql("""
                INSERT INTO alternative_quotes (quote_code, source_code, search_job_id, hotel_id,
                    room_type_code, rate_plan_code, check_in_date, check_out_date, adults, rooms,
                    base_amount, mandatory_fees_amount, total_amount, distance_meters, available_room_count,
                    max_adults, cancellation_policy, checked_at, expires_at, source_payload, case_id, property_name, room_name)
                SELECT :code, 'AIRBNB_DEMO', :job, id, 'DEMO', 'DEMO', :start, :end, :adults, :rooms,
                       :price, 0, :price, :distance, :rooms, :adults, 'UNCONFIRMED', now(),
                       now() + interval '30 minutes', CAST(:payload AS jsonb), :case, :name, '시연 객실'
                FROM hotels WHERE hotel_code = :hotelCode RETURNING id
                """).param("code", DEMO + requestId + "-" + index).param("job", job)
                .param("start", start).param("end", end).param("adults", ctx.adults()).param("rooms", ctx.rooms())
                .param("price", price.asLong()).param("distance", Math.round(distance.asDouble() * 1000))
                .param("payload", item.toString()).param("case", ctx.id()).param("name", name)
                .param("hotelCode", hotelCode).query(Long.class).single();
    }

    List<String> dueCases() {
        return jdbc.sql("""
                SELECT c.case_code FROM offers o JOIN cases c ON c.id = o.case_id
                JOIN offer_items i ON i.offer_id = o.id AND i.display_order = 1
                JOIN alternative_quotes q ON q.id = i.quote_id
                JOIN bookings b ON b.id = c.booking_id
                JOIN users u ON u.id = b.customer_id AND u.is_active AND u.role = 'CUSTOMER'
                WHERE o.offer_code LIKE 'ABSIM-%' AND o.status = 'ACTIVE' AND c.status <> 'CLOSED'
                  AND o.sent_at <= clock_timestamp() - interval '5 seconds' AND q.expires_at > clock_timestamp()
                  AND NOT EXISTS (SELECT 1 FROM alternative_selections s WHERE s.offer_id = o.id)
                ORDER BY o.sent_at LIMIT 50
                """).query(String.class).list();
    }

    @Transactional
    public void selectFirst(String caseId) {
        var ctx = context(caseId, true);
        if ("CLOSED".equals(ctx.status())) return;
        jdbc.sql("""
                WITH chosen AS (
                    SELECT o.id, o.offer_code, o.sent_by, q.id AS quote_id, q.property_name
                    FROM offers o JOIN offer_items i ON i.offer_id = o.id AND i.display_order = 1
                    JOIN alternative_quotes q ON q.id = i.quote_id
                    JOIN users u ON u.id = :customer AND u.is_active AND u.role = 'CUSTOMER'
                    WHERE o.case_id = :case AND o.offer_code LIKE 'ABSIM-%' AND o.status = 'ACTIVE'
                      AND o.sent_at <= clock_timestamp() - interval '5 seconds' AND q.expires_at > clock_timestamp()
                      AND NOT EXISTS (SELECT 1 FROM alternative_selections s WHERE s.offer_id = o.id)
                ), selected AS (
                    INSERT INTO alternative_selections (selection_code, case_id, offer_id, quote_id, selected_by,
                                                        selected_at, idempotency_key, request_hash)
                    SELECT offer_code, :case, id, quote_id, :customer, clock_timestamp(), offer_code,
                           encode(sha256(convert_to(offer_code, 'UTF8')), 'hex') FROM chosen
                    RETURNING offer_id, selected_at
                )
                INSERT INTO chat_messages (message_code, case_id, sender_user_id, sender_type, message_type, body, sent_at)
                SELECT 'SEL-' || substring(offer_code from 7), :case, :customer, 'CUSTOMER', 'TEXT',
                       property_name || '으로 선택할게요.', selected_at
                FROM selected JOIN chosen ON chosen.id = selected.offer_id
                UNION ALL
                SELECT 'ACT-' || substring(offer_code from 7), :case, sent_by, 'COUNSELOR', 'TEXT',
                       '해당 숙소 예약을 진행하도록 하겠습니다.', selected_at + interval '1 microsecond'
                FROM selected JOIN chosen ON chosen.id = selected.offer_id
                """).param("case", ctx.id()).param("customer", ctx.customer()).update();
    }

    Action action(Principal principal, String caseId, UUID offerId) {
        var ctx = context(principal, caseId);
        return jdbc.sql("""
                SELECT 'ACT-' || substring(o.offer_code from 7) AS message_id, q.property_name, q.total_amount,
                       (r.id IS NULL AND o.status = 'ACTIVE' AND s.superseded_at IS NULL
                        AND q.expires_at > clock_timestamp() AND :open) AS available,
                       b.booking_code AS booking_id
                FROM alternative_selections s JOIN offers o ON o.id = s.offer_id
                JOIN alternative_quotes q ON q.id = s.quote_id
                LEFT JOIN replacement_requests r ON r.selection_id = s.id
                LEFT JOIN bookings b ON b.supplier_code = 'AIRBNB_DEMO' AND b.supplier_booking_id = r.supplier_booking_id
                WHERE s.case_id = :case AND o.offer_code = :offer
                """).param("case", ctx.id()).param("offer", DEMO + offerId)
                .param("open", !"CLOSED".equals(ctx.status())).query(Action.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AIRBNB_DEMO_ACTION_NOT_FOUND"));
    }

    @Transactional
    public String book(Principal principal, String caseId, UUID offerId) {
        var user = counselor(principal, caseId);
        var ctx = context(caseId, true);
        String requestCode = "ADB-" + offerId;
        String bookingCode = "YB-" + offerId;
        var existing = jdbc.sql("""
                SELECT b.booking_code FROM replacement_requests r JOIN bookings b
                  ON b.supplier_code = 'AIRBNB_DEMO' AND b.supplier_booking_id = r.supplier_booking_id
                WHERE r.case_id = :case AND r.request_code = :code
                """).param("case", ctx.id()).param("code", requestCode).query(String.class).optional();
        if (existing.isPresent()) return existing.get();
        ctx.requireOpen();
        return jdbc.sql("""
                WITH chosen AS (
                    SELECT s.id AS selection_id, q.*
                    FROM alternative_selections s JOIN offers o ON o.id = s.offer_id
                    JOIN alternative_quotes q ON q.id = s.quote_id
                    WHERE s.case_id = :case AND s.selected_by = :customer AND o.offer_code = :offer
                      AND o.status = 'ACTIVE' AND s.superseded_at IS NULL AND q.expires_at > clock_timestamp()
                ), confirmed AS (
                    INSERT INTO replacement_requests (request_code, case_id, selection_id, idempotency_key, request_hash,
                        status, expected_amount, confirmed_amount, supplier_booking_id, request_payload, response_payload,
                        confirmed_at, requested_by)
                    SELECT :code, :case, selection_id, :code, encode(sha256(convert_to(:code, 'UTF8')), 'hex'),
                           'CONFIRMED', total_amount, total_amount, :code, jsonb_build_object('quoteId', quote_code),
                           jsonb_build_object('simulation', true), now(), :user FROM chosen
                    RETURNING selection_id, supplier_booking_id
                ), booked AS (
                    INSERT INTO bookings (booking_code, customer_id, hotel_id, supplier_code, supplier_booking_id,
                        check_in_date, check_out_date, adults, rooms, original_total_amount)
                    SELECT :bookingCode, :customer, hotel_id, 'AIRBNB_DEMO', supplier_booking_id,
                           check_in_date, check_out_date, adults, rooms, total_amount
                    FROM chosen JOIN confirmed USING (selection_id)
                    RETURNING id, booking_code, check_in_date, check_out_date
                ), nights AS (
                    INSERT INTO booking_nights (booking_id, stay_date)
                    SELECT id, check_in_date + day_offset FROM booked
                    CROSS JOIN LATERAL generate_series(0, check_out_date - check_in_date - 1) AS days(day_offset)
                )
                SELECT booking_code FROM booked
                """).param("case", ctx.id()).param("customer", ctx.customer()).param("user", user.id())
                .param("offer", DEMO + offerId).param("code", requestCode).param("bookingCode", bookingCode)
                .query(String.class).optional()
                .orElseThrow(() -> conflict("REPLACEMENT_NOT_AVAILABLE"));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_DEMO_OFFER");
    }

    private static ResponseStatusException conflict(String code) {
        return new ResponseStatusException(HttpStatus.CONFLICT, code);
    }
}
