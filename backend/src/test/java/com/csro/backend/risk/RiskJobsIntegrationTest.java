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
        when(evidence.reconfirmAndApply(anyString(), anyString(), any(), anyLong()))
                .thenAnswer(call -> {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    assertEquals("RUNNING", status(job.jobCode()));
                    assertEquals(1L, assessmentCount());
                    throw failure;
                }).thenReturn(completed());
        var first = jobs.execute(job.jobCode());
        assertEquals(State.WAITING, first.state());
        assertEquals(0, retryCount(job.jobCode()));
        assertEquals(State.WAITING, jobs.execute(job.jobCode()).state()); // Not due: no second HTTP.
        verify(evidence, times(1)).reconfirmAndApply(eq(fixture.booking()), eq(job.requestId()), any(), anyLong());
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
        verify(evidence, times(2)).reconfirmAndApply(eq(fixture.booking()), eq(job.requestId()), any(), anyLong());
    }

    @Test
    void permanentFailureAndAttemptExhaustionRequireManualHandlingWithoutAFabricatedCase() {
        var job = enqueue();
        var failure = upstreamFailure(BookingReconfirmationException.Kind.NOT_FOUND, 404);
        when(evidence.reconfirmAndApply(anyString(), anyString(), any(), anyLong()))
                .thenThrow(failure);
        assertEquals(State.FAILED, jobs.execute(job.jobCode()).state());
        assertTrue(jdbc.sql("SELECT (result_payload ->> 'manualRequired')::boolean FROM jobs WHERE job_code=:code")
                .param("code", job.jobCode()).query(Boolean.class).single());
        jobs.execute(job.jobCode());
        verify(evidence, times(1)).reconfirmAndApply(anyString(), anyString(), any(), anyLong());
        assertEquals(0L, fixtureRows("cases"));
        assertEquals(0L, fixtureRows("evidence_records"));
    }

    @Test
    void repeated503StopsAfterThreeTotalAttemptsWithRetryCountsZeroOneTwo() {
        var job = enqueue();
        var failure = upstreamFailure(BookingReconfirmationException.Kind.UNAVAILABLE, 503);
        when(evidence.reconfirmAndApply(anyString(), anyString(), any(), anyLong()))
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
        verify(evidence, times(3)).reconfirmAndApply(eq(fixture.booking()), eq(job.requestId()), any(), anyLong());
    }

    @Test
    void hotelPendingUsesTheSameRequestOnPollAndDoesNotInventEvidenceOrACase() {
        var job = enqueue();
        var validated = validatedResult(job.requestId());
        var pending = new BookingReconfirmationResult(validated.bookingCode(), validated.supplier(),
                new HotelPending(job.requestId(), "SYNTHETIC-PENDING", "PENDING", AS_OF),
                validated.supplierEvidence(), validated.hotelEvidence());
        when(evidence.reconfirmAndApply(anyString(), anyString(), any(), anyLong()))
                .thenReturn(new BookingReconfirmationEvidenceService.Outcome(pending, null, null, null, false))
                .thenReturn(completed());
        var waiting = jobs.execute(job.jobCode());
        assertEquals(State.WAITING, waiting.state());
        assertEquals("HOTEL_PENDING", waiting.errorCode());
        assertEquals(0L, fixtureRows("evidence_records"));
        assertEquals(0L, fixtureRows("cases"));
        clock.advance(31);
        assertEquals(State.COMPLETED, jobs.execute(job.jobCode()).state());
        verify(evidence, times(2)).reconfirmAndApply(eq(fixture.booking()), eq(job.requestId()), any(), anyLong());
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
        var ml = mock(RiskMlClient.class);
        when(ml.assess(any())).thenReturn(assessment());
        var service = new RiskRecheckService(ml, evidence, jdbc, manager, jobs);
        assertThrows(IllegalStateException.class,
                () -> service.assessAndReconfirm(request(), RiskSelectionPolicy.RECALL_50_F1));
        assertEquals(0L, assessmentCount());
        assertEquals(1L, fixtureRows("jobs"));
        verifyNoInteractions(evidence);
    }

    @Test
    void equalCohortSizeWithDifferentBookingIdentityStopsBeforeMlOrAnyRiskWrite() {
        var ml = mock(RiskMlClient.class);
        var service = new RiskRecheckService(ml, evidence, jdbc, manager, jobs);
        assertThrows(IllegalArgumentException.class, () -> service.assessAndReconfirm(
                request(), RiskSelectionPolicy.RECALL_50_F1, List.of("DIFFERENT-BOOKING")));
        verifyNoInteractions(ml, evidence);
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
    void committedEvidenceCanBeReplayedAfterWorkerFailureWithoutDuplicatingTheCaseOrTask() {
        var job = enqueue();
        var validated = validatedResult(job.requestId());
        var realEvidence = RiskEvidenceTestFactory.validatedReply(jdbc, manager, validated);
        var crashBoundary = mock(BookingReconfirmationEvidenceService.class);
        when(crashBoundary.reconfirmAndApply(anyString(), anyString(), any(), anyLong())).thenAnswer(call -> {
            realEvidence.reconfirmAndApply(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3));
            throw new IllegalStateException("Simulated worker interruption after evidence commit");
        });
        assertThrows(IllegalStateException.class, () -> worker(crashBoundary).execute(job.jobCode()));
        assertEquals("RUNNING", status(job.jobCode()));
        assertEquals(2L, fixtureRows("evidence_records"));
        assertEquals(1L, fixtureRows("cases"));
        assertEquals(1L, fixtureRows("case_tasks"));
        assertEquals(2L, jdbc.sql("""
                SELECT count(*) FROM evidence_records e JOIN jobs j ON j.id=e.job_id
                WHERE j.job_code=:code AND e.booking_id=j.booking_id
                """).param("code", job.jobCode()).query(Long.class).single());
        clock.advance(61);
        assertEquals(State.COMPLETED, worker(realEvidence).execute(job.jobCode()).state());
        assertEquals(1, retryCount(job.jobCode()));
        assertEquals(2L, fixtureRows("evidence_records"));
        assertEquals(1L, fixtureRows("cases"));
        assertEquals(1L, fixtureRows("case_tasks"));
        assertEquals("CONFIRMED", jdbc.sql("SELECT supplier_status FROM bookings WHERE id=:id")
                .param("id", fixture.bookingId()).query(String.class).single());
    }

    @Test
    void concurrentWorkersDoNotRepeatHttpDuringAnActiveLease() throws Exception {
        var job = enqueue();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(evidence.reconfirmAndApply(anyString(), anyString(), any(), anyLong())).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return completed();
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
        verify(evidence, times(1)).reconfirmAndApply(anyString(), anyString(), any(), anyLong());
    }

    @Test
    void expiredLeaseFencesAnOldWorkerFromOverwritingTheNewClaim() throws Exception {
        var job = enqueue();
        var firstEntered = new CountDownLatch(1);
        var releaseOldWorker = new CountDownLatch(1);
        var calls = new AtomicInteger();
        when(evidence.reconfirmAndApply(anyString(), anyString(), any(), anyLong())).thenAnswer(call -> {
            if (calls.getAndIncrement() == 0) {
                firstEntered.countDown();
                assertTrue(releaseOldWorker.await(10, TimeUnit.SECONDS));
                throw upstreamFailure(BookingReconfirmationException.Kind.UNAVAILABLE, 503);
            }
            return completed();
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var old = pool.submit(() -> jobs.execute(job.jobCode()));
            try {
                assertTrue(firstEntered.await(10, TimeUnit.SECONDS));
                clock.advance(61);
                assertEquals(State.COMPLETED, pool.submit(() -> jobs.execute(job.jobCode())).get(10, TimeUnit.SECONDS).state());
            } finally {
                releaseOldWorker.countDown();
            }
            assertEquals(State.COMPLETED, old.get(10, TimeUnit.SECONDS).state());
        }
        assertEquals("COMPLETED", status(job.jobCode()));
        assertEquals(1, retryCount(job.jobCode()));
        assertEquals(1L, assessmentCount());
        verify(evidence, times(2)).reconfirmAndApply(eq(fixture.booking()), eq(job.requestId()), any(), anyLong());
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
                    .param("model", RiskMlClient.MODEL_VERSION).query(Long.class).single();
            var scope = new RiskRecheckJobs.Scope(fixture.bookingId(), fixture.booking(), fixture.hotel(),
                    "MOCK_SUPPLIER", fixture.supplier(), TARGET, TARGET.plusDays(3), 2, 1);
            return jobs.enqueueSelected(id, request(), RiskSelectionPolicy.RECALL_50_F1, .16, List.of(scope)).getFirst();
        });
    }

    private RiskRequest request() {
        return new RiskRequest(fixture.hotel(), TARGET, AS_OF, "risk-features-0.2",
                new RiskRequest.Features(.05, 1, 0, 0, 0, false));
    }

    private RiskMlClient.Assessment assessment() {
        return new RiskMlClient.Assessment(fixture.hotel(), TARGET, AS_OF, .16,
                RiskMlClient.MODEL_VERSION, "risk-features-0.2");
    }

    private BookingReconfirmationEvidenceService.Outcome completed() {
        return new BookingReconfirmationEvidenceService.Outcome(validatedResult("unused"), null, null, null, false);
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
