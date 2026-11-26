package com.csro.backend.closure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.csro.backend.offers.OfferSelectionService;
import com.csro.backend.quotes.QuoteContracts.*;
import com.csro.backend.quotes.QuoteRankingClient;
import com.csro.backend.quotes.QuoteSearchService;
import com.csro.backend.quotes.QuoteSupplierClient;
import com.csro.backend.replacements.ReplacementService;
import com.csro.backend.replacements.ReplacementSupplierClient;
import com.csro.backend.replacements.ReplacementSupplierClient.FrozenRequest;
import com.csro.backend.replacements.ReplacementSupplierClient.Outcome;
import java.security.Principal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Core closing rule on real V1 rows: a virtual booking alone never closes a case. Check-in, coverage of
 * every original night and every required task must all hold. Uses the dedicated replacement test DB
 * because closure only exists on top of its booking ledger; only supplier/Agent HTTP is doubled.
 */
@EnabledIfEnvironmentVariable(named = "REPLACEMENT_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClosureIntegrationTest {
    private static final String URL = "jdbc:postgresql://127.0.0.1:25444/yeohaengbom_replacements_test";
    private static final String DATABASE = "yeohaengbom_replacements_test";
    private static final String USER = "yb_replacement_test";
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-09T10:20:00+09:00");
    private static final OffsetDateTime CHECKED = AT.minusMinutes(5);
    private static final OffsetDateTime EXPIRES = AT.plusMinutes(5);
    private static final Principal COUNSELOR = () -> "demo-counselor-001";
    private static final Principal CUSTOMER = () -> "demo-customer-001";
    private static final Principal OTHER = () -> "demo-customer-002";
    private static final AtomicInteger CASE_NUMBER = new AtomicInteger(1300000 + (int) (System.nanoTime() & 65535));
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private JdbcClient jdbc;
    private PlatformTransactionManager manager;
    private ClosureService closures;
    private ReplacementService replacements;
    private QuoteSearchService searches;
    private OfferSelectionService offers;
    private QuoteSupplierClient quoteSupplier;
    private long booking, caseKey;
    private String caseCode;

    @BeforeAll
    void checkEndpointAndActualIdentityBeforeFlyway() throws SQLException {
        var environment = System.getenv();
        String password = environment.get("REPLACEMENT_TEST_DB_PASSWORD");
        if (!URL.equals(environment.get("REPLACEMENT_TEST_DB_URL")) || !USER.equals(environment.get("REPLACEMENT_TEST_DB_USER"))
                || password == null || password.isBlank())
            throw new IllegalStateException("Exact dedicated replacement test database settings are required before datasource creation");
        var source = new DriverManagerDataSource(URL, USER, password);
        try (var connection = source.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT current_database(), current_user")) {
            if (!result.next() || !DATABASE.equals(result.getString(1)) || !USER.equals(result.getString(2)))
                throw new IllegalStateException("Dedicated replacement test database identity mismatch before Flyway");
        }
        Flyway.configure().dataSource(source).locations("classpath:db/migration", "classpath:db/local")
                .cleanDisabled(true).load().migrate();
        jdbc = JdbcClient.create(source);
        manager = new DataSourceTransactionManager(source);
    }

    @BeforeEach
    void createUniqueCaseWithoutDeletingRows() {
        Clock clock = Clock.systemUTC();
        var supplier = mock(ReplacementSupplierClient.class);
        doAnswer(call -> confirmed(call.getArgument(0))).when(supplier).create(any());
        doAnswer(call -> confirmed(call.getArgument(0))).when(supplier).lookup(any());
        replacements = new ReplacementService(jdbc, manager, supplier, clock);
        quoteSupplier = mock(QuoteSupplierClient.class);
        var ranker = mock(QuoteRankingClient.class);
        doThrow(new IllegalStateException("simulated ranking unavailable")).when(ranker).rank(any());
        searches = new QuoteSearchService(quoteSupplier, ranker, jdbc, manager, clock);
        offers = new OfferSelectionService(jdbc, manager, clock);
        closures = new ClosureService(jdbc, manager, clock);
        String suffix = UUID.randomUUID().toString().substring(0, 12);
        booking = jdbc.sql("""
                INSERT INTO bookings (booking_code,customer_id,hotel_id,supplier_booking_id,check_in_date,
                    check_out_date,adults,rooms,original_total_amount,created_at,updated_at)
                SELECT :code,u.id,h.id,:supplier,DATE '2026-11-10',DATE '2026-11-13',2,1,450000,:at,:at
                FROM users u CROSS JOIN hotels h WHERE u.subject_key='demo-customer-001' AND h.hotel_code='HOTEL-001' RETURNING id
                """).param("code", "YB-X-" + suffix).param("supplier", "SUP-X-" + suffix).param("at", AT.minusHours(1))
                .query(Long.class).single();
        jdbc.sql("INSERT INTO booking_nights (booking_id,stay_date) SELECT :id,DATE '2026-11-10'+n FROM generate_series(0,2)n")
                .param("id", booking).update();
        caseCode = "C-" + CASE_NUMBER.incrementAndGet();
        caseKey = jdbc.sql("INSERT INTO cases (case_code,booking_id,status,priority) VALUES (:code,:booking,'OPEN','URGENT') RETURNING id")
                .param("code", caseCode).param("booking", booking).query(Long.class).single();
        jdbc.sql("INSERT INTO case_tasks (case_id,booking_id,task_type,status,is_required) VALUES (:caseId,:booking,'CONTACT_CUSTOMER','TODO',true)")
                .param("caseId", caseKey).param("booking", booking).update();
    }

    @Test
    void fullStayClosesOnlyAfterReplacementCheckinAndEveryRequiredTask() {
        // Before any replacement: all three blockers, no state change.
        var blocked = blocked("before-booking");
        assertEquals(List.of("REPLACEMENT_NOT_CONFIRMED", "STAY_NOT_COVERED", "REQUIRED_TASKS_OPEN"), codes(blocked));
        assertEquals(List.of("2026-11-10", "2026-11-11", "2026-11-12"), stayDates(blocked));
        complete("CONTACT_CUSTOMER", "고객에게 상황 안내");

        doAnswer(call -> reply(call.getArgument(0), stay -> true)).when(quoteSupplier).search(any());
        String requestId = confirmReplacement("full");

        // Confirmed booking alone: check-in and the two new required tasks still block; the full stay is covered.
        blocked = blocked("after-booking");
        assertEquals(List.of("CHECKIN_NOT_CONFIRMED", "REQUIRED_TASKS_OPEN"), codes(blocked));
        assertEquals(List.of("CHECKIN_CONFIRM", "ORIGINAL_FOLLOWUP"), openTaskTypes(blocked));
        assertCode(409, "CHECKIN_REQUIRED", () -> closures.completeTask(COUNSELOR, caseCode, taskId("CHECKIN_CONFIRM"), "상담사 임의 완료"));

        assertCode(403, "FORBIDDEN_ROLE", () -> closures.checkin(COUNSELOR, requestId, "counselor-checkin"));
        assertCode(404, "REPLACEMENT_BOOKING_NOT_FOUND", () -> closures.checkin(OTHER, requestId, "other-checkin"));
        var checkin = closures.checkin(CUSTOMER, requestId, "checkin-1");
        assertEquals(checkin, closures.checkin(CUSTOMER, requestId, "checkin-1"));
        assertCode(409, "CHECKIN_ALREADY_CONFIRMED", () -> closures.checkin(CUSTOMER, requestId, "checkin-2"));
        assertEquals("DONE", taskStatus("CHECKIN_CONFIRM"));

        // Only the original-booking follow-up remains.
        blocked = blocked("after-checkin");
        assertEquals(List.of("REQUIRED_TASKS_OPEN"), codes(blocked));
        assertEquals(List.of("ORIGINAL_FOLLOWUP"), openTaskTypes(blocked));
        assertEquals("OPEN", caseStatus());
        assertEquals(0L, closedActions());

        complete("ORIGINAL_FOLLOWUP", "원호텔에 대체 숙박 안내·취소 요청 수동 접수");
        assertCode(403, "FORBIDDEN_ROLE", () -> closures.close(CUSTOMER, caseCode, "customer-close"));
        var closed = closures.close(COUNSELOR, caseCode, "close-1");
        assertEquals("CLOSED", caseStatus());
        assertEquals("REPLACEMENT_CHECKED_IN", jdbc.sql("SELECT close_reason FROM cases WHERE id=:id").param("id", caseKey).query(String.class).single());
        assertEquals(closed, closures.close(COUNSELOR, caseCode, "close-1"));
        assertCode(409, "CASE_ALREADY_CLOSED", () -> closures.close(COUNSELOR, caseCode, "close-2"));
        assertCode(409, "CASE_CLOSED", () -> closures.checkin(CUSTOMER, requestId, "checkin-after-close"));
        assertEquals(1L, closedActions());
    }

    @Test
    void oneNightReplacementStaysBlockedUntilTheRemainingNightsAreVerifiedAvailable() {
        verify(LocalDate.of(2026, 11, 10), "UNAVAILABLE");
        // No full-stay quote, so the search falls back to the verified failure night only.
        doAnswer(call -> reply(call.getArgument(0), stay -> !stay.checkOutDate().equals(LocalDate.of(2026, 11, 13))))
                .when(quoteSupplier).search(any());
        complete("CONTACT_CUSTOMER", "고객에게 1박 대체 안내");
        String requestId = confirmReplacement("partial");
        assertEquals(LocalDate.of(2026, 11, 11), jdbc.sql("""
                SELECT q.check_out_date FROM replacement_requests r JOIN alternative_selections s ON s.id=r.selection_id
                JOIN alternative_quotes q ON q.id=s.quote_id WHERE r.request_code=:code
                """).param("code", requestId).query(LocalDate.class).single());
        closures.checkin(CUSTOMER, requestId, "partial-checkin");
        complete("ORIGINAL_FOLLOWUP", "원호텔 1박 미제공 확인, 나머지 2박 확인 요청");

        // Check-in and every task are done, yet the two unconfirmed original nights block closing.
        var blocked = blocked("partial-close");
        assertEquals(List.of("STAY_NOT_COVERED"), codes(blocked));
        assertEquals(List.of("2026-11-11", "2026-11-12"), stayDates(blocked));
        assertEquals("OPEN", caseStatus());
        assertEquals(0L, closedActions());

        verify(LocalDate.of(2026, 11, 11), "AVAILABLE");
        verify(LocalDate.of(2026, 11, 12), "AVAILABLE");
        closures.close(COUNSELOR, caseCode, "partial-close-after-verify");
        assertEquals("CLOSED", caseStatus());
    }

    @Test
    void unconfirmedBookingCannotBeCheckedIn() {
        doAnswer(call -> reply(call.getArgument(0), stay -> true)).when(quoteSupplier).search(any());
        var selection = choose("pending");
        var accepted = replacements.request(COUNSELOR, caseCode, selection, "pending-booking", AT, true);
        assertCode(409, "REPLACEMENT_NOT_CONFIRMED", () -> closures.checkin(CUSTOMER, accepted.replacementBookingId(), "too-early"));
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM checkin_confirmations WHERE case_id=:id").param("id", caseKey).query(Long.class).single());
    }

    private String confirmReplacement(String key) {
        var accepted = replacements.request(COUNSELOR, caseCode, choose(key), "booking-" + key, AT, true);
        assertEquals("CONFIRMED", replacements.execute(accepted.replacementBookingId()).status());
        return accepted.replacementBookingId();
    }

    private String choose(String key) {
        var job = searches.request(COUNSELOR, caseCode, new QuoteSearchService.SearchOptions(3000, 300000L), "search-" + key, AT, true);
        assertEquals("COMPLETED", searches.execute(job.searchJobId()).status());
        List<String> quotes = jdbc.sql("""
                SELECT quote_code FROM alternative_quotes WHERE case_id=:id AND search_job_id=(SELECT max(id) FROM jobs
                    WHERE case_id=:id AND kind='QUOTE_SEARCH' AND status='COMPLETED') ORDER BY id
                """).param("id", caseKey).query(String.class).list();
        var offer = offers.send(COUNSELOR, caseCode, quotes, "offer-" + key, AT, true);
        return offers.select(CUSTOMER, caseCode, offer.offerId(), quotes.getFirst(), "choice-" + key, AT, true).selectionId();
    }

    private List<Map<String, Object>> blocked(String key) {
        var failure = assertThrows(ClosureService.CloseBlocked.class, () -> closures.close(COUNSELOR, caseCode, key));
        assertEquals(409, failure.getStatusCode().value());
        assertEquals("CASE_CLOSE_BLOCKED", failure.getReason());
        assertNotEquals("CLOSED", caseStatus());
        return failure.blockers();
    }

    private static List<String> codes(List<Map<String, Object>> blockers) {
        return blockers.stream().map(item -> (String) item.get("code")).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> stayDates(List<Map<String, Object>> blockers) {
        return blockers.stream().filter(item -> "STAY_NOT_COVERED".equals(item.get("code")))
                .map(item -> (List<String>) item.get("stayDates")).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<String> openTaskTypes(List<Map<String, Object>> blockers) {
        return blockers.stream().filter(item -> "REQUIRED_TASKS_OPEN".equals(item.get("code")))
                .flatMap(item -> ((List<Map<String, Object>>) item.get("tasks")).stream()).map(task -> (String) task.get("type")).toList();
    }

    private void complete(String type, String note) {
        var done = closures.completeTask(COUNSELOR, caseCode, taskId(type), note);
        assertEquals("DONE", done.status());
        assertEquals(done, closures.completeTask(COUNSELOR, caseCode, taskId(type), note));
        assertCode(409, "TASK_ALREADY_COMPLETED", () -> closures.completeTask(COUNSELOR, caseCode, taskId(type), note + " 변경"));
    }

    private String taskId(String type) {
        return "TASK-" + jdbc.sql("SELECT id FROM case_tasks WHERE case_id=:id AND task_type=:type ORDER BY id DESC LIMIT 1")
                .param("id", caseKey).param("type", type).query(Long.class).single();
    }

    private String taskStatus(String type) {
        return jdbc.sql("SELECT status FROM case_tasks WHERE case_id=:id AND task_type=:type ORDER BY id DESC LIMIT 1")
                .param("id", caseKey).param("type", type).query(String.class).single();
    }

    private String caseStatus() { return jdbc.sql("SELECT status FROM cases WHERE id=:id").param("id", caseKey).query(String.class).single(); }

    private long closedActions() {
        return jdbc.sql("SELECT count(*) FROM case_actions WHERE case_id=:id AND action_type='CASE_CLOSED'").param("id", caseKey).query(Long.class).single();
    }

    private void verify(LocalDate date, String status) {
        long night = jdbc.sql("SELECT id FROM booking_nights WHERE booking_id=:id AND stay_date=:date")
                .param("id", booking).param("date", date).query(Long.class).single();
        long evidence = jdbc.sql("INSERT INTO evidence_records (booking_id,source_type,raw_payload,received_at) VALUES (:id,'HOTEL_API','{}'::jsonb,:at) RETURNING id")
                .param("id", booking).param("at", AT).query(Long.class).single();
        long assertion = jdbc.sql("""
                INSERT INTO evidence_nights (booking_id,evidence_id,booking_night_id,reported_status,review_status,reviewed_at)
                VALUES (:booking,:evidence,:night,:status,'ACCEPTED',:at) RETURNING id
                """).param("booking", booking).param("evidence", evidence).param("night", night).param("status", status)
                .param("at", AT).query(Long.class).single();
        jdbc.sql("UPDATE booking_nights SET fulfillment_status=:status,fact_status='VERIFIED',accepted_assertion_id=:assertion WHERE id=:id")
                .param("status", status).param("assertion", assertion).param("id", night).update();
    }

    private static Outcome confirmed(FrozenRequest request) {
        String supplierBookingId = "ALT-" + request.requestId().substring(4, 36);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        return new Outcome("CONFIRMED", supplierBookingId, request.expectedAmount(), now, null, null, null,
                JSON.writeValueAsString(Map.of("requestId", request.requestId(), "status", "CONFIRMED", "quoteId", request.quoteId(),
                        "propertyId", request.propertyId(), "supplierBookingId", supplierBookingId,
                        "confirmedPrice", Map.of("totalAmount", request.expectedAmount(), "currency", request.currency()),
                        "confirmedAt", now.toString())));
    }

    /** Three distinct simulated properties for stays the predicate accepts; an empty completed search otherwise. */
    private static SearchResult reply(SearchInput input, Predicate<Stay> offered) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Quote> quotes = new ArrayList<>();
        int nights = (int) ChronoUnit.DAYS.between(input.stay().checkInDate(), input.stay().checkOutDate());
        for (int i = 0; offered.test(input.stay()) && i < 3; i++) {
            String letter = "ABC".substring(i, i + 1), property = "HOTEL-X-" + letter, quoteId = "QUOTE-" + letter + "-" + input.searchRequestId();
            long base = (150000L + i * 1000L) * nights, fees = 10000L * nights;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("quoteId", quoteId); row.put("source", "SIMULATED_SUPPLIER"); row.put("propertyId", property);
            row.put("propertyName", "가상 호텔 " + letter); row.put("distanceMeters", 500 + i * 300);
            row.put("roomTypeId", "ROOM-" + letter); row.put("roomName", "가상 객실 " + letter); row.put("ratePlanId", "RATE-" + letter);
            row.put("maxAdults", 2); row.put("breakfastIncluded", false); row.put("availableRoomCount", 2);
            row.put("price", Map.of("baseAmount", base, "mandatoryFeesAmount", fees, "totalAmount", base + fees, "currency", "KRW"));
            row.put("cancellationPolicy", "NON_REFUNDABLE"); row.put("checkedAt", CHECKED.toString()); row.put("expiresAt", EXPIRES.toString());
            rows.add(row);
            quotes.add(new Quote(quoteId, "SIMULATED_SUPPLIER", property, "가상 호텔 " + letter, 500 + i * 300, "ROOM-" + letter,
                    "가상 객실 " + letter, "RATE-" + letter, 2, false, 2, base, fees, base + fees, "KRW", "NON_REFUNDABLE",
                    CHECKED, EXPIRES, JSON.writeValueAsString(row)));
        }
        var stay = Map.of("checkInDate", input.stay().checkInDate().toString(), "checkOutDate", input.stay().checkOutDate().toString(),
                "adults", input.stay().adults(), "rooms", input.stay().rooms());
        var body = Map.of("searchRequestId", input.searchRequestId(), "searchStatus", "COMPLETED", "stay", stay,
                "checkedAt", CHECKED.toString(), "currency", "KRW", "quotes", rows);
        var request = Map.of("searchRequestId", input.searchRequestId(), "originPropertyId", input.originPropertyId(), "stay", stay,
                "filters", Map.of("radiusMeters", input.radiusMeters(), "currency", "KRW", "maxAverageNightlyAmount", 300000));
        return new SearchResult(input.searchRequestId(), input.stay(), CHECKED, "KRW", quotes,
                JSON.writeValueAsString(request), JSON.writeValueAsString(body));
    }

    private static void assertCode(int status, String code, Runnable command) {
        var failure = assertThrows(ResponseStatusException.class, command::run);
        assertEquals(status, failure.getStatusCode().value());
        assertEquals(code, failure.getReason());
    }
}
