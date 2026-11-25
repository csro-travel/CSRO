package com.csro.backend.replacements;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.csro.backend.offers.OfferSelectionService;
import com.csro.backend.quotes.QuoteContracts.*;
import com.csro.backend.quotes.QuoteRankingClient;
import com.csro.backend.quotes.QuoteSearchService;
import com.csro.backend.quotes.QuoteSupplierClient;
import com.csro.backend.replacements.ReplacementSupplierClient.FrozenRequest;
import com.csro.backend.replacements.ReplacementSupplierClient.Outcome;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real V1 writes. Only supplier/Agent HTTP boundaries are doubled; no shared DB reset is used. */
@EnabledIfEnvironmentVariable(named = "REPLACEMENT_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReplacementIntegrationTest {
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-09T10:20:00+09:00");
    private static final OffsetDateTime CHECKED = AT.minusMinutes(5);
    private static final OffsetDateTime EXPIRES = AT.plusMinutes(5);
    private static final Principal COUNSELOR = () -> "demo-counselor-001";
    private static final Principal CUSTOMER = () -> "demo-customer-001";
    private static final Principal OTHER = () -> "demo-customer-002";
    private static final AtomicInteger CASE_NUMBER = new AtomicInteger(1100000 + (int) (System.nanoTime() & 65535));
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private JdbcClient jdbc;
    private PlatformTransactionManager manager;
    private MutableClock clock;
    private ReplacementService service;
    private ReplacementSupplierClient supplier;
    private QuoteSupplierClient quoteSupplier;
    private QuoteSearchService searches;
    private OfferSelectionService offers;
    private Fixture fixture;
    private OfferSelectionService.SendResult offer;
    private OfferSelectionService.SelectionResult selection;

    @BeforeAll
    void checkEndpointAndActualIdentityBeforeAnyFlywayOrSpringStartup() {
        var settings = ReplacementTestDatabase.validate(System.getenv());
        var source = new DriverManagerDataSource(settings.url(), settings.user(), settings.password());
        ReplacementTestDatabase.verifyIdentity(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration", "classpath:db/local")
                .cleanDisabled(true).load().migrate();
        jdbc = JdbcClient.create(source);
        manager = new DataSourceTransactionManager(source);
    }

    @BeforeEach
    void createUniqueQuoteOfferAndCustomerChoiceWithoutDeletingRows() {
        clock = new MutableClock(Instant.parse("2026-11-24T12:00:00.123456789Z"));
        supplier = mock(ReplacementSupplierClient.class);
        service = new ReplacementService(jdbc, manager, supplier, clock);
        quoteSupplier = mock(QuoteSupplierClient.class);
        var ranker = mock(QuoteRankingClient.class);
        searches = new QuoteSearchService(quoteSupplier, ranker, jdbc, manager, clock);
        offers = new OfferSelectionService(jdbc, manager, clock);
        doAnswer(call -> quoteReply(call.getArgument(0))).when(quoteSupplier).search(any());
        doThrow(new IllegalStateException("simulated ranking unavailable")).when(ranker).rank(any());
        fixture = createFixture();
        prepareChoice("initial");
        doAnswer(call -> { assertNoTransactionDuringHttp(); return confirmed(call.getArgument(0)); }).when(supplier).create(any());
        doAnswer(call -> { assertNoTransactionDuringHttp(); return confirmed(call.getArgument(0)); }).when(supplier).lookup(any());
    }

    @Test
    void roleOwnershipAndUnknownRequestDoNotLeakOrCreateAReservation() {
        assertCode(401, "UNAUTHENTICATED", () -> service.request(null, fixture.code(), selection.selectionId(), "missing", AT, true));
        assertCode(403, "FORBIDDEN_ROLE", () -> service.request(CUSTOMER, fixture.code(), selection.selectionId(), "customer", AT, true));
        assertCode(404, "CASE_NOT_FOUND", () -> service.request(COUNSELOR, "C-9999999999999", selection.selectionId(), "missing-case", AT, true));
        assertEquals(0L, count("replacement_requests"));
        var accepted = request("owner-read");
        assertCode(401, "UNAUTHENTICATED", () -> service.view(null, accepted.replacementBookingId()));
        assertCode(404, "REPLACEMENT_BOOKING_NOT_FOUND", () -> service.view(OTHER, accepted.replacementBookingId()));
        assertCode(404, "REPLACEMENT_BOOKING_NOT_FOUND", () -> service.view(CUSTOMER, "BRQ-" + "f".repeat(64)));
        assertEquals(accepted.replacementBookingId(), view(CUSTOMER, accepted.replacementBookingId()).path("replacementBookingId").asString());
        verifyNoInteractions(supplier);
    }

    @Test
    void staleOrExpiredChoiceCannotCreateARequest() {
        assertCode(409, "QUOTE_EXPIRED", () -> service.request(COUNSELOR, fixture.code(), selection.selectionId(), "expired", EXPIRES, true));
        var oldSelection = selection;
        selection = offers.select(CUSTOMER, fixture.code(), offer.offerId(), codes().get(1), "changed-choice", AT, true);
        assertCode(409, "CURRENT_SELECTION_CHANGED", () -> service.request(COUNSELOR, fixture.code(), oldSelection.selectionId(), "stale", AT, true));
        assertEquals(0L, count("replacement_requests"));
        verifyNoInteractions(supplier);
    }

    @Test
    void replayIsImmutableAfterClosureAndDifferentKeysCannotBookTheSameSelection() {
        var accepted = request("stable-key");
        assertTrue(accepted.replacementBookingId().matches("BRQ-[0-9a-f]{64}"));
        assertCode(409, "SELECTION_ALREADY_REQUESTED", () -> request("different-key"));
        jdbc.sql("UPDATE cases SET status='CLOSED',closed_at=:now,close_reason='TEST_COMPLETED' WHERE id=:id")
                .param("now", now()).param("id", fixture.caseId()).update();
        clock.advance(400);
        assertEquals(accepted, service.request(COUNSELOR, fixture.code(), selection.selectionId(), "stable-key", EXPIRES.plusDays(1), true));
        assertCode(409, "IDEMPOTENCY_KEY_REUSED", () -> service.request(COUNSELOR, fixture.code(), "SEL-" + UUID.randomUUID(), "stable-key", AT, true));
        assertEquals(1L, count("replacement_requests"));
        verifyNoInteractions(supplier);
    }

    @Test
    void simultaneousReceptionCreatesOneDurableRequestAndLocksChoiceReplacement() throws Exception {
        var start = new CountDownLatch(1);
        ReplacementService.Acceptance accepted;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return request("same-concurrent-key"); });
            var second = pool.submit(() -> { start.await(); return request("same-concurrent-key"); });
            start.countDown();
            accepted = first.get(10, TimeUnit.SECONDS);
            assertEquals(accepted, second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1L, count("replacement_requests"));
        assertCode(409, "REPLACEMENT_LOCKED", () -> offers.select(CUSTOMER, fixture.code(), offer.offerId(), codes().get(1), "change-after-intent", AT, true));
        assertEquals("PROCESSING", view(COUNSELOR, accepted.replacementBookingId()).path("status").asString());
        verifyNoInteractions(supplier);
    }

    @Test
    void confirmationCommitsRequiredTasksAndOneNotificationWithoutClosingOrChangingOriginalNights() {
        var accepted = request("confirmed");
        jdbc.sql("INSERT INTO case_tasks (case_id,booking_id,task_type,status,is_required) VALUES (:caseId,:booking,'CHECKIN_CONFIRM','TODO',true)")
                .param("caseId",fixture.caseId()).param("booking",fixture.bookingId()).update();
        var frozen = JSON.readTree(jdbc.sql("SELECT request_payload::text FROM replacement_requests WHERE request_code=:code")
                .param("code", accepted.replacementBookingId()).query(String.class).single());
        assertEquals(selection.quoteId(), frozen.path("quoteId").asString());
        assertEquals(480000L, frozen.path("expectedTotalAmount").longValue());
        assertEquals("KRW", frozen.path("currency").asString());
        assertEquals(4, frozen.size(), "Persist the existing API-029 body, with request ID carried by its header");
        long messagesBefore = count("chat_messages");
        assertEquals("CONFIRMED", service.execute(accepted.replacementBookingId()).status());
        var result = view(COUNSELOR, accepted.replacementBookingId());
        assertEquals("CONFIRMED", result.path("status").asString());
        assertTrue(result.path("supplierBookingId").asString().startsWith("ALT-"));
        assertEquals(480000L, result.path("confirmedPrice").path("totalAmount").longValue());
        assertEquals(2L, jdbc.sql("SELECT count(*) FROM case_tasks WHERE case_id=:id AND task_type IN ('CHECKIN_CONFIRM','ORIGINAL_FOLLOWUP') AND status='TODO' AND is_required")
                .param("id", fixture.caseId()).query(Long.class).single());
        assertEquals("TODO", jdbc.sql("SELECT status FROM case_tasks WHERE case_id=:id AND task_type='CONTACT_CUSTOMER'")
                .param("id", fixture.caseId()).query(String.class).single());
        assertEquals(messagesBefore + 1, count("chat_messages"));
        assertEquals(0L, count("checkin_confirmations"));
        assertEquals("OPEN", jdbc.sql("SELECT status FROM cases WHERE id=:id").param("id", fixture.caseId()).query(String.class).single());
        assertEquals(3L, jdbc.sql("SELECT count(*) FROM booking_nights WHERE booking_id=:id AND fulfillment_status='UNKNOWN' AND fact_status='UNCONFIRMED'")
                .param("id", fixture.bookingId()).query(Long.class).single());
        String before = snapshot();
        service.execute(accepted.replacementBookingId());
        assertEquals(before, snapshot());
        verify(supplier, times(1)).create(any());
        verify(supplier, never()).lookup(any());
    }

    @Test
    void lostPostResponseIsRecoveredByLookingUpTheSameFrozenRequest() {
        doThrow(uncertain()).when(supplier).create(any());
        var accepted = request("lost-response");
        assertEquals("RESULT_UNKNOWN", service.execute(accepted.replacementBookingId()).status());
        clock.advance(3);
        assertEquals("CONFIRMED", service.execute(accepted.replacementBookingId()).status());
        var posted = ArgumentCaptor.forClass(FrozenRequest.class);
        var queried = ArgumentCaptor.forClass(FrozenRequest.class);
        verify(supplier, times(1)).create(posted.capture());
        verify(supplier, times(1)).lookup(queried.capture());
        assertEquals(posted.getValue(), queried.getValue());
        assertEquals(accepted.replacementBookingId(), posted.getValue().requestId());
        assertEquals(1L, count("replacement_requests"));
    }

    @Test
    void verifiedNotFoundMayRetryOnlyTheOriginalPostBodyAndRequestId() {
        doAnswer(call -> { throw uncertain(); }).doAnswer(call -> confirmed(call.getArgument(0))).when(supplier).create(any());
        doThrow(new ReplacementSupplierException(ReplacementSupplierException.Kind.REQUEST_NOT_FOUND, 404, "BOOKING_REQUEST_NOT_FOUND"))
                .when(supplier).lookup(any());
        var accepted = request("not-dispatched");
        var result = service.execute(accepted.replacementBookingId());
        for (int attempt=0; attempt<4 && !"CONFIRMED".equals(result.status()); attempt++) {
            clock.advance(31);
            result = service.execute(accepted.replacementBookingId());
        }
        assertEquals("CONFIRMED", result.status());
        var posted = ArgumentCaptor.forClass(FrozenRequest.class);
        verify(supplier, times(2)).create(posted.capture());
        assertEquals(posted.getAllValues().getFirst(), posted.getAllValues().getLast());
        verify(supplier, times(1)).lookup(posted.getValue());
        assertEquals(1L, count("replacement_requests"));
    }

    @Test
    void uncertainLookupExhaustionRemainsUnknownAndCannotUnlockANewBooking() {
        doThrow(uncertain()).when(supplier).create(any());
        doThrow(new ReplacementSupplierException(ReplacementSupplierException.Kind.CONNECTION_FAILURE, 503, "SUPPLIER_UNAVAILABLE"))
                .when(supplier).lookup(any());
        var accepted = request("unknown-exhaustion");
        var execution = exhaust(accepted.replacementBookingId());
        assertEquals("RESULT_UNKNOWN", execution.status());
        assertTrue(execution.manualRequired());
        assertTrue(execution.getAttempts() <= 8);
        assertEquals(1, execution.postAttempts());
        assertTrue(view(CUSTOMER, accepted.replacementBookingId()).path("manualRequired").booleanValue());
        assertEquals(accepted, request("unknown-exhaustion"));
        assertCode(409, "REPLACEMENT_LOCKED", () -> offers.send(COUNSELOR, fixture.code(), codes(), "must-not-unlock", AT, true));
        verify(supplier, times(1)).create(any());
    }

    @Test
    void malformedSuccessfulSupplierResponseRequiresManualRecoveryWithoutClaimingFailureOrConfirmation() {
        doThrow(new ReplacementSupplierException(ReplacementSupplierException.Kind.UNKNOWN, 201, null))
                .when(supplier).create(any());
        var accepted = request("invalid-success");
        var result = service.execute(accepted.replacementBookingId());
        assertEquals("RESULT_UNKNOWN", result.status());
        assertTrue(result.manualRequired());
        assertNull(result.nextCheckAt());
        var saved = view(CUSTOMER, accepted.replacementBookingId());
        assertTrue(saved.path("supplierBookingId").isNull());
        assertTrue(saved.path("confirmedPrice").isNull());
        assertTrue(saved.path("failureCode").isNull());
        assertCode(409, "REPLACEMENT_LOCKED", () -> offers.select(CUSTOMER, fixture.code(), offer.offerId(), codes().get(1), "invalid-result-new-choice", AT, true));
        verify(supplier, times(1)).create(any());
        verify(supplier, never()).lookup(any());
    }

    @Test
    void publicationConstraintFailureRollsBackAllEffectsAndRetainsTheVerifiedProviderObservation() {
        var first = request("original-confirmed-id");
        assertEquals("CONFIRMED", service.execute(first.replacementBookingId()).status());
        String supplierId = view(COUNSELOR, first.replacementBookingId()).path("supplierBookingId").asString();
        fixture = createFixture();
        prepareChoice("collision-case");
        var observed = new AtomicReference<String>();
        doAnswer(call -> {
            assertNoTransactionDuringHttp();
            var outcome = confirmed(call.getArgument(0), supplierId);
            observed.set(outcome.rawJson());
            return outcome;
        }).when(supplier).create(any());
        var second = request("duplicate-supplier-id");
        long messagesBefore = count("chat_messages");
        var result = service.execute(second.replacementBookingId());
        assertEquals("RESULT_UNKNOWN", result.status());
        assertTrue(result.manualRequired());
        var saved = view(COUNSELOR, second.replacementBookingId());
        assertTrue(saved.path("supplierBookingId").isNull());
        assertTrue(saved.path("confirmedPrice").isNull());
        assertTrue(saved.path("failureCode").isNull());
        assertEquals(messagesBefore, count("chat_messages"), "The failed publication must not leave its success notification");
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM case_tasks WHERE case_id=:id AND task_type IN ('CHECKIN_CONFIRM','ORIGINAL_FOLLOWUP')")
                .param("id", fixture.caseId()).query(Long.class).single());
        String audit = jdbc.sql("""
                SELECT details->>'observationJson' FROM case_actions
                WHERE case_id=:id AND action_type='REPLACEMENT_WORK'
                    AND details->>'requestId'=:request ORDER BY id DESC LIMIT 1
                """).param("id", fixture.caseId()).param("request", second.replacementBookingId()).query(String.class).single();
        assertEquals(JSON.readTree(observed.get()), JSON.readTree(audit), "Verified provider evidence must survive publication rollback");
        assertEquals("CONFIRMED", view(COUNSELOR, first.replacementBookingId()).path("status").asString());
    }

    @Test
    void resultReadsAreConsistentAndCannotCallSupplierClaimWorkOrChangeRows() {
        var accepted = request("readonly");
        String before = snapshot();
        var result = view(COUNSELOR, accepted.replacementBookingId());
        assertEquals(result, view(CUSTOMER, accepted.replacementBookingId()));
        assertEquals("PROCESSING", result.path("status").asString());
        assertEquals("SYNTHETIC_DEMO", result.path("clockMode").asString());
        for (String field : List.of("request_payload", "response_payload", "requested_by", "requestHash", "guestReference", "rawJson"))
            assertFalse(result.has(field));
        assertEquals(before, snapshot());
        verifyNoInteractions(supplier);
    }

    @Test
    void confirmedNoBookingFailurePreservesHistoryAndRequiresFreshQuoteOfferAndChoice() {
        doAnswer(call -> failed(call.getArgument(0), "PRICE_CHANGED", 490000L)).when(supplier).create(any());
        var old = request("price-changed");
        assertEquals("FAILED", service.execute(old.replacementBookingId()).status());
        var failure = view(CUSTOMER, old.replacementBookingId());
        assertEquals("PRICE_CHANGED", failure.path("failureCode").asString());
        assertEquals(490000L, failure.path("currentPrice").path("totalAmount").longValue());
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM case_tasks WHERE case_id=:id AND task_type IN ('CHECKIN_CONFIRM','ORIGINAL_FOLLOWUP')")
                .param("id", fixture.caseId()).query(Long.class).single());
        assertCode(409, "NEW_OFFER_REQUIRED", () -> offers.select(CUSTOMER, fixture.code(), offer.offerId(), codes().get(1), "failed-offer-choice", AT, true));
        clock.advance(60);
        prepareChoice("fresh-after-failure");
        var fresh = request("new-reception");
        assertNotEquals(old.replacementBookingId(), fresh.replacementBookingId());
        assertEquals(2L, count("replacement_requests"));
        assertEquals("FAILED", view(CUSTOMER, old.replacementBookingId()).path("status").asString());
    }

    @Test
    void stalePostClaimCannotOverwriteARecoveredConfirmationOrDuplicateItsTasks() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            assertNoTransactionDuringHttp(); entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return failed(call.getArgument(0), "OUT_OF_STOCK", null);
        }).when(supplier).create(any());
        var accepted = request("stale-worker");
        try (var pool = Executors.newSingleThreadExecutor()) {
            var stale = pool.submit(() -> service.execute(accepted.replacementBookingId()));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            clock.advance(46);
            try {
                assertEquals("CONFIRMED", service.execute(accepted.replacementBookingId()).status());
            } finally { release.countDown(); }
            assertEquals("CONFIRMED", stale.get(10, TimeUnit.SECONDS).status());
        }
        assertEquals("CONFIRMED", view(COUNSELOR, accepted.replacementBookingId()).path("status").asString());
        assertEquals(2L, jdbc.sql("SELECT count(*) FROM case_tasks WHERE case_id=:id AND task_type IN ('CHECKIN_CONFIRM','ORIGINAL_FOLLOWUP')")
                .param("id", fixture.caseId()).query(Long.class).single());
        verify(supplier, times(1)).create(any());
        verify(supplier, times(1)).lookup(any());
    }

    @Test
    void explicitManualRecoveryResumesOnlyLookupForTheExistingRequest() {
        doThrow(uncertain()).when(supplier).create(any());
        doThrow(uncertain()).when(supplier).lookup(any());
        var accepted = request("manual-recovery");
        assertTrue(exhaust(accepted.replacementBookingId()).manualRequired());
        int posts = mockingDetails(supplier).getInvocations().stream().filter(i -> i.getMethod().getName().equals("create")).toList().size();
        doAnswer(call -> confirmed(call.getArgument(0))).when(supplier).lookup(any());
        service.resume(accepted.replacementBookingId());
        clock.advance(1);
        var execution = service.execute(accepted.replacementBookingId());
        assertEquals("CONFIRMED", execution.status());
        assertEquals(posts, mockingDetails(supplier).getInvocations().stream().filter(i -> i.getMethod().getName().equals("create")).toList().size());
        assertEquals(1L, count("replacement_requests"));
    }

    @Test
    void manualRecoveryDuringAnActiveLookupCannotBeTurnedBackIntoAPostByItsLateNotFound() throws Exception {
        doThrow(uncertain()).when(supplier).create(any());
        var accepted = request("manual-active-lookup");
        assertEquals("RESULT_UNKNOWN", service.execute(accepted.replacementBookingId()).status());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var lookups = new AtomicInteger();
        doAnswer(call -> {
            assertNoTransactionDuringHttp();
            if (lookups.incrementAndGet() == 1) {
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                throw new ReplacementSupplierException(ReplacementSupplierException.Kind.REQUEST_NOT_FOUND, 404, "BOOKING_REQUEST_NOT_FOUND");
            }
            return confirmed(call.getArgument(0));
        }).when(supplier).lookup(any());
        clock.advance(3);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var inFlight = pool.submit(() -> service.execute(accepted.replacementBookingId()));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            try {
                var resumed = service.resume(accepted.replacementBookingId());
                var repeated = service.resume(accepted.replacementBookingId());
                assertEquals(resumed.getAttempts(), repeated.getAttempts(), "Concurrent manual recovery cannot reset the active claim budget");
            } finally { release.countDown(); }
            assertEquals("RESULT_UNKNOWN", inFlight.get(10, TimeUnit.SECONDS).status());
        }
        clock.advance(3);
        assertEquals("CONFIRMED", service.execute(accepted.replacementBookingId()).status());
        verify(supplier, times(1)).create(any());
        verify(supplier, times(2)).lookup(any());
        assertEquals(1L, count("replacement_requests"));
    }

    private ReplacementService.Acceptance request(String key) {
        return service.request(COUNSELOR, fixture.code(), selection.selectionId(), key, AT, true);
    }

    private JsonNode view(Principal principal, String requestId) { return JSON.readTree(service.view(principal, requestId)); }

    private ReplacementService.Execution exhaust(String requestId) {
        var execution = service.execute(requestId);
        for (int attempt=0; attempt<12 && !execution.manualRequired(); attempt++) {
            clock.advance(31);
            execution = service.execute(requestId);
        }
        assertTrue(execution.manualRequired(), "Retry budget or deadline must be finite");
        return execution;
    }

    private Outcome confirmed(FrozenRequest request) {
        return confirmed(request, "ALT-" + request.requestId().substring(4));
    }

    private Outcome confirmed(FrozenRequest request, String supplierBookingId) {
        return new Outcome("CONFIRMED", supplierBookingId, request.expectedAmount(), now(),
                null, null, null, JSON.writeValueAsString(Map.of("requestId",request.requestId(),"status","CONFIRMED",
                    "quoteId",request.quoteId(),"propertyId",request.propertyId(),"supplierBookingId",supplierBookingId,
                    "confirmedPrice",Map.of("totalAmount",request.expectedAmount(),"currency",request.currency()),
                    "confirmedAt",now().toString())));
    }

    private Outcome failed(FrozenRequest request, String code, Long current) {
        Map<String,Object> raw = new LinkedHashMap<>();
        raw.put("code",code); raw.put("requestId",request.requestId());
        if (current != null) { raw.put("currentTotalAmount",current); raw.put("currency","KRW"); }
        return new Outcome("FAILED", null, null, null, code, current, null, JSON.writeValueAsString(raw));
    }

    private static ReplacementSupplierException uncertain() {
        return new ReplacementSupplierException(ReplacementSupplierException.Kind.TIMEOUT, null, "SUPPLIER_TIMEOUT");
    }

    private static void assertNoTransactionDuringHttp() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "External supplier HTTP must run after commit and outside row locks");
    }

    private void prepareChoice(String key) {
        var job = searches.request(COUNSELOR, fixture.code(), new QuoteSearchService.SearchOptions(3000,300000L), key, AT, true);
        jdbc.sql("UPDATE jobs SET created_at=:at WHERE job_code=:code").param("at",now()).param("code",job.searchJobId()).update();
        assertEquals("COMPLETED", searches.execute(job.searchJobId()).status());
        offer = offers.send(COUNSELOR, fixture.code(), codes(), "offer-"+key, AT, true);
        selection = offers.select(CUSTOMER, fixture.code(), offer.offerId(), codes().getFirst(), "choice-"+key, AT, true);
    }

    private List<String> codes() {
        return jdbc.sql("SELECT quote_code FROM alternative_quotes WHERE case_id=:id AND search_job_id=(SELECT max(id) FROM jobs WHERE case_id=:id AND kind='QUOTE_SEARCH' AND status='COMPLETED') ORDER BY id")
                .param("id",fixture.caseId()).query(String.class).list();
    }

    private Fixture createFixture() {
        String suffix=UUID.randomUUID().toString().substring(0,12);
        long booking=jdbc.sql("""
                INSERT INTO bookings (booking_code,customer_id,hotel_id,supplier_booking_id,check_in_date,
                    check_out_date,adults,rooms,original_total_amount,created_at,updated_at)
                SELECT :code,u.id,h.id,:supplier,DATE '2026-11-10',DATE '2026-11-13',2,1,450000,:at,:at
                FROM users u CROSS JOIN hotels h WHERE u.subject_key='demo-customer-001' AND h.hotel_code='HOTEL-001' RETURNING id
                """).param("code","YB-R-"+suffix).param("supplier","SUP-R-"+suffix).param("at",AT.minusHours(1)).query(Long.class).single();
        jdbc.sql("INSERT INTO booking_nights (booking_id,stay_date) SELECT :id,DATE '2026-11-10'+n FROM generate_series(0,2)n")
                .param("id",booking).update();
        String code="C-"+CASE_NUMBER.incrementAndGet();
        long caseId=jdbc.sql("INSERT INTO cases (case_code,booking_id,status,priority) VALUES (:code,:booking,'OPEN','URGENT') RETURNING id")
                .param("code",code).param("booking",booking).query(Long.class).single();
        jdbc.sql("INSERT INTO case_tasks (case_id,booking_id,task_type,status,is_required) VALUES (:caseId,:booking,'CONTACT_CUSTOMER','TODO',true)")
                .param("caseId",caseId).param("booking",booking).update();
        return new Fixture(booking,caseId,code);
    }

    private long count(String table) { return jdbc.sql("SELECT count(*) FROM "+table+" WHERE case_id=:id").param("id",fixture.caseId()).query(Long.class).single(); }
    private String snapshot() {
        return jdbc.sql("""
                SELECT jsonb_build_object(
                  'requests',(SELECT jsonb_agg(to_jsonb(r) ORDER BY id) FROM replacement_requests r WHERE case_id=:id),
                  'actions',(SELECT jsonb_agg(to_jsonb(a) ORDER BY id) FROM case_actions a WHERE case_id=:id),
                  'messages',(SELECT jsonb_agg(to_jsonb(m) ORDER BY id) FROM chat_messages m WHERE case_id=:id),
                  'tasks',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM case_tasks t WHERE case_id=:id))::text
                """).param("id",fixture.caseId()).query(String.class).single();
    }
    private OffsetDateTime now() { return clock.instant().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC); }
    private static void assertCode(int status, String code, Runnable command) {
        var failure=assertThrows(ResponseStatusException.class,command::run);
        assertEquals(status,failure.getStatusCode().value()); assertEquals(code,failure.getReason());
    }
    private record Fixture(long bookingId,long caseId,String code) {}
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;
        MutableClock(Instant initial) { current=new AtomicReference<>(initial); }
        void advance(long seconds) { current.updateAndGet(at->at.plusSeconds(seconds)); }
        @Override public Instant instant() { return current.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId ignored) { return this; }
    }

    private static SearchResult quoteReply(SearchInput input) {
        List<Map<String,Object>> rows=new ArrayList<>(); List<Quote> quotes=new ArrayList<>();
        for(int i=0;i<3;i++) {
            String letter="ABC".substring(i,i+1),property="HOTEL-R-"+letter,quoteId="QUOTE-"+letter+"-"+input.searchRequestId();
            long base=450000L+i*3000L,fees=30000L,total=base+fees;
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("quoteId",quoteId); row.put("source","SIMULATED_SUPPLIER"); row.put("propertyId",property); row.put("propertyName","가상 호텔 "+letter);
            row.put("distanceMeters",500+i*300); row.put("roomTypeId","ROOM-"+letter); row.put("roomName","가상 객실 "+letter); row.put("ratePlanId","RATE-"+letter);
            row.put("maxAdults",2); row.put("breakfastIncluded",false); row.put("availableRoomCount",2);
            row.put("price",Map.of("baseAmount",base,"mandatoryFeesAmount",fees,"totalAmount",total,"currency","KRW"));
            row.put("cancellationPolicy","NON_REFUNDABLE"); row.put("checkedAt",CHECKED.toString()); row.put("expiresAt",EXPIRES.toString());
            rows.add(row); quotes.add(new Quote(quoteId,"SIMULATED_SUPPLIER",property,"가상 호텔 "+letter,500+i*300,"ROOM-"+letter,
                "가상 객실 "+letter,"RATE-"+letter,2,false,2,base,fees,total,"KRW","NON_REFUNDABLE",CHECKED,EXPIRES,JSON.writeValueAsString(row)));
        }
        var stay=Map.of("checkInDate",input.stay().checkInDate().toString(),"checkOutDate",input.stay().checkOutDate().toString(),"adults",input.stay().adults(),"rooms",input.stay().rooms());
        var raw=Map.of("searchRequestId",input.searchRequestId(),"searchStatus","COMPLETED","stay",stay,"checkedAt",CHECKED.toString(),"currency","KRW","quotes",rows);
        var request=Map.of("searchRequestId",input.searchRequestId(),"originPropertyId",input.originPropertyId(),"stay",stay,
                "filters",Map.of("radiusMeters",input.radiusMeters(),"currency","KRW","maxAverageNightlyAmount",300000));
        return new SearchResult(input.searchRequestId(),input.stay(),CHECKED,"KRW",quotes,JSON.writeValueAsString(request),JSON.writeValueAsString(raw));
    }
}
