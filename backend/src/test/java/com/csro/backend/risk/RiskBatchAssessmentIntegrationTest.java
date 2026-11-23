package com.csro.backend.risk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
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

import com.csro.backend.reconfirmation.BookingReconfirmationEvidenceService;
import com.csro.backend.reconfirmation.BookingReconfirmationResult;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.HotelPending;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.HotelReply;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.Night;

/** Batch replay/concurrency checks; all writes are confined to an explicit dedicated PostgreSQL. */
@EnabledIfEnvironmentVariable(named = "RISK_BATCH_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RiskBatchAssessmentIntegrationTest {
    private static final LocalDate TARGET = LocalDate.of(2026, 11, 10);
    private static final OffsetDateTime AS_OF = OffsetDateTime.parse("2026-11-09T09:00:00+09:00");
    private JdbcClient jdbc;
    private PlatformTransactionManager manager;
    private RiskMlClient ml;
    private BookingReconfirmationEvidenceService evidence;
    private RiskRecheckJobs jobs;
    private RiskRecheckService service;
    private MutableClock clock;
    private String hotel;
    private String booking;
    private long hotelId;
    private long bookingId;

    @BeforeAll
    void bootstrapOnlyAfterTheGuard() {
        var settings = RiskBatchTestDatabase.validate(System.getenv());
        var datasource = new DriverManagerDataSource(settings.url(), settings.user(), settings.password());
        jdbc = JdbcClient.create(datasource);
        assertEquals("yeohaengbom_risk_batch_test", jdbc.sql("SELECT current_database()")
                .query(String.class).single());
        Flyway.configure().dataSource(datasource).locations("classpath:db/migration", "classpath:db/local")
                .cleanDisabled(true).load().migrate();
        manager = new DataSourceTransactionManager(datasource);
    }

    @BeforeEach
    void prepareAFreshCohortWithoutDeletingExistingRows() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        hotel = "BATCH-H-" + suffix;
        booking = "BATCH-B-" + suffix;
        hotelId = jdbc.sql("INSERT INTO hotels (hotel_code,name,data_source) "
                + "VALUES (:code,:code,'SIMULATED') RETURNING id")
                .param("code", hotel).query(Long.class).single();
        bookingId = jdbc.sql("""
                INSERT INTO bookings (booking_code,customer_id,hotel_id,supplier_code,supplier_booking_id,
                    supplier_status,check_in_date,check_out_date,adults,rooms,original_total_amount,currency,
                    created_at,updated_at)
                SELECT :code,customer_id,:hotel,'MOCK_SUPPLIER',:supplier,'CONFIRMED',
                    :target,:checkout,2,1,450000,'KRW',:cutoff,:cutoff
                FROM bookings WHERE booking_code='YB-001' RETURNING id
                """).param("code", booking).param("hotel", hotelId).param("supplier", "SUP-" + suffix)
                .param("target", TARGET).param("checkout", TARGET.plusDays(3)).param("cutoff", AS_OF)
                .query(Long.class).single();
        jdbc.sql("INSERT INTO booking_nights (booking_id,stay_date) "
                + "SELECT :id,:day+n FROM generate_series(0,2) n")
                .param("id", bookingId).param("day", TARGET).update();
        ml = mock(RiskMlClient.class);
        evidence = mock(BookingReconfirmationEvidenceService.class);
        clock = new MutableClock(Instant.parse("2026-11-23T00:00:00Z"));
        jobs = new RiskRecheckJobs(evidence, jdbc, manager, clock,
                new RiskRecheckJobs.RetryPolicy(3, 30, 60, 900));
        service = new RiskRecheckService(ml, evidence, jdbc, manager, jobs);
        stubScore(.16);
        when(evidence.reconfirmAndApply(anyString(), anyString(), any(), anyLong()))
                .thenAnswer(call -> {
                    assertOutsideTransaction();
                    return completed(call.getArgument(1));
                });
    }

    @Test
    void repeatedBatchReusesAssessmentAndTerminalJobWhileRefreshingRawInference() {
        var first = batch(request(), RiskSelectionPolicy.RECALL_50_F1);
        var second = batch(request(), RiskSelectionPolicy.RECALL_50_F1);
        assertFalse(first.reusedAssessment());
        assertTrue(second.reusedAssessment());
        assertEquals(first.outcome().assessmentId(), second.outcome().assessmentId());
        assertEquals(first.outcome().rechecks().getFirst().requestId(),
                second.outcome().rechecks().getFirst().requestId());
        assertEquals(RiskRecheckService.RecheckStatus.COMPLETED,
                second.outcome().rechecks().getFirst().status());
        assertEquals(1L, assessments());
        assertEquals(1L, jobCount());
        verify(ml, times(2)).assess(any()); // Existing rounded DB score is not used as raw model output.
        verify(evidence, times(1)).reconfirmAndApply(eq(booking), anyString(), any(), anyLong());
    }

    @Test
    void waitingBatchReusesTheSameJobAndHonorsItsRetryTime() {
        when(evidence.reconfirmAndApply(anyString(), anyString(), any(), anyLong()))
                .thenAnswer(call -> new BookingReconfirmationEvidenceService.Outcome(
                        new BookingReconfirmationResult(booking, null,
                                new HotelPending(call.getArgument(1), "SYNTHETIC-PENDING", "PENDING", AS_OF),
                                null, null), null, null, null, false))
                .thenAnswer(call -> completed(call.getArgument(1)));
        var first = batch(request(), RiskSelectionPolicy.RECALL_50_F1);
        var early = batch(request(), RiskSelectionPolicy.RECALL_50_F1);
        assertTrue(early.reusedAssessment());
        assertEquals(RiskRecheckService.RecheckStatus.PENDING, early.outcome().rechecks().getFirst().status());
        verify(evidence, times(1)).reconfirmAndApply(anyString(), anyString(), any(), anyLong());
        clock.advance(31);
        var retried = batch(request(), RiskSelectionPolicy.RECALL_50_F1);
        assertEquals(first.outcome().assessmentId(), retried.outcome().assessmentId());
        assertEquals(first.outcome().rechecks().getFirst().requestId(),
                retried.outcome().rechecks().getFirst().requestId());
        assertEquals(RiskRecheckService.RecheckStatus.COMPLETED, retried.outcome().rechecks().getFirst().status());
        assertEquals(1L, assessments());
        assertEquals(1L, jobCount());
        assertEquals(1, jdbc.sql("SELECT attempt_count FROM jobs WHERE booking_id=:id")
                .param("id", bookingId).query(Integer.class).single());
    }

    @Test
    void equalRoundedScoresStillUseFreshRawThresholdSelection() {
        double threshold = RiskSelectionPolicy.F1_BASELINE.threshold();
        doAnswer(call -> assessment(call.getArgument(0), threshold - 1e-7))
                .doAnswer(call -> assessment(call.getArgument(0), threshold + 1e-7))
                .when(ml).assess(any());
        var below = batch(request(), RiskSelectionPolicy.F1_BASELINE);
        var above = batch(request(), RiskSelectionPolicy.F1_BASELINE);
        assertFalse(below.outcome().selected());
        assertTrue(above.outcome().selected());
        assertTrue(above.reusedAssessment());
        assertEquals(below.outcome().assessmentId(), above.outcome().assessmentId());
        assertEquals(0, new BigDecimal("0.2234").compareTo(jdbc.sql(
                "SELECT risk_score FROM risk_assessments WHERE hotel_id=:id")
                .param("id", hotelId).query(BigDecimal.class).single()));
        assertEquals(1L, assessments());
        assertEquals(1L, jobCount());
    }

    @Test
    void manualCallsKeepNewRowsAndBatchCanAdoptAnIdenticalExistingAssessment() {
        stubScore(.02);
        var first = service.assessAndReconfirm(request(), RiskSelectionPolicy.F1_BASELINE);
        var second = service.assessAndReconfirm(request(), RiskSelectionPolicy.F1_BASELINE);
        assertNotEquals(first.assessmentId(), second.assessmentId());
        var adopted = batch(request(), RiskSelectionPolicy.F1_BASELINE);
        assertTrue(adopted.reusedAssessment());
        assertEquals(first.assessmentId(), adopted.outcome().assessmentId());
        assertEquals(2L, assessments());
        assertEquals(0L, jobCount());
        verifyNoInteractions(evidence);
    }

    @Test
    void changedFeaturesAreDistinctEvenWhenRoundedScoresMatch() {
        var first = batch(request(), RiskSelectionPolicy.RECALL_50_F1);
        var changed = new RiskRequest(hotel, TARGET, AS_OF, RiskRequest.FEATURE_SET_VERSION,
                new RiskRequest.Features(.06, 1, 0, 0, 0, false));
        var second = batch(changed, RiskSelectionPolicy.RECALL_50_F1);
        assertFalse(second.reusedAssessment());
        assertNotEquals(first.outcome().assessmentId(), second.outcome().assessmentId());
        assertEquals(2L, assessments());
        assertEquals(2L, jobCount());
    }

    @Test
    void concurrentCyclesCreateOneAssessmentAndOneClaimedOutboundJob() throws Exception {
        var bothAtMl = new CountDownLatch(2);
        var firstAtEvidence = new CountDownLatch(1);
        var releaseEvidence = new CountDownLatch(1);
        var anotherCycleReturned = new CountDownLatch(1);
        doAnswer(call -> {
            assertOutsideTransaction();
            bothAtMl.countDown();
            assertTrue(bothAtMl.await(10, TimeUnit.SECONDS));
            return assessment(call.getArgument(0), .16);
        }).when(ml).assess(any());
        when(evidence.reconfirmAndApply(anyString(), anyString(), any(), anyLong())).thenAnswer(call -> {
            assertOutsideTransaction();
            firstAtEvidence.countDown();
            assertTrue(releaseEvidence.await(10, TimeUnit.SECONDS));
            return completed(call.getArgument(1));
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var action = (java.util.concurrent.Callable<RiskRecheckService.BatchOutcome>) () -> {
                var result = batch(request(), RiskSelectionPolicy.RECALL_50_F1);
                if (result.outcome().rechecks().getFirst().status() == RiskRecheckService.RecheckStatus.PENDING)
                    anotherCycleReturned.countDown();
                return result;
            };
            var left = executor.submit(action);
            var right = executor.submit(action);
            try {
                assertTrue(firstAtEvidence.await(10, TimeUnit.SECONDS));
                assertTrue(anotherCycleReturned.await(10, TimeUnit.SECONDS));
            } finally {
                releaseEvidence.countDown();
            }
            var first = left.get(10, TimeUnit.SECONDS);
            var second = right.get(10, TimeUnit.SECONDS);
            assertEquals(first.outcome().assessmentId(), second.outcome().assessmentId());
            assertNotEquals(first.reusedAssessment(), second.reusedAssessment());
        }
        assertEquals(1L, assessments());
        assertEquals(1L, jobCount());
        verify(evidence, times(1)).reconfirmAndApply(anyString(), anyString(), any(), anyLong());
    }

    private RiskRecheckService.BatchOutcome batch(RiskRequest request, RiskSelectionPolicy policy) {
        return service.assessBatchAndReconfirm(request, policy, List.of(booking));
    }

    private void stubScore(double score) {
        doAnswer(call -> {
            assertOutsideTransaction();
            return assessment(call.getArgument(0), score);
        }).when(ml).assess(any());
    }

    private RiskMlClient.Assessment assessment(RiskRequest request, double score) {
        return new RiskMlClient.Assessment(request.hotelId(), request.targetCheckInDate(), request.asOf(),
                score, RiskMlClient.MODEL_VERSION, RiskRequest.FEATURE_SET_VERSION);
    }

    private RiskRequest request() {
        return new RiskRequest(hotel, TARGET, AS_OF, RiskRequest.FEATURE_SET_VERSION,
                new RiskRequest.Features(.05, 1, 0, 0, 0, false));
    }

    private BookingReconfirmationEvidenceService.Outcome completed(String requestId) {
        var reply = new HotelReply(requestId, "SYNTHETIC-REPLY", "RESPONDED", "SUP-SYNTHETIC", hotel,
                List.of(new Night(TARGET, "AVAILABLE", "Synthetic controlled response")), AS_OF, AS_OF);
        return new BookingReconfirmationEvidenceService.Outcome(
                new BookingReconfirmationResult(booking, null, reply, null, null), null, null, null, false);
    }

    private static void assertOutsideTransaction() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
    }

    private long assessments() {
        return jdbc.sql("SELECT count(*) FROM risk_assessments WHERE hotel_id=:id")
                .param("id", hotelId).query(Long.class).single();
    }
    private long jobCount() {
        return jdbc.sql("SELECT count(*) FROM jobs WHERE booking_id=:id")
                .param("id", bookingId).query(Long.class).single();
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant;
        MutableClock(Instant instant) { this.instant = instant; }
        void advance(long seconds) { instant = instant.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("Test clock is UTC");
            return this;
        }
        @Override public Instant instant() { return instant; }
    }
}
