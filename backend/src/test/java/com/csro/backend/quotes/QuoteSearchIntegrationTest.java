package com.csro.backend.quotes;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.csro.backend.quotes.QuoteContracts.*;
import com.csro.backend.quotes.QuoteSearchService.SearchOptions;

/** Real PostgreSQL writes, isolated before Flyway; only supplier/Agent HTTP boundaries are doubled. */
@EnabledIfEnvironmentVariable(named = "QUOTE_SEARCH_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuoteSearchIntegrationTest {
    private static final OffsetDateTime EFFECTIVE_AT = OffsetDateTime.parse("2026-11-09T10:20:00+09:00");
    private static final OffsetDateTime CHECKED_AT = OffsetDateTime.parse("2026-11-09T10:15:00+09:00");
    private static final Principal COUNSELOR = () -> "demo-counselor-001";
    private static final Principal CUSTOMER = () -> "demo-customer-001";
    private static final SearchOptions OPTIONS = new SearchOptions(3000, 300000L);
    private static final AtomicInteger CASE_NUMBER = new AtomicInteger(900000 + (int) (System.nanoTime() % 100000));

    private JdbcClient jdbc;
    private PlatformTransactionManager manager;
    private MutableClock clock;
    private QuoteSupplierClient supplier;
    private QuoteRankingClient ranker;
    private QuoteSearchService service;
    private Fixture fixture;

    @BeforeAll
    void bootstrapOnlyAfterEndpointAndActualIdentityAreVerified() {
        var settings = QuoteSearchTestDatabase.validate(System.getenv());
        var source = new DriverManagerDataSource(settings.url(), settings.user(), settings.password());
        QuoteSearchTestDatabase.verifyIdentity(source);
        // No application context or Flyway is created before both identity guards pass.
        Flyway.configure().dataSource(source).locations("classpath:db/migration", "classpath:db/local")
                .cleanDisabled(true).load().migrate();
        jdbc = JdbcClient.create(source);
        manager = new DataSourceTransactionManager(source);
    }

    @BeforeEach
    void isolateEachFixtureWithoutDeletingExistingRows() {
        clock = new MutableClock(Instant.parse("2026-11-24T00:00:00Z"));
        supplier = mock(QuoteSupplierClient.class);
        ranker = mock(QuoteRankingClient.class);
        service = new QuoteSearchService(supplier, ranker, jdbc, manager, clock);
        fixture = createFixture();
        doAnswer(call -> {
            assertNoTransactionOrBusinessRowLock();
            return searchReply(call.getArgument(0), false, false);
        }).when(supplier).search(any());
        doAnswer(call -> {
            assertNoTransactionOrBusinessRowLock();
            return rankingReply(call.getArgument(0));
        }).when(ranker).rank(any());
    }

    @Test
    void concurrentIdenticalKeysCreateOneJobAndDifferentBodyConflicts() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return request("same-key"); });
            var second = executor.submit(() -> { start.await(); return request("same-key"); });
            start.countDown();
            assertEquals(first.get(8, TimeUnit.SECONDS).searchJobId(), second.get(8, TimeUnit.SECONDS).searchJobId());
        }
        assertEquals(1L, count("jobs"));
        var changed = assertThrows(ResponseStatusException.class, () -> service.request(COUNSELOR, fixture.caseCode(),
                new SearchOptions(2000, 300000L), "same-key", EFFECTIVE_AT, true));
        assertEquals(409, changed.getStatusCode().value());
        assertEquals("IDEMPOTENCY_KEY_REUSED", changed.getReason());
        var busy = assertThrows(ResponseStatusException.class, () -> request("different-key"));
        assertEquals(409, busy.getStatusCode().value());
        verifyNoInteractions(supplier, ranker);
    }

    @Test
    void fullStayQuotesCommitBeforeRankingAndReadsNeverCreateBusinessActions() {
        var job = request("full-stay");
        doAnswer(call -> {
            assertNoTransactionOrBusinessRowLock();
            assertEquals(3L, count("alternative_quotes"), "The Agent must receive already-saved snapshots");
            return rankingReply(call.getArgument(0));
        }).when(ranker).rank(any());
        assertEquals("COMPLETED", service.execute(job.searchJobId()).status());
        Map<?, ?> before = view();
        assertEquals("SYNTHETIC_DEMO", before.get("clockMode"));
        var groups = list(before.get("groups"));
        assertEquals(1, groups.size());
        Map<?, ?> group = map(groups.getFirst());
        assertEquals("FULL_STAY", group.get("coverageMode"));
        assertEquals(Boolean.TRUE, group.get("rankingAvailable"));
        var quotes = list(group.get("quotes"));
        assertEquals(3, quotes.size());
        assertEquals(List.of(480000L, 465000L, 510000L), quotes.stream()
                .map(QuoteSearchIntegrationTest::map).map(q -> ((Number) q.get("totalAmount")).longValue()).toList());
        assertEquals(30000L, ((Number) map(quotes.getFirst()).get("priceDifference")).longValue());
        assertEquals(3L, count("alternative_quotes"));
        assertEquals(1L, count("quote_rankings"));
        assertEquals(before, view());
        service.execute(job.searchJobId());
        verify(supplier, times(1)).search(any());
        verify(ranker, times(1)).rank(any());
        assertEquals(0L, count("offers"));
        assertEquals(0L, count("alternative_selections"));
        assertEquals(0L, count("replacement_requests"));
        assertEquals(0L, count("case_actions"));
        assertEquals(0L, count("case_tasks"));
        assertEquals(3L, jdbc.sql("SELECT count(*) FROM booking_nights WHERE booking_id=:booking AND fulfillment_status='UNKNOWN' AND fact_status='UNCONFIRMED'")
                .param("booking", fixture.bookingId()).query(Long.class).single());
    }

    @Test
    void searchScopedQuoteIdsCanNeverCrossLinkDifferentCases() {
        var first = request("case-one");
        assertEquals("COMPLETED", service.execute(first.searchJobId()).status());
        var other = createFixture();
        var second = service.request(COUNSELOR, other.caseCode(), OPTIONS, "case-two", EFFECTIVE_AT, true);
        assertEquals("COMPLETED", service.execute(second.searchJobId()).status());
        assertEquals(6L, jdbc.sql("SELECT count(*) FROM alternative_quotes WHERE case_id IN (:first,:second)")
                .param("first", fixture.caseId()).param("second", other.caseId()).query(Long.class).single());
        assertEquals(6L, jdbc.sql("SELECT count(DISTINCT quote_code) FROM alternative_quotes WHERE case_id IN (:first,:second)")
                .param("first", fixture.caseId()).param("second", other.caseId()).query(Long.class).single());
        assertEquals(0L, jdbc.sql("""
                SELECT count(*) FROM alternative_quotes q JOIN jobs j ON j.id=q.search_job_id
                WHERE q.case_id IN (:first,:second) AND q.case_id<>j.case_id
                """).param("first", fixture.caseId()).param("second", other.caseId()).query(Long.class).single());

        List<String> alreadySaved = jdbc.sql("SELECT quote_code FROM alternative_quotes WHERE case_id=:id ORDER BY id")
                .param("id", fixture.caseId()).query(String.class).list();
        doAnswer(call -> collisionReply(call.getArgument(0), alreadySaved)).when(supplier).search(any());
        var third = createFixture();
        var conflicting = service.request(COUNSELOR, third.caseCode(), OPTIONS, "provider-id-collision", EFFECTIVE_AT, true);
        var failed = service.execute(conflicting.searchJobId());
        assertEquals("FAILED", failed.status());
        assertEquals("QUOTE_ID_CONFLICT", failed.errorCode());
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM alternative_quotes WHERE case_id=:id")
                .param("id", third.caseId()).query(Long.class).single());
        assertEquals(3L, count("alternative_quotes"), "A conflicting provider ID cannot reassign the first case's snapshots");
    }

    @Test
    void onlyAValidEmptyFullStayTriggersVerifiedFailureDateFallback() {
        verifyUnavailable(fixture, LocalDate.of(2026, 11, 10));
        var seen = new ArrayList<SearchInput>();
        doAnswer(call -> {
            SearchInput input = call.getArgument(0);
            seen.add(input);
            assertNoTransactionOrBusinessRowLock();
            return searchReply(input, input.stay().checkOutDate().equals(LocalDate.of(2026, 11, 13)), false);
        }).when(supplier).search(any());
        doAnswer(call -> {
            RankInput input = call.getArgument(0);
            assertNull(input.originalStayTotal(), "A one-night quote must not use the original three-night total");
            return rankingReply(input);
        }).when(ranker).rank(any());
        var job = request("fallback");
        assertEquals("COMPLETED", service.execute(job.searchJobId()).status());
        assertEquals(2, seen.size());
        assertEquals(new Stay(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 13), 2, 1), seen.getFirst().stay());
        assertEquals(new Stay(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 11), 2, 1), seen.get(1).stay());
        var groups = list(view().get("groups"));
        var partial = groups.stream().map(QuoteSearchIntegrationTest::map)
                .filter(g -> "VERIFIED_FAILURE_ONLY".equals(g.get("coverageMode"))).findFirst().orElseThrow();
        assertTrue(list(partial.get("quotes")).stream().map(QuoteSearchIntegrationTest::map)
                .allMatch(q -> q.get("priceDifference") == null));
        assertEquals(List.of("2026-11-11", "2026-11-12"), partial.get("remainingUnconfirmedStayDates"));
        assertEquals("OPEN", jdbc.sql("SELECT status FROM cases WHERE id=:id").param("id", fixture.caseId()).query(String.class).single());
    }

    @Test
    void agentFailurePreservesValidatedQuotesForManualReview() {
        doThrow(new QuoteIntegrationException(QuoteIntegrationException.Step.API_025,
                QuoteIntegrationException.Kind.UNAVAILABLE, 503, "AGENT_UNAVAILABLE", null)).when(ranker).rank(any());
        var job = request("agent-failure");
        assertEquals("COMPLETED", service.execute(job.searchJobId()).status());
        var group = map(list(view().get("groups")).getFirst());
        assertEquals(Boolean.FALSE, group.get("rankingAvailable"));
        assertEquals(3, list(group.get("quotes")).size());
        assertEquals(3L, count("alternative_quotes"));
        assertEquals(0L, count("offers"));
    }

    @Test
    void supplierFailureDoesNotFallbackOrManufactureFacts() {
        doThrow(new QuoteIntegrationException(QuoteIntegrationException.Step.API_028,
                QuoteIntegrationException.Kind.UNAVAILABLE, 503, "SUPPLIER_UNAVAILABLE", null)).when(supplier).search(any());
        var job = request("supplier-failure");
        var result = service.execute(job.searchJobId());
        assertEquals("FAILED", result.status());
        assertNotNull(result.errorCode());
        verify(supplier, times(1)).search(any());
        verifyNoInteractions(ranker);
        assertEquals(0L, count("alternative_quotes"));
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM evidence_records WHERE booking_id=:id")
                .param("id", fixture.bookingId()).query(Long.class).single());
        assertEquals(0L, count("case_actions"));
    }

    @Test
    void interruptedRankingResumesSavedSnapshotsAfterTheRealLeaseExpires() {
        doThrow(new AssertionError("simulated process stop after quote commit")).when(ranker).rank(any());
        var job = request("resume-ranking");
        assertThrows(AssertionError.class, () -> service.execute(job.searchJobId()));
        assertEquals(3L, count("alternative_quotes"));
        assertEquals("QUOTES_SAVED", jdbc.sql("SELECT result_payload->>'stage' FROM jobs WHERE job_code=:code")
                .param("code", job.searchJobId()).query(String.class).single());
        clock.advance(46);
        doAnswer(call -> rankingReply(call.getArgument(0))).when(ranker).rank(any());
        assertEquals("COMPLETED", service.execute(job.searchJobId()).status());
        verify(supplier, times(1)).search(any());
        assertEquals(3L, count("alternative_quotes"));
        assertEquals(1L, count("quote_rankings"));
    }

    @Test
    void anExpiredClaimCannotOverwriteTheNewWorkersCompletedQuotes() throws Exception {
        var firstInHttp = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var calls = new AtomicInteger();
        doAnswer(call -> {
            assertNoTransactionOrBusinessRowLock();
            if (calls.incrementAndGet() == 1) {
                firstInHttp.countDown();
                assertTrue(releaseFirst.await(8, TimeUnit.SECONDS));
            }
            return searchReply(call.getArgument(0), false, false);
        }).when(supplier).search(any());
        var job = request("late-claim");
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> service.execute(job.searchJobId()));
            try {
                assertTrue(firstInHttp.await(5, TimeUnit.SECONDS));
                clock.advance(46);
                assertEquals("COMPLETED", service.execute(job.searchJobId()).status());
            } finally {
                releaseFirst.countDown();
            }
            first.get(8, TimeUnit.SECONDS);
        }
        assertEquals(3L, count("alternative_quotes"));
        assertEquals(1L, count("quote_rankings"));
        verify(ranker, times(1)).rank(any());
    }

    @Test
    void explicitQuoteClockRejectsExpiredQuotesAndRoleChecksPrecedeAllWrites() {
        assertEquals(401, assertThrows(ResponseStatusException.class,
                () -> service.request(null, fixture.caseCode(), OPTIONS, "no-auth", EFFECTIVE_AT, true)).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> service.request(CUSTOMER, fixture.caseCode(), OPTIONS, "customer", EFFECTIVE_AT, true)).getStatusCode().value());
        assertEquals(0L, count("jobs"));
        var expired = service.request(COUNSELOR, fixture.caseCode(), OPTIONS, "expired", EFFECTIVE_AT.plusMinutes(10), true);
        assertEquals("FAILED", service.execute(expired.searchJobId()).status());
        assertEquals(0L, count("alternative_quotes"));
        verifyNoInteractions(ranker);
        jdbc.sql("UPDATE cases SET status='CLOSED',closed_at=now(),close_reason='TEST_COMPLETED' WHERE id=:id")
                .param("id", fixture.caseId()).update();
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> request("closed")).getStatusCode().value());
    }

    @Test
    void realTimeExecutionAndExpiryUseTheCurrentClockInsteadOfTheEnqueueSnapshot() {
        clock.set(Instant.parse("2026-11-09T01:14:00Z"));
        var queuedAt = OffsetDateTime.now(clock);
        // The five-argument entry point is ordinary real-time mode, not the explicitly frozen demo.
        var job = service.request(COUNSELOR, fixture.caseCode(), OPTIONS, "real-time", queuedAt);
        clock.advance(120); // Supplier checkedAt 10:15 is now in the past, though it was future at enqueue.
        assertEquals("COMPLETED", service.execute(job.searchJobId()).status());
        Map<?, ?> fresh = view();
        assertEquals("REAL_TIME", fresh.get("clockMode"));
        assertEquals(clock.instant(), OffsetDateTime.parse((String) fresh.get("effectiveAt")).toInstant());
        assertTrue(list(map(list(fresh.get("groups")).getFirst()).get("quotes")).stream()
                .map(QuoteSearchIntegrationTest::map).allMatch(quote -> Boolean.FALSE.equals(quote.get("expired"))));
        clock.advance(600); // 10:26, after the actual 10:25 expiry.
        Map<?, ?> expired = view();
        assertEquals(clock.instant(), OffsetDateTime.parse((String) expired.get("effectiveAt")).toInstant());
        assertTrue(list(map(list(expired.get("groups")).getFirst()).get("quotes")).stream()
                .map(QuoteSearchIntegrationTest::map).allMatch(quote -> Boolean.TRUE.equals(quote.get("expired"))));
    }

    private QuoteSearchService.Acceptance request(String key) {
        return service.request(COUNSELOR, fixture.caseCode(), OPTIONS, key, EFFECTIVE_AT, true);
    }

    private Map<?, ?> view() { return map(QuoteContracts.JSON.readValue(service.view(COUNSELOR, fixture.caseCode()), Object.class)); }

    private long count(String table) {
        // Table identifiers are fixed internal test literals; values remain bound parameters.
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE case_id=:id")
                .param("id", fixture.caseId()).query(Long.class).single();
    }

    private void assertNoTransactionOrBusinessRowLock() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "External HTTP must execute outside the write transaction");
        var transaction = new TransactionTemplate(manager);
        transaction.executeWithoutResult(status -> {
            jdbc.sql("SELECT id FROM bookings WHERE id=:id FOR UPDATE NOWAIT").param("id", fixture.bookingId()).query(Long.class).single();
            jdbc.sql("SELECT id FROM cases WHERE id=:id FOR UPDATE NOWAIT").param("id", fixture.caseId()).query(Long.class).single();
        });
    }

    private Fixture createFixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long booking = jdbc.sql("""
                INSERT INTO bookings (booking_code,customer_id,hotel_id,supplier_booking_id,check_in_date,
                    check_out_date,adults,rooms,original_total_amount,created_at,updated_at)
                SELECT :code,u.id,h.id,:supplier,DATE '2026-11-10',DATE '2026-11-13',2,1,450000,:at,:at
                FROM users u CROSS JOIN hotels h WHERE u.subject_key='demo-customer-001' AND h.hotel_code='HOTEL-001'
                RETURNING id
                """).param("code", "YB-Q-" + suffix).param("supplier", "SUP-Q-" + suffix)
                .param("at", EFFECTIVE_AT.minusHours(1)).query(Long.class).single();
        jdbc.sql("INSERT INTO booking_nights (booking_id,stay_date) SELECT :id,DATE '2026-11-10'+n FROM generate_series(0,2) n")
                .param("id", booking).update();
        String caseCode = "C-" + CASE_NUMBER.incrementAndGet();
        long caseId = jdbc.sql("INSERT INTO cases (case_code,booking_id,status,priority) VALUES (:code,:booking,'OPEN','URGENT') RETURNING id")
                .param("code", caseCode).param("booking", booking).query(Long.class).single();
        return new Fixture(booking, caseId, caseCode);
    }

    private void verifyUnavailable(Fixture target, LocalDate stayDate) {
        long night = jdbc.sql("SELECT id FROM booking_nights WHERE booking_id=:booking AND stay_date=:date")
                .param("booking", target.bookingId()).param("date", stayDate).query(Long.class).single();
        long evidence = jdbc.sql("""
                INSERT INTO evidence_records (booking_id,source_type,raw_payload,received_at)
                VALUES (:booking,'HOTEL_API','{}'::jsonb,:at) RETURNING id
                """).param("booking", target.bookingId()).param("at", EFFECTIVE_AT).query(Long.class).single();
        long assertion = jdbc.sql("""
                INSERT INTO evidence_nights (booking_id,evidence_id,booking_night_id,reported_status,review_status,reviewed_at)
                VALUES (:booking,:evidence,:night,'UNAVAILABLE','ACCEPTED',:at) RETURNING id
                """).param("booking", target.bookingId()).param("evidence", evidence).param("night", night)
                .param("at", EFFECTIVE_AT).query(Long.class).single();
        jdbc.sql("UPDATE booking_nights SET fulfillment_status='UNAVAILABLE',fact_status='VERIFIED',accepted_assertion_id=:assertion WHERE id=:night")
                .param("assertion", assertion).param("night", night).update();
    }

    private static SearchResult searchReply(SearchInput input, boolean empty, boolean fixedIds) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int nights = (int) java.time.temporal.ChronoUnit.DAYS.between(input.stay().checkInDate(), input.stay().checkOutDate());
        for (int i = 0; !empty && i < 3; i++) {
            String letter = "ABC".substring(i, i + 1);
            long base = new long[]{150000,155000,145000}[i] * nights * input.stay().rooms();
            long fees = new long[]{10000,15000,10000}[i] * nights * input.stay().rooms();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("quoteId", "QUOTE-" + letter + (fixedIds ? "" : "-" + input.searchRequestId()));
            row.put("source", "SIMULATED_SUPPLIER"); row.put("propertyId", "HOTEL-" + letter);
            row.put("propertyName", letter + "호텔"); row.put("distanceMeters", new int[]{800,1400,2600}[i]);
            row.put("roomTypeId", "ROOM-" + letter); row.put("roomName", "가상 객실 " + letter);
            row.put("ratePlanId", "RATE-" + letter); row.put("maxAdults", 2); row.put("breakfastIncluded", i == 0);
            row.put("availableRoomCount", 2); row.put("price", Map.of("baseAmount",base,"mandatoryFeesAmount",fees,"totalAmount",base+fees,"currency","KRW"));
            row.put("cancellationPolicy", "NON_REFUNDABLE"); row.put("checkedAt", "2026-11-09T10:15:00+09:00");
            row.put("expiresAt", "2026-11-09T10:25:00+09:00"); rows.add(row);
        }
        Map<String, Object> body = Map.of("searchRequestId",input.searchRequestId(),"searchStatus","COMPLETED",
                "stay",Map.of("checkInDate",input.stay().checkInDate().toString(),"checkOutDate",input.stay().checkOutDate().toString(),
                        "adults",input.stay().adults(),"rooms",input.stay().rooms()),"checkedAt","2026-11-09T10:15:00+09:00","currency","KRW","quotes",rows);
        String request = QuoteContracts.JSON.writeValueAsString(QuoteContracts.searchWire(input));
        return QuoteContracts.readSearch(QuoteContracts.JSON.writeValueAsBytes(body), input, request);
    }

    private static Ranking rankingReply(RankInput input) {
        Quote nearest = input.quotes().stream().min(Comparator.comparingInt(Quote::distanceMeters)).orElseThrow();
        var remaining = input.quotes().stream().filter(q -> !q.quoteId().equals(nearest.quoteId()))
                .sorted(Comparator.comparingLong(Quote::totalAmount)).toList();
        var ordered = new ArrayList<Quote>(); ordered.add(nearest); ordered.addAll(remaining);
        long lowest = input.quotes().stream().mapToLong(Quote::totalAmount).min().orElseThrow();
        var reasons = ordered.stream().map(q -> Map.of("quoteId",q.quoteId(),"reasonCodes",List.of(
                q == nearest ? "NEAREST" : q.totalAmount() == lowest ? "LOWEST_PRICE" : "ALTERNATIVE"),
                "summary","가상 견적 원본의 거리와 금액 비교 결과입니다.")).toList();
        var body = Map.of("caseId",input.caseId(),"rankingVersion","alternative-ranker-0.1",
                "rankedQuoteIds",ordered.stream().map(Quote::quoteId).toList(),"reasons",reasons,
                "warnings",List.of("예약 확정이 아닙니다."));
        return QuoteContracts.readRanking(QuoteContracts.JSON.writeValueAsBytes(body), input,
                QuoteContracts.JSON.writeValueAsString(QuoteContracts.rankingWire(input)));
    }

    @SuppressWarnings("unchecked")
    private static SearchResult collisionReply(SearchInput input, List<String> existingIds) {
        SearchResult fresh = searchReply(input, false, false);
        var payload = (Map<String, Object>) QuoteContracts.JSON.readValue(fresh.responseJson(), Object.class);
        var rows = (List<Map<String, Object>>) payload.get("quotes");
        for (int index = 0; index < rows.size(); index++) rows.get(index).put("quoteId", existingIds.get(index));
        return QuoteContracts.readSearch(QuoteContracts.JSON.writeValueAsBytes(payload), input, fresh.requestJson());
    }

    private static Map<?, ?> map(Object value) { assertInstanceOf(Map.class, value); return (Map<?, ?>) value; }
    private static List<?> list(Object value) { assertInstanceOf(List.class, value); return (List<?>) value; }
    private record Fixture(long bookingId, long caseId, String caseCode) {}

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> value;
        MutableClock(Instant value) { this.value = new AtomicReference<>(value); }
        void set(Instant instant) { value.set(instant); }
        void advance(long seconds) { value.updateAndGet(at -> at.plusSeconds(seconds)); }
        @Override public Instant instant() { return value.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
