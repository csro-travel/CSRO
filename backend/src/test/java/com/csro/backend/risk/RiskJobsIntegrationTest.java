package com.csro.backend.risk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

import com.csro.backend.reconfirmation.BookingReconfirmationEvidenceService;
import com.csro.backend.reconfirmation.BookingReconfirmationException;
import com.csro.backend.reconfirmation.BookingReconfirmationResult;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.*;
import com.csro.backend.reconfirmation.ReconfirmationScope;
import com.csro.backend.reconfirmation.RiskEvidenceTestFactory;
import com.csro.backend.risk.RiskRecheckJobs.State;

/** Writes only to the exact dedicated database; external boundaries are controlled test doubles. */
@EnabledIfEnvironmentVariable(named = "RISK_JOBS_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RiskJobsIntegrationTest {
    private static final LocalDate TARGET = LocalDate.of(2026, 11, 10);
    private static final OffsetDateTime AS_OF = OffsetDateTime.parse("2026-11-09T09:00:00+09:00");
    private JdbcClient jdbc;
    private PlatformTransactionManager manager;
    private TransactionTemplate write;
    private MutableClock clock;
    private BookingReconfirmationEvidenceService evidence;
    private RiskRecheckJobs jobs;
    private Fixture fixture;

    @BeforeAll
    void bootstrapOnlyAfterTheEndpointGuard() {
        // No DataSource, Spring application or Flyway exists until this guard passes.
        var settings = RiskJobsTestDatabase.validate(System.getenv());
        var dataSource = new DriverManagerDataSource(settings.url(), settings.user(), settings.password());
        jdbc = JdbcClient.create(dataSource);
        assertEquals("yeohaengbom_risk_stage2_test", jdbc.sql("SELECT current_database()")
                .query(String.class).single());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration", "classpath:db/local")
                .cleanDisabled(true).load().migrate();
        manager = new DataSourceTransactionManager(dataSource);
        write = new TransactionTemplate(manager);
    }

    @BeforeEach
    void freshFixtureWithoutResettingExistingData() {
        clock = new MutableClock(Instant.parse("2026-11-22T00:00:00Z"));
        evidence = mock(BookingReconfirmationEvidenceService.class);
        jobs = worker(evidence);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String hotel = "JOBS-H-" + suffix;
        String booking = "JOBS-B-" + suffix;
        long hotelId = jdbc.sql("""
                INSERT INTO hotels (hotel_code, name, time_zone, data_source)
                VALUES (:hotel, :hotel, 'Asia/Seoul', 'SIMULATED') RETURNING id
                """).param("hotel", hotel).query(Long.class).single();
        long bookingId = jdbc.sql("""
                INSERT INTO bookings (booking_code, customer_id, hotel_id, supplier_code, supplier_booking_id,
                    supplier_status, check_in_date, check_out_date, adults, rooms, original_total_amount,
                    currency, created_at, updated_at)
                SELECT :code, customer_id, :hotel, 'MOCK_SUPPLIER', :supplier, 'CONFIRMED',
                    :target, :checkout, 2, 1, 450000, 'KRW', :asOf, :asOf
                FROM bookings WHERE booking_code='YB-001' RETURNING id
                """).param("code", booking).param("hotel", hotelId).param("supplier", "SUP-" + suffix)
                .param("target", TARGET).param("checkout", TARGET.plusDays(3)).param("asOf", AS_OF)
                .query(Long.class).single();
        jdbc.sql("""
                INSERT INTO booking_nights (booking_id, stay_date)
                SELECT :id, :target + n FROM generate_series(0,2) AS n
                """).param("id", bookingId).param("target", TARGET).update();
        fixture = new Fixture(bookingId, hotelId, booking, hotel, "SUP-" + suffix);
    }

    @Test
    void retryableFailureCommitsWaitingThenReusesTheSameRequestAndAssessment() {
        var job = enqueue();
        var failure = upstreamFailure(BookingReconfirmationException.Kind.UNAVAILABLE, 503);
        when(evidence.reconfirmAndApply(anyString(), anyString(), any(), anyLong(), any()))
                .thenAnswer(call -> {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    assertEquals("RUNNING", status(job.jobCode()));
                    assertEquals(1L, assessmentCount());
                    throw failure;
                }).thenAnswer(this::completeClaim);
        var first = jobs.execute(job.jobCode());
        assertEquals(State.WAITING, first.state());
        assertEquals(0, retryCount(job.jobCode()));
        assertEquals(State.WAITING, jobs.execute(job.jobCode()).state()); // Not due: no second HTTP.
        verify(evidence, times(1)).reconfirmAndApply(eq(fixture.booking()), eq(job.requestId()), any(), anyLong(), any());
        clock.advance(31);
        var second = jobs.execute(job.jobCode());
        assertEquals(State.COMPLETED, second.state());
        assertEquals(job.requestId(), second.requestId());
        assertEquals(1, retryCount(job.jobCode()));
        assertEquals(1L, assessmentCount());
        assertEquals(0L, fixtureRows("cases"));
        assertEquals(1, jdbc.sql("SELECT jsonb_array_length(result_payload -> 'history') FROM jobs WHERE job_code=:code")
                .param("code", job.jobCode()).query(Integer.class).single());
        jobs.execute(job.jobCode());
        verify(evidence, times(2)).reconfirmAndApply(eq(fixture.booking()), eq(job.requestId()), any(), anyLong(), any());
    }

    @Test
    void permanentFailureAndAttemptExhaustionRequireManualHandlingWithoutAFabricatedCase() {
        var job = enqueue();
        var failure = upstreamFailure(BookingReconfirmationException.Kind.NOT_FOUND, 404);
        when(evidence.reconfirmAndApply(anyString(), anyString(), any(), anyLong(), any()))
                .thenThrow(failure);
        assertEquals(State.FAILED, jobs.execute(job.jobCode()).state());
        assertTrue(jdbc.sql("SELECT (result_payload ->> 'manualRequired')::boolean FROM jobs WHERE job_code=:code")
                .param("code", job.jobCode()).query(Boolean.class).single());
        jobs.execute(job.jobCode());
        verify(evidence, times(1)).reconfirmAndApply(anyString(), anyString(), any(), anyLong(), any());
        assertEquals(0L, fixtureRows("cases"));
        assertEquals(0L, fixtureRows("evidence_records"));
    }

    @Test
    void repeated503StopsAfterThreeTotalAttemptsWithRetryCountsZeroOneTwo() {
        var job = enqueue();
        var failure = upstreamFailure(BookingReconfirmationException.Kind.UNAVAILABLE, 503);
        when(evidence.reconfirmAndApply(anyString(), anyString(), any(), anyLong(), any()))
                .thenThrow(failure);
        assertEquals(State.WAITING, jobs.execute(job.jobCode()).state());
        assertEquals(0, retryCount(job.jobCode()));
        clock.advance(31);
        assertEquals(State.WAITING, jobs.execute(job.jobCode()).state());
        assertEquals(1, retryCount(job.jobCode()));
        clock.advance(31);
        var exhausted = jobs.execute(job.jobCode());
        assertEquals(State.FAILED, exhausted.state());
        assertEquals("ATTEMPTS_EXHAUSTED", exhausted.errorCode());
        assertEquals(2, retryCount(job.jobCode()));
        clock.advance(300);
        assertEquals(State.FAILED, jobs.execute(job.jobCode()).state());
        verify(evidence, times(3)).reconfirmAndApply(eq(fixture.booking()), eq(job.requestId()), any(), anyLong(), any());
    }

    @Test
    void hotelPendingUsesTheSameRequestOnPollAndDoesNotInventEvidenceOrACase() {
        var job = enqueue();
        var validated = validatedResult(job.requestId());
        var pending = new BookingReconfirmationResult(validated.bookingCode(), validated.supplier(),
                new HotelPending(job.requestId(), "SYNTHETIC-PENDING", "PENDING", AS_OF),
                validated.supplierEvidence(), validated.hotelEvidence());
        when(evidence.reconfirmAndApply(anyString(), anyString(), any(), anyLong(), any()))
                .thenReturn(new BookingReconfirmationEvidenceService.Outcome(pending, null, null, null, false))
                .thenAnswer(this::completeClaim);
        var waiting = jobs.execute(job.jobCode());
        assertEquals(State.WAITING, waiting.state());
        assertEquals("HOTEL_PENDING", waiting.errorCode());
        assertEquals(0L, fixtureRows("evidence_records"));
        assertEquals(0L, fixtureRows("cases"));
        clock.advance(31);
        assertEquals(State.COMPLETED, jobs.execute(job.jobCode()).state());
        verify(evidence, times(2)).reconfirmAndApply(eq(fixture.booking()), eq(job.requestId()), any(), anyLong(), any());
        assertEquals(1L, assessmentCount());
    }

    @Test
    void expiredProcessingDeadlineStopsBeforeCallingUpstream() {
        var job = enqueue();
        clock.advance(901);
        var expired = jobs.execute(job.jobCode());
        assertEquals(State.FAILED, expired.state());
        assertEquals("DEADLINE_EXCEEDED", expired.errorCode());
        assertEquals(0, retryCount(job.jobCode()));
        verifyNoInteractions(evidence);
        assertEquals(0L, fixtureRows("cases"));
    }

    @Test
    void foreignActiveJobRollsBackTheNewAssessmentRatherThanStealingWork() {
        jdbc.sql("""
                INSERT INTO jobs (job_code, booking_id, kind, trigger_type, parameters)
                VALUES (:code, :booking, 'RECHECK', 'COUNSELOR', '{"owner":"another-workflow"}')
                """).param("code", "FOREIGN-" + fixture.bookingId()).param("booking", fixture.bookingId()).update();
        var ml = mock(RiskAssessmentService.class);
        when(ml.prepare(fixture.hotel(), TARGET)).thenReturn(projection(fixture.booking()));
        when(ml.predict(any())).thenReturn(new RiskAssessmentService.Prediction(new java.math.BigDecimal(".16"), "test-model"));
        var service = new RiskRecheckService(ml, new RiskAssessmentStore(jdbc, tools.jackson.databind.json.JsonMapper.builder().build()), jdbc, manager, jobs);
        assertThrows(IllegalStateException.class,
                () -> service.assessAndReconfirm(fixture.hotel(), TARGET, RiskSelectionPolicy.RECALL_50_F1));
        assertEquals(0L, assessmentCount());
        assertEquals(1L, fixtureRows("jobs"));
        verifyNoInteractions(evidence);
    }

    @Test
    void equalCohortSizeWithDifferentBookingIdentityStopsBeforeMlOrAnyRiskWrite() {
        var ml = mock(RiskAssessmentService.class);
        var service = new RiskRecheckService(ml, new RiskAssessmentStore(jdbc, tools.jackson.databind.json.JsonMapper.builder().build()), jdbc, manager, jobs);
        when(ml.prepare(fixture.hotel(), TARGET)).thenReturn(projection("DIFFERENT-BOOKING"));
        assertThrows(IllegalArgumentException.class, () -> service.assessAndReconfirm(fixture.hotel(), TARGET, RiskSelectionPolicy.RECALL_50_F1));
        verify(ml, times(0)).predict(any());
        verifyNoInteractions(evidence);
        assertEquals(0L, assessmentCount());
        assertEquals(0L, fixtureRows("jobs"));
    }

    @Test
    void bookingChangeAfterEnqueueFailsBeforeAnyUpstreamCall() {
        var job = enqueue();
        var boundary = RiskEvidenceTestFactory.frozenBoundary(jdbc, manager);
        var realGuardWorker = worker(boundary.service());
        jdbc.sql("UPDATE bookings SET adults=3 WHERE id=:id").param("id", fixture.bookingId()).update();
        var result = realGuardWorker.execute(job.jobCode());
        assertEquals(State.FAILED, result.state());
        assertEquals("REQUEST_MISMATCH", result.errorCode());
        boundary.assertNoHttp().run();
        assertEquals(0L, fixtureRows("evidence_records"));
        assertEquals(0L, fixtureRows("cases"));
    }

    @Test
    void finalAttemptCommitsJobWithEvidenceBeforeWorkerInterruption() {
        var job = enqueue();
        var realEvidence = RiskEvidenceTestFactory.validatedReply(jdbc, manager, validatedResult(job.requestId()));
        var boundary = mock(BookingReconfirmationEvidenceService.class);
        var failure = upstreamFailure(BookingReconfirmationException.Kind.UNAVAILABLE, 503);
        when(boundary.reconfirmAndApply(anyString(), anyString(), any(), anyLong(), any()))
                .thenThrow(failure).thenThrow(failure).thenAnswer(call -> {
                    realEvidence.reconfirmAndApply(call.getArgument(0), call.getArgument(1),
                            call.getArgument(2), call.getArgument(3), call.getArgument(4));
                    throw new IllegalStateException("Worker interrupted immediately after evidence commit");
                });
        var worker = worker(boundary);
        for (int attempt = 0; attempt < 2; attempt++) {
            assertEquals(State.WAITING, worker.execute(job.jobCode()).state());
            clock.advance(31);
        }
        assertThrows(IllegalStateException.class, () -> worker.execute(job.jobCode()));
        clock.advance(901);
        var recovered = worker.execute(job.jobCode());
        assertEquals(State.COMPLETED, recovered.state());
        assertEquals(2, retryCount(job.jobCode()));
        assertEquals(recovered.caseCode(), jdbc.sql("""
                SELECT c.case_code FROM jobs j JOIN cases c ON c.id=j.case_id
                JOIN evidence_records s ON s.id=(j.result_payload #>> '{last,supplierEvidenceId}')::bigint
                JOIN evidence_records h ON h.id=(j.result_payload #>> '{last,hotelEvidenceId}')::bigint
                WHERE s.job_id=j.id AND h.job_id=j.id AND j.job_code=:code
                """).param("code", job.jobCode()).query(String.class).single());
        assertEquals(2L, fixtureRows("evidence_records"));
        assertEquals(1L, fixtureRows("cases"));
        assertEquals(1L, fixtureRows("case_tasks"));
        verify(boundary, times(3)).reconfirmAndApply(anyString(), eq(job.requestId()), any(), anyLong(), any());
        assertEquals("CONFIRMED", jdbc.sql("SELECT supplier_status FROM bookings WHERE id=:id")
                .param("id", fixture.bookingId()).query(String.class).single());
    }

    @Test
    void concurrentWorkersDoNotRepeatHttpDuringAnActiveLease() throws Exception {
        var job = enqueue();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(evidence.reconfirmAndApply(anyString(), anyString(), any(), anyLong(), any())).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return completeClaim(call);
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> jobs.execute(job.jobCode()));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertEquals(State.RUNNING, pool.submit(() -> jobs.execute(job.jobCode())).get(10, TimeUnit.SECONDS).state());
            } finally {
                release.countDown();
            }
            assertEquals(State.COMPLETED, first.get(10, TimeUnit.SECONDS).state());
        }
        verify(evidence, times(1)).reconfirmAndApply(anyString(), anyString(), any(), anyLong(), any());
    }

    @Test
    void expiredLeaseFencesAnOldWorkerFromOverwritingTheNewClaim() throws Exception {
        var job = enqueue();
        var firstEntered = new CountDownLatch(1);
        var releaseOldWorker = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var realEvidence = RiskEvidenceTestFactory.validatedReply(jdbc, manager, validatedResult(job.requestId()));
        when(evidence.reconfirmAndApply(anyString(), anyString(), any(), anyLong(), any())).thenAnswer(call -> {
            if (calls.getAndIncrement() == 0) {
                firstEntered.countDown();
                assertTrue(releaseOldWorker.await(10, TimeUnit.SECONDS));
                return realEvidence.reconfirmAndApply(call.getArgument(0), call.getArgument(1),
                        call.getArgument(2), call.getArgument(3), call.getArgument(4));
            }
            throw upstreamFailure(BookingReconfirmationException.Kind.NOT_FOUND, 404);
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var old = pool.submit(() -> jobs.execute(job.jobCode()));
            try {
                assertTrue(firstEntered.await(10, TimeUnit.SECONDS));
                clock.advance(61);
                assertEquals(State.FAILED, pool.submit(() -> jobs.execute(job.jobCode())).get(10, TimeUnit.SECONDS).state());
            } finally {
                releaseOldWorker.countDown();
            }
            assertEquals(State.FAILED, old.get(10, TimeUnit.SECONDS).state());
        }
        assertEquals("FAILED", status(job.jobCode()));
        assertEquals(0L, fixtureRows("evidence_records"));
        assertEquals(0L, fixtureRows("cases"));
        assertEquals(0L, fixtureRows("case_tasks"));
        assertEquals(1, retryCount(job.jobCode()));
        assertEquals(1L, assessmentCount());
        verify(evidence, times(2)).reconfirmAndApply(eq(fixture.booking()), eq(job.requestId()), any(), anyLong(), any());
    }

    private RiskRecheckJobs worker(BookingReconfirmationEvidenceService boundary) {
        return new RiskRecheckJobs(boundary, jdbc, manager, clock, new RiskRecheckJobs.RetryPolicy(3, 30, 60, 900));
    }

    private RiskRecheckJobs.JobRef enqueue() {
        return write.execute(status -> {
            jdbc.sql("SELECT id FROM bookings WHERE id=:id FOR UPDATE").param("id", fixture.bookingId())
                    .query(Long.class).single();
            long id = jdbc.sql("""
                    INSERT INTO risk_assessments (hotel_id, target_check_in_date, as_of, risk_score,
                        model_version, feature_set_version, feature_snapshot)
                    VALUES (:hotel, :target, :asOf, .16, :model, 'risk-features-0.2', '{}') RETURNING id
                    """).param("hotel", fixture.hotelId()).param("target", TARGET).param("asOf", AS_OF)
                    .param("model", "test-model").query(Long.class).single();
            var scope = new RiskRecheckJobs.Scope(fixture.bookingId(), fixture.booking(), fixture.hotel(),
                    "MOCK_SUPPLIER", fixture.supplier(), TARGET, TARGET.plusDays(3), 2, 1);
            return jobs.enqueueSelected(id, request(), RiskSelectionPolicy.RECALL_50_F1, .16, "test-model", List.of(scope)).getFirst();
        });
    }

    private RiskRequest request() {
        return new RiskRequest(fixture.hotel(), TARGET, AS_OF, "risk-features-0.2",
                new RiskRequest.Features(.05, 1, 0, 0, 0, false));
    }

    private RiskObservations.Prepared projection(String booking) {
        var input = tools.jackson.databind.json.JsonMapper.builder().build().convertValue(request(), RiskObservations.Input.class);
        return new RiskObservations.Prepared(input, List.of(booking));
    }

    private BookingReconfirmationEvidenceService.Outcome completeClaim(org.mockito.invocation.InvocationOnMock call) {
        var outcome = new BookingReconfirmationEvidenceService.Outcome(validatedResult(call.getArgument(1)), null, null, null, false);
        java.util.function.Consumer<BookingReconfirmationEvidenceService.Outcome> complete = call.getArgument(4);
        write.executeWithoutResult(status -> complete.accept(outcome));
        return outcome;
    }

    private BookingReconfirmationResult validatedResult(String requestId) {
        var supplier = new SupplierBooking(fixture.supplier(), fixture.booking(), fixture.hotel(), "CONFIRMED",
                new Stay(TARGET, TARGET.plusDays(3), 2, 1), AS_OF);
        var nights = List.of(new Night(TARGET, "UNAVAILABLE", "Synthetic first-night failure"),
                new Night(TARGET.plusDays(1), "UNKNOWN", "Synthetic pending confirmation"),
                new Night(TARGET.plusDays(2), "UNKNOWN", "Synthetic pending confirmation"));
        var reply = new HotelReply(requestId, "SYNTHETIC-REPLY", "RESPONDED", fixture.supplier(),
                fixture.hotel(), nights, AS_OF, AS_OF);
        return new BookingReconfirmationResult(fixture.booking(), supplier, reply,
                new EvidenceBody("{\"synthetic\":\"supplier\"}", AS_OF, true, null),
                new EvidenceBody("{\"synthetic\":\"hotel\"}", AS_OF, true, "{\"requestId\":\"" + requestId + "\"}"));
    }

    private static BookingReconfirmationException upstreamFailure(BookingReconfirmationException.Kind kind, int status) {
        var failure = mock(BookingReconfirmationException.class);
        when(failure.step()).thenReturn(BookingReconfirmationException.Step.API_027);
        when(failure.kind()).thenReturn(kind);
        when(failure.httpStatus()).thenReturn(status);
        return failure;
    }

    private String status(String code) {
        return jdbc.sql("SELECT status FROM jobs WHERE job_code=:code").param("code", code).query(String.class).single();
    }
    private int retryCount(String code) {
        return jdbc.sql("SELECT attempt_count FROM jobs WHERE job_code=:code").param("code", code).query(Integer.class).single();
    }
    private long assessmentCount() {
        return jdbc.sql("SELECT count(*) FROM risk_assessments WHERE hotel_id=:id")
                .param("id", fixture.hotelId()).query(Long.class).single();
    }
    private long fixtureRows(String table) {
        if (!List.of("jobs", "cases", "case_tasks", "evidence_records").contains(table)) {
            throw new IllegalArgumentException("Unexpected test table");
        }
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE booking_id=:id")
                .param("id", fixture.bookingId()).query(Long.class).single();
    }
    private record Fixture(long bookingId, long hotelId, String booking, String hotel, String supplier) {}
    private static final class MutableClock extends Clock {
        private volatile Instant value;
        MutableClock(Instant value) { this.value = value; }
        void advance(long seconds) { value = value.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("Test clock is UTC");
            return this;
        }
        @Override public Instant instant() { return value; }
    }
}
