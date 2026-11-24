package com.csro.backend.offers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.csro.backend.quotes.QuoteContracts.*;
import com.csro.backend.quotes.QuoteRankingClient;
import com.csro.backend.quotes.QuoteSearchService;
import com.csro.backend.quotes.QuoteSupplierClient;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** Real V1 writes, with real quote-search snapshots and only the provider/Agent boundaries doubled. */
@EnabledIfEnvironmentVariable(named = "OFFER_SELECTION_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OfferSelectionIntegrationTest {
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-09T10:20:00+09:00");
    private static final OffsetDateTime CHECKED = AT.minusMinutes(5);
    private static final OffsetDateTime EXPIRES = AT.plusMinutes(5);
    private static final Principal COUNSELOR = () -> "demo-counselor-001";
    private static final Principal CUSTOMER = () -> "demo-customer-001";
    private static final Principal OTHER_CUSTOMER = () -> "demo-customer-002";
    private static final AtomicInteger CASE_NUMBER = new AtomicInteger(700000 + (int) (System.nanoTime() % 100000));
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private JdbcClient jdbc;
    private PlatformTransactionManager manager;
    private MutableClock clock;
    private OfferSelectionService service;
    private QuoteSearchService searches;
    private QuoteSupplierClient supplier;
    private QuoteRankingClient ranking;
    private Fixture fixture;

    @BeforeAll
    void verifyConfiguredEndpointAndActualIdentityBeforeFlyway() {
        var settings = OfferSelectionTestDatabase.validate(System.getenv());
        var source = new DriverManagerDataSource(settings.url(), settings.user(), settings.password());
        OfferSelectionTestDatabase.verifyIdentity(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration", "classpath:db/local")
                .cleanDisabled(true).load().migrate();
        jdbc = JdbcClient.create(source);
        manager = new DataSourceTransactionManager(source);
    }

    @BeforeEach
    void separateEachCaseWithoutRemovingExistingRows() {
        clock = new MutableClock(Instant.parse("2026-11-24T12:00:00.123456789Z"));
        service = new OfferSelectionService(jdbc, manager, clock);
        supplier = mock(QuoteSupplierClient.class);
        ranking = mock(QuoteRankingClient.class);
        searches = new QuoteSearchService(supplier, ranking, jdbc, manager, clock);
        doAnswer(call -> reply(call.getArgument(0), false, false)).when(supplier).search(any());
        doThrow(new IllegalStateException("simulated unavailable ranking boundary")).when(ranking).rank(any());
        fixture = createFixture();
        search(fixture, "initial-search");
    }

    @Test
    void sendsThreeDistinctSavedPropertiesInOrderWithMessageAndFrozenAudit() {
        var result = send("send-three");
        assertEquals(fixture.caseCode(), result.caseId());
        assertEquals(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS), result.sentAt().toInstant(),
                "Audit time is actual server time at PostgreSQL precision, not the virtual quote time");
        assertEquals(1L, count("offers"));
        assertEquals(3L, count("offer_items"));
        assertEquals(1L, count("chat_messages"));
        assertEquals(1L, count("case_actions"));
        assertEquals(choices(), jdbc.sql("""
                SELECT q.quote_code FROM offer_items i JOIN alternative_quotes q ON q.id=i.quote_id
                JOIN offers o ON o.id=i.offer_id WHERE o.offer_code=:code ORDER BY i.display_order
                """).param("code", result.offerId()).query(String.class).list());
        assertEquals(3L, jdbc.sql("""
                SELECT count(DISTINCT q.hotel_id) FROM offer_items i JOIN alternative_quotes q ON q.id=i.quote_id
                WHERE i.case_id=:id
                """).param("id", fixture.caseId()).query(Long.class).single());
        assertTrue(jdbc.sql("SELECT ranking_id IS NULL FROM offers WHERE offer_code=:code")
                .param("code", result.offerId()).query(Boolean.class).single(), "Agent failure permits a manual valid supplier proposal");
        var audit = jdbc.sql("SELECT details::text FROM case_actions WHERE case_id=:id AND action_type='OFFER_SENT'")
                .param("id", fixture.caseId()).query(String.class).single();
        var details = JSON.readTree(audit);
        assertEquals("offer-selection-v1", details.path("protocol").asText());
        assertEquals(result.offerId(), details.path("offerId").asText());
        assertEquals("FULL_STAY", details.path("coverageMode").asText());
        assertEquals(450000L, details.path("originalAmount").asLong());
        jdbc.sql("UPDATE bookings SET original_total_amount=999999 WHERE id=:id").param("id", fixture.bookingId()).update();
        assertEquals(audit, jdbc.sql("SELECT details::text FROM case_actions WHERE case_id=:id AND action_type='OFFER_SENT'")
                .param("id", fixture.caseId()).query(String.class).single(), "Sent comparison basis must not follow later booking changes");
        assertEquals(0L, count("replacement_requests"));
        assertEquals(0L, count("checkin_confirmations"));
        assertEquals("OPEN", caseStatus());
    }

    @Test
    void rejectsWrongCountSamePropertyForeignCaseAndOldSearchWithoutPartialWrites() {
        assertCode(400, "INVALID_OFFER_REQUEST", () -> service.send(COUNSELOR, fixture.caseCode(), choices().subList(0,2), "two", AT, true));
        assertCode(400, "INVALID_OFFER_REQUEST", () -> service.send(COUNSELOR, fixture.caseCode(),
                List.of(choices().getFirst(), choices().getFirst(), choices().getLast()), "duplicate", AT, true));
        var foreign = createFixture();
        search(foreign, "foreign-search");
        assertCode(409, "QUOTE_INVALID", () -> service.send(COUNSELOR, fixture.caseCode(),
                List.of(choices().getFirst(), choices().get(1), codes(foreign).getFirst()), "foreign", AT, true));
        var old = choices();
        search(fixture, "newer-search");
        assertCode(409, "QUOTE_INVALID", () -> service.send(COUNSELOR, fixture.caseCode(), old, "old-search", AT, true));
        var sameProperty = createFixture();
        doAnswer(call -> reply(call.getArgument(0), false, true)).when(supplier).search(any());
        search(sameProperty, "same-property");
        assertCode(409, "QUOTE_INVALID", () -> service.send(COUNSELOR, sameProperty.caseCode(),
                codes(sameProperty).subList(0,3), "same-property-send", AT, true));
        assertEquals(0L, count("offers"));
        assertEquals(0L, count("offer_items"));
        assertEquals(0L, count("chat_messages"));
        assertEquals(0L, count("case_actions"));
    }

    @Test
    void rejectsExpiredBoundaryAndTamperedStoredSupplierSnapshot() {
        assertCode(409, "QUOTE_EXPIRED", () -> service.send(COUNSELOR, fixture.caseCode(), choices(), "expired", EXPIRES, true));
        jdbc.sql("UPDATE alternative_quotes SET total_amount=total_amount+1,base_amount=base_amount+1 WHERE quote_code=:code")
                .param("code", choices().getFirst()).update();
        assertCode(409, "QUOTE_INVALID", () -> send("tampered"));
        assertEquals(0L, count("offers"));
        assertEquals(0L, count("case_actions"));
    }

    @Test
    void partialOfferRequiresTheCurrentVerifiedFailureGroupAndKeepsNoFullStayDifference() {
        verifyUnavailable(fixture, LocalDate.of(2026,11,10));
        doAnswer(call -> {
            SearchInput input = call.getArgument(0);
            return reply(input, input.stay().checkOutDate().equals(LocalDate.of(2026,11,13)), false);
        }).when(supplier).search(any());
        search(fixture, "partial-search");
        var sent = send("partial-offer");
        var details = JSON.readTree(jdbc.sql("SELECT details::text FROM case_actions WHERE case_id=:id AND action_type='OFFER_SENT'")
                .param("id", fixture.caseId()).query(String.class).single());
        assertEquals("VERIFIED_FAILURE_ONLY", details.path("coverageMode").asText());
        assertTrue(details.path("originalAmount").isNull() || details.path("originalAmount").isMissingNode());
        jdbc.sql("UPDATE booking_nights SET fulfillment_status='UNKNOWN',fact_status='UNCONFIRMED',accepted_assertion_id=NULL WHERE booking_id=:id")
                .param("id", fixture.bookingId()).update();
        assertCode(409, "QUOTE_INVALID", () -> send("no-longer-verified"));
        assertEquals(sent.offerId(), activeOffer());
        assertEquals(1L, count("offers"));
    }

    @Test
    void authenticatesRolesAndCurrentOwnershipBeforeAnyWriteOrReplay() {
        var sent = send("ownership");
        assertCode(401, "UNAUTHENTICATED", () -> service.send(null, fixture.caseCode(), choices(), "ownership", AT, true));
        assertCode(403, "FORBIDDEN_ROLE", () -> service.send(CUSTOMER, fixture.caseCode(), choices(), "ownership", AT, true));
        assertCode(403, "FORBIDDEN_ROLE", () -> service.select(COUNSELOR, fixture.caseCode(), sent.offerId(), choices().getFirst(), "counselor-choice", AT, true));
        assertCode(404, "CASE_NOT_FOUND", () -> service.select(OTHER_CUSTOMER, fixture.caseCode(), sent.offerId(), choices().getFirst(), "other-choice", AT, true));
        var selected = select(sent.offerId(), choices().getFirst(), "owner-choice");
        jdbc.sql("UPDATE bookings SET customer_id=(SELECT id FROM users WHERE subject_key='demo-customer-002') WHERE id=:id")
                .param("id", fixture.bookingId()).update();
        assertCode(404, "CASE_NOT_FOUND", () -> select(sent.offerId(), choices().getFirst(), "owner-choice"));
        assertEquals(selected.selectionId(), currentSelection());
        assertEquals(1L, count("alternative_selections"));
    }

    @Test
    void onlyTheCurrentActuallySentUnexpiredItemCanBeSelected() {
        var first = send("first-offer");
        var all = codes(fixture);
        assertCode(409, "QUOTE_NOT_OFFERED", () -> select(first.offerId(), all.get(3), "unsent"));
        var second = send("second-offer");
        assertCode(409, "OFFER_NOT_ACTIVE", () -> select(first.offerId(), all.getFirst(), "old-offer"));
        assertCode(409, "QUOTE_EXPIRED", () -> service.select(CUSTOMER, fixture.caseCode(), second.offerId(),
                all.getFirst(), "expired-choice", EXPIRES, true));
        var chosen = select(second.offerId(), all.getFirst(), "valid-choice");
        assertEquals(chosen.selectionId(), currentSelection());
        assertEquals(1L, count("alternative_selections"));
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM chat_messages WHERE case_id=:id AND sender_type='SYSTEM' AND message_type='SYSTEM'")
                .param("id", fixture.caseId()).query(Long.class).single());
        assertEquals(0L, count("replacement_requests"));
        assertEquals(0L, count("checkin_confirmations"));
        assertEquals("OPEN", caseStatus());
    }

    @Test
    void replacingAnOfferPreservesItsItemsAndTheSupersededSelectionHistory() {
        var first = send("offer-a");
        var chosen = select(first.offerId(), choices().getFirst(), "selection-a");
        clock.advance(1);
        var second = send("offer-b");
        assertEquals(second.offerId(), activeOffer());
        assertNull(currentSelection());
        assertEquals(2L, count("offers"));
        assertEquals(6L, count("offer_items"));
        assertEquals(1L, count("alternative_selections"));
        assertNotNull(jdbc.sql("SELECT superseded_at FROM alternative_selections WHERE selection_code=:code")
                .param("code", chosen.selectionId()).query(OffsetDateTime.class).single());
        assertCode(409, "OFFER_NOT_ACTIVE", () -> select(first.offerId(), choices().getFirst(), "old-selection-new-key"));
    }

    @Test
    void offerReplayAfterSupersedingExpiryAndClosureReturnsTheOriginalWithoutRestoringIt() {
        var original = send("replay-offer");
        clock.advance(1);
        var current = send("current-offer");
        closeCase();
        var replay = service.send(COUNSELOR, fixture.caseCode(), choices(), "replay-offer", EXPIRES.plusHours(1), true);
        assertEquals(original, replay);
        assertEquals(current.offerId(), activeOffer());
        assertEquals(2L, count("offers"));
        assertEquals(2L, count("chat_messages"));
        assertEquals(2L, count("case_actions"));
        var changed = new ArrayList<>(choices());
        java.util.Collections.swap(changed, 0,1);
        assertCode(409, "IDEMPOTENCY_KEY_REUSED", () -> service.send(COUNSELOR, fixture.caseCode(), changed, "replay-offer", AT, true));
        assertCode(409, "CASE_CLOSED", () -> send("closed-new-offer"));
    }

    @Test
    void selectionReplayNeverResurrectsAnOldChoiceAndNewKeysRecordTheirOwnIntent() {
        var offer = send("choice-history");
        var first = select(offer.offerId(), choices().getFirst(), "first-choice");
        clock.advance(1);
        var second = select(offer.offerId(), choices().get(1), "second-choice");
        clock.advance(1);
        var third = select(offer.offerId(), choices().get(1), "third-choice");
        assertNotEquals(second.selectionId(), third.selectionId());
        closeCase();
        assertEquals(first, service.select(CUSTOMER, fixture.caseCode(), offer.offerId(), choices().getFirst(),
                "first-choice", EXPIRES.plusHours(1), true));
        assertEquals(third.selectionId(), currentSelection());
        assertEquals(3L, count("alternative_selections"));
        assertEquals(4L, count("chat_messages"));
        assertCode(409, "IDEMPOTENCY_KEY_REUSED", () -> select(offer.offerId(), choices().get(1), "first-choice"));
        assertCode(409, "CASE_CLOSED", () -> select(offer.offerId(), choices().getFirst(), "closed-choice"));
    }

    @Test
    void simultaneousIdenticalOfferAndSelectionKeysCreateExactlyOneRecordEach() throws Exception {
        var start = new CountDownLatch(1);
        OfferSelectionService.SendResult sent;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return send("concurrent-offer"); });
            var second = pool.submit(() -> { start.await(); return send("concurrent-offer"); });
            start.countDown();
            sent = first.get(10, TimeUnit.SECONDS);
            assertEquals(sent, second.get(10, TimeUnit.SECONDS));
        }
        var choose = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { choose.await(); return select(sent.offerId(), choices().getFirst(), "concurrent-choice"); });
            var second = pool.submit(() -> { choose.await(); return select(sent.offerId(), choices().getFirst(), "concurrent-choice"); });
            choose.countDown();
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1L, count("offers"));
        assertEquals(1L, count("alternative_selections"));
        assertEquals(2L, count("chat_messages"));
        assertEquals(2L, count("case_actions"));
    }

    @Test
    void competingOfferReplacementAndSelectionCannotLeaveACurrentChoiceForASupersededOffer() throws Exception {
        var first = send("race-initial");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var replace = pool.submit(() -> { start.await(); return send("race-replacement"); });
            var choose = pool.submit(() -> {
                start.await();
                try { select(first.offerId(), choices().getFirst(), "race-choice"); }
                catch (ResponseStatusException expected) {
                    assertEquals(409, expected.getStatusCode().value());
                    assertEquals("OFFER_NOT_ACTIVE", expected.getReason());
                }
                return true;
            });
            start.countDown();
            assertEquals(replace.get(10, TimeUnit.SECONDS).offerId(), activeOffer());
            assertTrue(choose.get(10, TimeUnit.SECONDS));
        }
        assertNull(currentSelection());
        assertEquals(0L, jdbc.sql("""
                SELECT count(*) FROM alternative_selections s JOIN offers o ON o.id=s.offer_id
                WHERE s.case_id=:id AND s.superseded_at IS NULL AND o.status<>'ACTIVE'
                """).param("id", fixture.caseId()).query(Long.class).single());
    }

    @Test
    void processingUnknownAndConfirmedReservationsBlockNewOfferAndChoiceButAllowSafeReplay() {
        for (String status : List.of("PROCESSING", "RESULT_UNKNOWN", "CONFIRMED")) {
            fixture = createFixture();
            search(fixture, "guard-" + status);
            var offer = send("reserved-offer");
            var selected = select(offer.offerId(), choices().getFirst(), "reserved-choice");
            reservation(selected.selectionId(), status);
            assertCode(409, "REPLACEMENT_LOCKED", () -> send("new-after-reservation"));
            assertCode(409, "REPLACEMENT_LOCKED", () -> select(offer.offerId(), choices().get(1), "new-choice-after-reservation"));
            assertEquals(offer, send("reserved-offer"));
            assertEquals(selected, select(offer.offerId(), choices().getFirst(), "reserved-choice"));
            assertEquals(1L, count("offers"));
            assertEquals(1L, count("alternative_selections"));
            assertEquals(1L, count("replacement_requests"));
        }
    }

    @Test
    void confirmedFailureRequiresANewSearchAndOfferWhilePreservingTheOldRequest() {
        var oldOffer = send("failed-offer");
        var oldSelection = select(oldOffer.offerId(), choices().getFirst(), "failed-choice");
        reservation(oldSelection.selectionId(), "FAILED");
        assertCode(409, "QUOTE_REFRESH_REQUIRED", () -> send("stale-after-failure"));
        assertCode(409, "NEW_OFFER_REQUIRED", () -> select(oldOffer.offerId(), choices().get(1), "same-offer-after-failure"));
        clock.advance(10);
        search(fixture, "fresh-after-failure");
        var fresh = send("fresh-offer");
        var selected = select(fresh.offerId(), choices().getFirst(), "fresh-choice");
        assertEquals(selected.selectionId(), currentSelection());
        assertEquals(1L, count("replacement_requests"));
        assertEquals("FAILED", jdbc.sql("SELECT status FROM replacement_requests WHERE case_id=:id")
                .param("id", fixture.caseId()).query(String.class).single());
        assertEquals(2L, count("offers"));
        assertEquals(2L, count("alternative_selections"));
    }

    private OfferSelectionService.SendResult send(String key) {
        return service.send(COUNSELOR, fixture.caseCode(), choices(), key, AT, true);
    }

    private OfferSelectionService.SelectionResult select(String offer, String quote, String key) {
        return service.select(CUSTOMER, fixture.caseCode(), offer, quote, key, AT, true);
    }

    private List<String> choices() { return codes(fixture).subList(0,3); }

    private List<String> codes(Fixture target) {
        return jdbc.sql("""
                SELECT quote_code FROM alternative_quotes WHERE case_id=:id AND search_job_id=
                (SELECT max(id) FROM jobs WHERE case_id=:id AND kind='QUOTE_SEARCH' AND status='COMPLETED') ORDER BY id
                """).param("id", target.caseId()).query(String.class).list();
    }

    private void search(Fixture target, String key) {
        var job = searches.request(COUNSELOR, target.caseCode(), new QuoteSearchService.SearchOptions(3000, 300000L), key, AT, true);
        jdbc.sql("UPDATE jobs SET created_at=:at WHERE job_code=:code")
                .param("at", clock.instant().atOffset(ZoneOffset.UTC)).param("code", job.searchJobId()).update();
        assertEquals("COMPLETED", searches.execute(job.searchJobId()).status());
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE case_id=:id")
                .param("id", fixture.caseId()).query(Long.class).single();
    }

    private String activeOffer() {
        return jdbc.sql("SELECT offer_code FROM offers WHERE case_id=:id AND status='ACTIVE'")
                .param("id", fixture.caseId()).query(String.class).optional().orElse(null);
    }

    private String currentSelection() {
        return jdbc.sql("SELECT selection_code FROM alternative_selections WHERE case_id=:id AND superseded_at IS NULL")
                .param("id", fixture.caseId()).query(String.class).optional().orElse(null);
    }

    private String caseStatus() {
        return jdbc.sql("SELECT status FROM cases WHERE id=:id").param("id", fixture.caseId()).query(String.class).single();
    }

    private void closeCase() {
        jdbc.sql("UPDATE cases SET status='CLOSED',closed_at=:at,close_reason='TEST_COMPLETED' WHERE id=:id")
                .param("at", clock.instant().atOffset(ZoneOffset.UTC)).param("id", fixture.caseId()).update();
    }

    private Fixture createFixture() {
        String suffix = UUID.randomUUID().toString().substring(0,12);
        long booking = jdbc.sql("""
                INSERT INTO bookings (booking_code,customer_id,hotel_id,supplier_booking_id,check_in_date,
                    check_out_date,adults,rooms,original_total_amount,created_at,updated_at)
                SELECT :code,u.id,h.id,:supplier,DATE '2026-11-10',DATE '2026-11-13',2,1,450000,:at,:at
                FROM users u CROSS JOIN hotels h WHERE u.subject_key='demo-customer-001' AND h.hotel_code='HOTEL-001'
                RETURNING id
                """).param("code", "YB-O-" + suffix).param("supplier", "SUP-O-" + suffix)
                .param("at", AT.minusHours(1)).query(Long.class).single();
        jdbc.sql("INSERT INTO booking_nights (booking_id,stay_date) SELECT :id,DATE '2026-11-10'+n FROM generate_series(0,2) n")
                .param("id", booking).update();
        String code = "C-" + CASE_NUMBER.incrementAndGet();
        long caseId = jdbc.sql("INSERT INTO cases (case_code,booking_id,status,priority) VALUES (:code,:booking,'OPEN','URGENT') RETURNING id")
                .param("code", code).param("booking", booking).query(Long.class).single();
        return new Fixture(booking, caseId, code);
    }

    private void verifyUnavailable(Fixture target, LocalDate date) {
        long night = jdbc.sql("SELECT id FROM booking_nights WHERE booking_id=:id AND stay_date=:date")
                .param("id", target.bookingId()).param("date", date).query(Long.class).single();
        long evidence = jdbc.sql("INSERT INTO evidence_records (booking_id,source_type,raw_payload,received_at) VALUES (:id,'HOTEL_API','{}'::jsonb,:at) RETURNING id")
                .param("id", target.bookingId()).param("at", AT).query(Long.class).single();
        long assertion = jdbc.sql("""
                INSERT INTO evidence_nights (booking_id,evidence_id,booking_night_id,reported_status,review_status,reviewed_at)
                VALUES (:booking,:evidence,:night,'UNAVAILABLE','ACCEPTED',:at) RETURNING id
                """).param("booking", target.bookingId()).param("evidence", evidence).param("night", night).param("at", AT).query(Long.class).single();
        jdbc.sql("UPDATE booking_nights SET fulfillment_status='UNAVAILABLE',fact_status='VERIFIED',accepted_assertion_id=:assertion WHERE id=:id")
                .param("assertion", assertion).param("id", night).update();
    }

    private void reservation(String selectionCode, String state) {
        String request = "RR-" + UUID.randomUUID();
        boolean confirmed = "CONFIRMED".equals(state);
        jdbc.sql("""
                INSERT INTO replacement_requests (request_code,selection_id,idempotency_key,request_hash,status,
                    expected_amount,confirmed_amount,currency,supplier_booking_id,request_payload,confirmed_at,
                    created_at,updated_at,case_id,requested_by,failure_code)
                SELECT :request,s.id,:request,:hash,:state,q.total_amount,:amount,'KRW',:supplier,'{}'::jsonb,
                    :confirmedAt,:at,:at,s.case_id,u.id,:failure
                FROM alternative_selections s JOIN alternative_quotes q ON q.id=s.quote_id CROSS JOIN users u
                WHERE s.selection_code=:selection AND u.subject_key='demo-counselor-001'
                """).param("request", request).param("hash", "a".repeat(64)).param("state", state)
                .param("amount", confirmed ? totalForSelection(selectionCode) : null)
                .param("supplier", confirmed ? "SUP-" + UUID.randomUUID() : null)
                .param("confirmedAt", confirmed ? clock.instant().atOffset(ZoneOffset.UTC) : null)
                .param("at", clock.instant().atOffset(ZoneOffset.UTC)).param("selection", selectionCode)
                .param("failure", "FAILED".equals(state) ? "PRICE_CHANGED" : null).update();
    }

    private long totalForSelection(String code) {
        return jdbc.sql("SELECT q.total_amount FROM alternative_quotes q JOIN alternative_selections s ON s.quote_id=q.id WHERE s.selection_code=:code")
                .param("code", code).query(Long.class).single();
    }

    private static void assertCode(int status, String code, Runnable command) {
        var failure = assertThrows(ResponseStatusException.class, command::run);
        assertEquals(status, failure.getStatusCode().value());
        assertEquals(code, failure.getReason());
    }

    private static SearchResult reply(SearchInput input, boolean empty, boolean sameProperty) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Quote> quotes = new ArrayList<>();
        int nights = (int) java.time.temporal.ChronoUnit.DAYS.between(input.stay().checkInDate(), input.stay().checkOutDate());
        for (int i=0; !empty && i<4; i++) {
            String letter = "ABCD".substring(i,i+1);
            String property = "HOTEL-O-" + (sameProperty ? "A" : letter);
            String quoteId = "QUOTE-" + letter + "-" + input.searchRequestId();
            long base = (150000L + i*1000L)*nights;
            long fees = 10000L*nights;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("quoteId",quoteId); row.put("source","SIMULATED_SUPPLIER"); row.put("propertyId",property);
            row.put("propertyName","가상 호텔 " + letter); row.put("distanceMeters", 500+i*300);
            row.put("roomTypeId","ROOM-"+letter); row.put("roomName","가상 객실 " + letter); row.put("ratePlanId","RATE-"+letter);
            row.put("maxAdults",2); row.put("breakfastIncluded",false); row.put("availableRoomCount",2);
            row.put("price",Map.of("baseAmount",base,"mandatoryFeesAmount",fees,"totalAmount",base+fees,"currency","KRW"));
            row.put("cancellationPolicy","NON_REFUNDABLE"); row.put("checkedAt",CHECKED.toString()); row.put("expiresAt",EXPIRES.toString());
            rows.add(row);
            quotes.add(new Quote(quoteId,"SIMULATED_SUPPLIER",property,"가상 호텔 "+letter,500+i*300,
                    "ROOM-"+letter,"가상 객실 "+letter,"RATE-"+letter,2,false,2,base,fees,base+fees,"KRW",
                    "NON_REFUNDABLE",CHECKED,EXPIRES,JSON.writeValueAsString(row)));
        }
        var stay = Map.of("checkInDate",input.stay().checkInDate().toString(),"checkOutDate",input.stay().checkOutDate().toString(),
                "adults",input.stay().adults(),"rooms",input.stay().rooms());
        var body = Map.of("searchRequestId",input.searchRequestId(),"searchStatus","COMPLETED","stay",stay,
                "checkedAt",CHECKED.toString(),"currency","KRW","quotes",rows);
        var request = Map.of("searchRequestId",input.searchRequestId(),"originPropertyId",input.originPropertyId(),"stay",stay,
                "filters",Map.of("radiusMeters",input.radiusMeters(),"currency","KRW","maxAverageNightlyAmount",300000));
        return new SearchResult(input.searchRequestId(),input.stay(),CHECKED,"KRW",quotes,
                JSON.writeValueAsString(request),JSON.writeValueAsString(body));
    }

    private record Fixture(long bookingId, long caseId, String caseCode) {}
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> value;
        MutableClock(Instant initial) { value = new AtomicReference<>(initial); }
        void advance(long seconds) { value.updateAndGet(at -> at.plusSeconds(seconds)); }
        @Override public Instant instant() { return value.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
