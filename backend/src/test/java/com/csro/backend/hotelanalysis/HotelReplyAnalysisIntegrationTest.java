package com.csro.backend.hotelanalysis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.AdditionalAnswers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import com.csro.backend.hotelanalysis.HotelReplyAnalysisContracts.Analysis;
import com.csro.backend.hotelanalysis.HotelReplyAnalysisContracts.Claim;
import com.csro.backend.hotelanalysis.HotelReplyAnalysisContracts.Input;
import com.csro.backend.hotelanalysis.HotelReplyAnalysisService.State;

/** Dedicated PostgreSQL only. External analysis is controlled; no facts are adopted from its claims. */
@EnabledIfEnvironmentVariable(named = "HOTEL_ANALYSIS_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HotelReplyAnalysisIntegrationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-25T03:00:00Z"), ZoneOffset.UTC);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LocalDate FIRST = LocalDate.of(2026, 11, 25);
    private static final String MESSAGE = "11월 25일은 객실 제공이 불가능합니다.";
    private JdbcClient jdbc;
    private DataSourceTransactionManager manager;
    private HotelReplyAnalysisClient client;
    private HotelReplyAnalysisService service;
    private long bookingId;
    private long evidenceId;
    private long counselorId;

    @BeforeAll
    void verifyBothConfiguredAndActualDatabaseBeforeFlyway() {
        var settings = HotelAnalysisTestDatabase.validate(System.getenv());
        var source = new DriverManagerDataSource(settings.url(), settings.user(), settings.password());
        jdbc = JdbcClient.create(source);
        var identity = jdbc.sql("SELECT current_database() AS db, current_user AS usr")
                .query((rs, row) -> List.of(rs.getString("db"), rs.getString("usr"))).single();
        assertEquals(List.of(HotelAnalysisTestDatabase.DATABASE, HotelAnalysisTestDatabase.USER), identity);
        Flyway.configure().dataSource(source).locations("classpath:db/migration", "classpath:db/local")
                .cleanDisabled(true).load().migrate();
        manager = new DataSourceTransactionManager(source);
    }

    @BeforeEach
    void appendIsolatedUnknownFixtureWithoutResettingAnyExistingData() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long customer = user("HA-C-" + suffix, "CUSTOMER");
        counselorId = user("HA-S-" + suffix, "COUNSELOR");
        long hotel = jdbc.sql("""
                INSERT INTO hotels(hotel_code,name,time_zone,data_source)
                VALUES (:code,'Analysis fixture hotel','Asia/Seoul','SIMULATED') RETURNING id
                """).param("code", "HA-H-" + suffix).query(Long.class).single();
        bookingId = jdbc.sql("""
                INSERT INTO bookings(booking_code,customer_id,hotel_id,supplier_code,supplier_booking_id,
                    supplier_status,check_in_date,check_out_date,adults,rooms,original_total_amount,currency)
                VALUES (:code,:customer,:hotel,'MOCK_SUPPLIER',:supplier,'CONFIRMED',:first,:checkout,2,1,450000,'KRW')
                RETURNING id
                """).param("code", "YB-HA-" + suffix).param("customer", customer).param("hotel", hotel)
                .param("supplier", "SUP-HA-" + suffix).param("first", FIRST).param("checkout", FIRST.plusDays(3))
                .query(Long.class).single();
        jdbc.sql("INSERT INTO booking_nights(booking_id,stay_date) SELECT :booking,:first+n FROM generate_series(0,2) n")
                .param("booking", bookingId).param("first", FIRST).update();
        evidenceId = jdbc.sql("""
                INSERT INTO evidence_records(booking_id,source_type,source_authenticated,raw_text,occurred_at,received_at)
                VALUES (:booking,'MANUAL_HOTEL',false,:text,:occurred,:received) RETURNING id
                """).param("booking", bookingId).param("text", MESSAGE)
                .param("occurred", OffsetDateTime.parse("2026-11-24T23:54:00+09:00"))
                .param("received", OffsetDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC)).query(Long.class).single();
        client = mock(HotelReplyAnalysisClient.class);
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
            return result(call.getArgument(0), "hotel-reply-extractor-0.1", false, List.of());
        }).when(client).analyze(any());
        service = service(jdbc);
    }

    @Test
    void storesActualSnapshotsAndReusesOnlyIdenticalInputModelAndResult() {
        createCase("OPEN");
        String facts = protectedFacts();
        var version = new AtomicReference<>("hotel-reply-extractor-0.1");
        var warnings = new AtomicReference<List<String>>(List.of());
        doAnswer(call -> result(call.getArgument(0), version.get(), false, warnings.get())).when(client).analyze(any());
        var first = service.analyze(evidenceId);
        assertEquals(State.ANALYZED, first.status());
        assertTrue(first.requiresHumanVerification());
        assertNotNull(first.manualTaskId());
        String original = storedRow(first.analysisId());
        var replay = service.analyze(evidenceId);
        assertEquals(State.REUSED, replay.status());
        assertEquals(first.analysisId(), replay.analysisId());
        assertEquals(first.manualTaskId(), replay.manualTaskId());
        version.set("hotel-reply-extractor-0.2");
        assertNotEquals(first.analysisId(), service.analyze(evidenceId).analysisId());
        warnings.set(List.of("추가 수동 확인이 필요합니다."));
        assertEquals(State.ANALYZED, service.analyze(evidenceId).status());
        assertEquals(3L, analysisCount());
        assertEquals(original, storedRow(first.analysisId()));
        assertEquals(1L, reviewTasks());
        assertEquals(facts, protectedFacts());
        var stored = jdbc.sql("SELECT input_payload::text AS input,result_payload::text AS result FROM agent_analyses WHERE id=:id")
                .param("id", first.analysisId()).query((rs, row) -> List.of(rs.getString("input"), rs.getString("result"))).single();
        Map<?, ?> input = JSON.readValue(stored.getFirst(), Map.class);
        Map<?, ?> response = JSON.readValue(stored.getLast(), Map.class);
        assertEquals(MESSAGE, input.get("originalMessage"));
        assertEquals(List.of("2026-11-25","2026-11-26","2026-11-27"), input.get("stayDates"));
        assertEquals("EV-" + evidenceId, response.get("evidenceId"));
        assertEquals(Boolean.TRUE, response.get("requiresHumanVerification"));
    }

    @Test
    void ambiguousAnalysisCannotUndoAnExistingVerifiedNightOrAuthenticateAManualSource() {
        createCase("WAITING");
        long night = jdbc.sql("SELECT id FROM booking_nights WHERE booking_id=:booking ORDER BY stay_date LIMIT 1")
                .param("booking", bookingId).query(Long.class).single();
        long assertion = jdbc.sql("""
                INSERT INTO evidence_nights(booking_id,evidence_id,booking_night_id,reported_status,
                    review_status,supporting_text,reviewed_by,reviewed_at)
                VALUES (:booking,:evidence,:night,'UNAVAILABLE','ACCEPTED','Existing human verification',:user,:now) RETURNING id
                """).param("booking", bookingId).param("evidence", evidenceId).param("night", night)
                .param("user", counselorId).param("now", OffsetDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC))
                .query(Long.class).single();
        jdbc.sql("UPDATE booking_nights SET fulfillment_status='UNAVAILABLE',fact_status='VERIFIED',accepted_assertion_id=:a WHERE id=:n")
                .param("a", assertion).param("n", night).update();
        String facts = protectedFacts();
        String assertions = jdbc.sql("SELECT to_jsonb(a)::text FROM evidence_nights a WHERE id=:id")
                .param("id", assertion).query(String.class).single();
        doAnswer(call -> result(call.getArgument(0), "hotel-reply-extractor-0.1", true, List.of())).when(client).analyze(any());
        var outcome = service.analyze(evidenceId);
        assertEquals("AMBIGUOUS", outcome.analysisStatus());
        assertEquals(facts, protectedFacts());
        assertEquals(assertions, jdbc.sql("SELECT to_jsonb(a)::text FROM evidence_nights a WHERE id=:id")
                .param("id", assertion).query(String.class).single());
    }

    @Test
    void missingOccurrenceRemainsExplicitNullAndAnInProgressRequiredTaskIsReused() {
        long caseId = createCase("IN_PROGRESS");
        jdbc.sql("UPDATE evidence_records SET occurred_at=NULL WHERE id=:id").param("id", evidenceId).update();
        long task = jdbc.sql("""
                INSERT INTO case_tasks(booking_id,case_id,task_type,status,is_required)
                VALUES (:booking,:case,'VERIFY_HOTEL','IN_PROGRESS',true) RETURNING id
                """).param("booking", bookingId).param("case", caseId).query(Long.class).single();
        doAnswer(call -> {
            Input input = call.getArgument(0);
            assertNull(input.messageOccurredAt());
            assertTrue(HotelReplyAnalysisClient.requestJson(input).contains("\"messageOccurredAt\":null"));
            return result(input, "hotel-reply-extractor-0.1", true, List.of());
        }).when(client).analyze(any());
        assertEquals(task, service.analyze(evidenceId).manualTaskId());
        assertEquals(1L, reviewTasks());
        assertEquals("IN_PROGRESS", jdbc.sql("SELECT status FROM case_tasks WHERE id=:id").param("id", task).query(String.class).single());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUPPLIER", "STRUCTURED", "BLANK", "INCOMPLETE_NIGHTS"})
    void rejectsWrongSourceOrWhole027JsonBeforeHttp(String variant) {
        switch (variant) {
            case "SUPPLIER" -> jdbc.sql("UPDATE evidence_records SET source_type='SUPPLIER_API' WHERE id=:id").param("id", evidenceId).update();
            case "STRUCTURED" -> jdbc.sql("UPDATE evidence_records SET source_type='HOTEL_API',raw_text='{}',raw_payload='{}'::jsonb WHERE id=:id").param("id", evidenceId).update();
            case "BLANK" -> jdbc.sql("UPDATE evidence_records SET raw_text='  ' WHERE id=:id").param("id", evidenceId).update();
            default -> jdbc.sql("UPDATE bookings SET check_out_date=check_out_date+1 WHERE id=:id").param("id", bookingId).update();
        }
        var error = assertThrows(HotelReplyAnalysisException.class, () -> service.analyze(evidenceId));
        assertEquals("INVALID_ANALYSIS_SOURCE", error.code());
        assertEquals(0L, analysisCount());
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AGENT_AUTHENTICATION_FAILED", "AGENT_UNAVAILABLE", "AGENT_TIMEOUT", "INVALID_AGENT_RESPONSE"})
    void providerFailureLeavesWaitingCaseUnchangedAndKeepsOneRequiredManualTask(String code) {
        createCase("WAITING");
        String facts = protectedFacts();
        doThrow(new HotelReplyAnalysisException(code, 503)).when(client).analyze(any());
        var first = service.analyze(evidenceId);
        var repeat = service.analyze(evidenceId);
        assertEquals(State.MANUAL_REQUIRED, first.status());
        assertNull(first.analysisId());
        assertEquals(code, first.errorCode());
        assertEquals(first.manualTaskId(), repeat.manualTaskId());
        assertEquals(0L, analysisCount());
        assertEquals(1L, reviewTasks());
        assertEquals(facts, protectedFacts());
        assertEquals(2L, jdbc.sql("SELECT count(*) FROM case_actions WHERE booking_id=:id AND action_type='HOTEL_ANALYSIS_MANUAL_REQUIRED'")
                .param("id", bookingId).query(Long.class).single());
    }

    @Test
    void invalidTypedReplyAndUnexpectedClientFailureCannotSkipManualReviewOrLeakTheirText() {
        createCase("OPEN");
        doAnswer(call -> {
            Analysis good = result(call.getArgument(0), "hotel-reply-extractor-0.1", false, List.of());
            return new Analysis("EV-OTHER", good.analysisStatus(), good.modelVersion(), good.claims(),
                    good.unresolvedStayDates(), true, good.warnings(), good.requestJson(), good.responseJson());
        }).when(client).analyze(any());
        assertEquals("INVALID_AGENT_RESPONSE", service.analyze(evidenceId).errorCode());
        doThrow(new IllegalStateException("TOP_SECRET_PRIVATE_BODY")).when(client).analyze(any());
        assertEquals("ANALYSIS_CLIENT_FAILED", service.analyze(evidenceId).errorCode());
        assertEquals(0L, analysisCount());
        assertFalse(jdbc.sql("SELECT jsonb_agg(details)::text FROM case_actions WHERE booking_id=:id")
                .param("id", bookingId).query(String.class).single().contains("TOP_SECRET_PRIVATE_BODY"));
    }

    @Test
    void noActiveCaseMeansExplicitManualHandoffWithoutCreatingOrReopeningACase() {
        long closedCase = createCase("CLOSED");
        String facts = protectedFacts();
        var success = service.analyze(evidenceId);
        assertEquals(State.ANALYZED, success.status());
        assertNotNull(success.analysisId());
        assertNull(success.manualTaskId());
        assertTrue(success.requiresHumanVerification());
        doThrow(new HotelReplyAnalysisException("AGENT_UNAVAILABLE")).when(client).analyze(any());
        var failure = service.analyze(evidenceId);
        assertEquals(State.MANUAL_REQUIRED, failure.status());
        assertNull(failure.manualTaskId());
        assertEquals(1L, analysisCount());
        assertEquals(0L, reviewTasks());
        assertEquals("CLOSED", jdbc.sql("SELECT status FROM cases WHERE id=:id").param("id", closedCase).query(String.class).single());
        assertEquals(facts, protectedFacts());
    }

    @Test
    void concurrentEqualResultsStoreOneAnalysisAndOnePendingRequiredTask() throws Exception {
        createCase("OPEN");
        try (var executor = Executors.newFixedThreadPool(4)) {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<HotelReplyAnalysisService.Outcome>>();
            for (int i = 0; i < 4; i++) futures.add(executor.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return service.analyze(evidenceId);
            }));
            start.countDown();
            Long id = futures.getFirst().get(15, TimeUnit.SECONDS).analysisId();
            for (var future : futures) assertEquals(id, future.get(15, TimeUnit.SECONDS).analysisId());
        }
        assertEquals(1L, analysisCount());
        assertEquals(1L, reviewTasks());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOURCE", "BOOKING"})
    void sourceOrBookingContextChangedDuringHttpRejectsTheStaleResult(String change) {
        createCase("OPEN");
        doAnswer(call -> {
            Analysis response = result(call.getArgument(0), "hotel-reply-extractor-0.1", false, List.of());
            if (change.equals("SOURCE")) jdbc.sql("UPDATE evidence_records SET raw_text='Changed hotel reply' WHERE id=:id").param("id", evidenceId).update();
            else jdbc.sql("UPDATE bookings SET check_out_date=check_out_date+1 WHERE id=:id").param("id", bookingId).update();
            return response;
        }).when(client).analyze(any());
        assertEquals("ANALYSIS_CONTEXT_CHANGED", assertThrows(HotelReplyAnalysisException.class,
                () -> service.analyze(evidenceId)).code());
        assertEquals(0L, analysisCount());
        assertEquals(0L, reviewTasks());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void databaseFailureRollsBackAnalysisAndManualWorkWithoutExposingSql(boolean providerFails) {
        createCase("OPEN");
        if (providerFails) doThrow(new HotelReplyAnalysisException("AGENT_UNAVAILABLE")).when(client).analyze(any());
        JdbcClient broken = mock(JdbcClient.class, AdditionalAnswers.delegatesTo(jdbc));
        String target = providerFails ? "INSERT INTO case_actions" : "INSERT INTO case_tasks";
        doThrow(new DataAccessResourceFailureException("TOP_SECRET_SQL_DETAIL"))
                .when(broken).sql(argThat((String sql) -> sql.contains(target)));
        var error = assertThrows(HotelReplyAnalysisException.class, () -> service(broken).analyze(evidenceId));
        assertEquals("ANALYSIS_STORAGE_FAILED", error.code());
        assertNull(error.getCause());
        assertFalse(error.toString().contains("TOP_SECRET"));
        assertEquals(0L, analysisCount());
        assertEquals(0L, reviewTasks());
    }

    @Test
    void callerTransactionIsRejectedBeforeAnyHttpOrStorageWork() {
        var transaction = new TransactionTemplate(manager);
        assertEquals("ANALYSIS_TRANSACTION_ACTIVE", assertThrows(HotelReplyAnalysisException.class,
                () -> transaction.execute(status -> service.analyze(evidenceId))).code());
        verifyNoInteractions(client);
        assertEquals(0L, analysisCount());
    }

    private HotelReplyAnalysisService service(JdbcClient database) {
        return new HotelReplyAnalysisService(client, new HotelReplyAnalysisStore(database, manager, CLOCK));
    }

    private static Analysis result(Input input, String model, boolean ambiguous, List<String> warnings) {
        List<Claim> claims = ambiguous ? List.of() : List.of(new Claim(input.stayDates().getFirst(), "UNAVAILABLE", input.originalMessage()));
        List<LocalDate> unresolved = ambiguous ? input.stayDates() : input.stayDates().subList(1, input.stayDates().size());
        String status = ambiguous ? "AMBIGUOUS" : "EXTRACTED";
        var wireClaims = claims.stream().map(claim -> Map.of("stayDate", claim.stayDate().toString(),
                "reportedProvisionStatus", claim.reportedProvisionStatus(), "supportingText", claim.supportingText())).toList();
        String response = JSON.writeValueAsString(Map.of("evidenceId", input.evidenceId(), "analysisStatus", status,
                "modelVersion", model, "claims", wireClaims,
                "unresolvedStayDates", unresolved.stream().map(LocalDate::toString).toList(),
                "requiresHumanVerification", true, "warnings", warnings));
        return new Analysis(input.evidenceId(), status, model, claims, unresolved, true, warnings,
                HotelReplyAnalysisClient.requestJson(input), response);
    }

    private long user(String subject, String role) {
        return jdbc.sql("INSERT INTO users(subject_key,display_name,role) VALUES (:subject,'Analysis test user',:role) RETURNING id")
                .param("subject", subject).param("role", role).query(Long.class).single();
    }

    private long createCase(String status) {
        return jdbc.sql("""
                WITH candidate AS (SELECT nextval(pg_get_serial_sequence('cases','id')) AS id)
                INSERT INTO cases(id,case_code,booking_id,status,priority,assignee_id,closed_at,close_reason)
                SELECT id,'C-'||lpad(id::text,greatest(3,length(id::text)),'0'),:booking,:status,'NORMAL',:assignee,
                    CASE WHEN :status='CLOSED' THEN now() END,CASE WHEN :status='CLOSED' THEN 'TEST_CLOSED' END
                FROM candidate RETURNING id
                """).param("booking", bookingId).param("status", status).param("assignee", counselorId).query(Long.class).single();
    }

    private long analysisCount() {
        return jdbc.sql("SELECT count(*) FROM agent_analyses WHERE evidence_id=:id").param("id", evidenceId).query(Long.class).single();
    }

    private long reviewTasks() {
        return jdbc.sql("SELECT count(*) FROM case_tasks WHERE booking_id=:id AND task_type='VERIFY_HOTEL'")
                .param("id", bookingId).query(Long.class).single();
    }

    private String storedRow(long id) {
        return jdbc.sql("SELECT to_jsonb(a)::text FROM agent_analyses a WHERE id=:id").param("id", id).query(String.class).single();
    }

    private String protectedFacts() {
        return jdbc.sql("""
                SELECT jsonb_build_object('booking',to_jsonb(b),
                    'nights',(SELECT jsonb_agg(to_jsonb(n) ORDER BY stay_date) FROM booking_nights n WHERE booking_id=b.id),
                    'evidence',(SELECT to_jsonb(e) FROM evidence_records e WHERE id=:evidence),
                    'cases',(SELECT jsonb_agg(to_jsonb(c) ORDER BY id) FROM cases c WHERE booking_id=b.id))::text
                FROM bookings b WHERE b.id=:booking
                """).param("evidence", evidenceId).param("booking", bookingId).query(String.class).single();
    }
}
