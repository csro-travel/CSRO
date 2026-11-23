package com.csro.backend.risk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real batch candidate selection; inference and job execution remain controlled boundaries. */
@EnabledIfEnvironmentVariable(named = "RISK_BATCH_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RiskBatchServiceIntegrationTest {
    private JdbcClient jdbc;
    private RiskRecheckService assessments;
    private RiskRecheckJobs jobs;
    private RiskBatchService batch;
    private LocalDate target;
    private Fixture first;
    @TempDir Path projections;

    @BeforeAll
    void bootstrapOnlyAfterTheDedicatedEndpointGuard() {
        var settings = RiskBatchTestDatabase.validate(System.getenv());
        var datasource = new DriverManagerDataSource(settings.url(), settings.user(), settings.password());
        jdbc = JdbcClient.create(datasource);
        assertEquals("yeohaengbom_risk_batch_test", jdbc.sql("SELECT current_database()")
                .query(String.class).single());
        Flyway.configure().dataSource(datasource).locations("classpath:db/migration", "classpath:db/local")
                .cleanDisabled(true).load().migrate();
    }

    @BeforeEach
    void prepareIsolatedDateAndFixtureWithoutDeletingData() {
        target = unusedDecemberDate();
        first = fixture("A", "CONFIRMED", cutoff());
        assessments = mock(RiskRecheckService.class);
        jobs = mock(RiskRecheckJobs.class);
        batch = new RiskBatchService(assessments, jobs, jdbc);
        when(jobs.runDue(anyInt())).thenReturn(List.of(recoveredJob()));
    }

    @Test
    void missingProjectionFailsClosedWhileDueJobsRecoverAndFutureOrCancelledBookingsAreExcluded() {
        fixture("CANCELLED", "CANCELLED", cutoff());
        fixture("LATE", "CONFIRMED", cutoff().plusSeconds(1));
        var result = batch.run(new RiskBatchManifest(target, List.of()),
                RiskSelectionPolicy.F1_BASELINE, 100, 10);
        assertEquals(1, result.eligibleHotels());
        assertEquals(first.hotel(), result.hotels().getFirst().hotelId());
        assertEquals("MISSING_PROJECTION", result.hotels().getFirst().errorCode());
        assertFalse(result.successful());
        verifyNoInteractions(assessments);
        assertEquals(List.of(recoveredJob()), result.resumedJobs());
        verify(jobs).runDue(10);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failingHotelDoesNotBlockAnotherHotelOrIndependentDueJobs(boolean unexpectedFailure) throws IOException {
        var healthy = fixture("Z", "CONFIRMED", cutoff());
        when(assessments.assessBatchAndReconfirm(any(), any(), any())).thenAnswer(call -> {
            RiskRequest request = call.getArgument(0);
            if (request.hotelId().equals(first.hotel())) {
                if (unexpectedFailure) throw new UnsupportedOperationException("sensitive-provider-detail");
                throw new RiskMlException(RiskMlException.Kind.UNAVAILABLE, 503, "private-code", "private-trace");
            }
            assertEquals(healthy.hotel(), request.hotelId());
            assertEquals(List.of(healthy.booking()), call.getArgument(2));
            return successfulAssessment();
        });
        var result = batch.run(new RiskBatchManifest(target,
                List.of(projection(first), projection(healthy))), RiskSelectionPolicy.F1_BASELINE, 100, 10);
        assertEquals(2, result.eligibleHotels());
        assertFalse(result.successful());
        assertEquals(unexpectedFailure ? "UNEXPECTED_ERROR" : "ML_UNAVAILABLE",
                result.hotels().getFirst().errorCode());
        assertEquals(RiskBatchService.State.ASSESSED, result.hotels().get(1).state());
        assertEquals(healthy.hotel(), result.hotels().get(1).hotelId());
        assertEquals(List.of(recoveredJob()), result.resumedJobs());
        verify(assessments, times(2)).assessBatchAndReconfirm(any(), eq(RiskSelectionPolicy.F1_BASELINE), any());
        verify(jobs).runDue(10);
    }

    @Test
    void anEmptyEligibleCohortStillDrainsJobsFromEarlierWork() {
        LocalDate emptyDay = unusedDecemberDate();
        var result = batch.run(new RiskBatchManifest(emptyDay, List.of()),
                RiskSelectionPolicy.F1_BASELINE, 100, 7);
        assertEquals(0, result.eligibleHotels());
        assertTrue(result.hotels().isEmpty());
        assertEquals(List.of(recoveredJob()), result.resumedJobs());
        assertTrue(result.successful());
        verifyNoInteractions(assessments);
        verify(jobs).runDue(7);
    }

    @Test
    void overflowingTheExplicitTargetBoundStopsBeforeInferenceOrJobDispatch() throws IOException {
        var other = fixture("Z", "CONFIRMED", cutoff());
        var manifest = new RiskBatchManifest(target, List.of(projection(first), projection(other)));
        assertThrows(IllegalStateException.class,
                () -> batch.run(manifest, RiskSelectionPolicy.F1_BASELINE, 1, 10));
        verifyNoInteractions(assessments, jobs);
    }

    private LocalDate unusedDecemberDate() {
        return jdbc.sql("""
                SELECT candidate::date FROM generate_series(DATE '2026-12-01', DATE '2026-12-31',
                    INTERVAL '1 day') AS candidate
                WHERE NOT EXISTS (SELECT 1 FROM bookings WHERE check_in_date=candidate::date)
                ORDER BY candidate LIMIT 1
                """).query(LocalDate.class).optional().orElseThrow(
                        () -> new IllegalStateException("No unused December test date remains; use a fresh dedicated DB"));
    }

    private Fixture fixture(String prefix, String supplierStatus, OffsetDateTime created) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String hotel = "BATCH-" + prefix + "-" + suffix;
        String booking = "BATCH-SVC-" + suffix;
        long hotelId = jdbc.sql("INSERT INTO hotels (hotel_code,name,data_source) "
                + "VALUES (:code,:code,'SIMULATED') RETURNING id")
                .param("code", hotel).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO bookings (booking_code,customer_id,hotel_id,supplier_code,supplier_booking_id,
                    supplier_status,check_in_date,check_out_date,adults,rooms,original_total_amount,currency,
                    created_at,updated_at)
                SELECT :code,customer_id,:hotel,'MOCK_SUPPLIER',:supplier,:status,:target,:checkout,
                    2,1,450000,'KRW',:created,:created FROM bookings WHERE booking_code='YB-001'
                """).param("code", booking).param("hotel", hotelId).param("supplier", "SUP-" + suffix)
                .param("status", supplierStatus).param("target", target).param("checkout", target.plusDays(3))
                .param("created", created).update();
        return new Fixture(hotel, booking);
    }

    private Path projection(Fixture fixture) throws IOException {
        String hash = "a".repeat(64);
        String json = """
                {"format":"risk-event-projection-1","sourceKind":"SYNTHETIC_OBSERVATIONS",
                 "request":{"hotelId":"%s","targetCheckInDate":"%s","asOf":"%s",
                  "featureSetVersion":"risk-features-0.2","features":{"pastFailureRate90d":0.05,
                   "targetConfirmedBookingCount":1,"bookingVolumeGrowth7d":0,
                   "reconfirmationOverdueRate7d":0,"inventoryUnavailableRate7d":0,"isPeakDay":false}},
                 "cohortBookingCodes":["%s"],"provenance":{"sourceSha256":"%s",
                  "featureBuilderSha256":"%s","configSha256":"%s","lineageDigest":"%s"}}
                """.formatted(fixture.hotel(), target, cutoff(), fixture.booking(), hash, hash, hash, hash);
        Path path = projections.resolve(fixture.hotel() + ".json");
        Files.writeString(path, json);
        return path;
    }

    private RiskRecheckService.BatchOutcome successfulAssessment() {
        return new RiskRecheckService.BatchOutcome(new RiskRecheckService.Outcome(1, .02,
                RiskSelectionPolicy.F1_BASELINE, RiskSelectionPolicy.F1_BASELINE.threshold(), false,
                RiskMlClient.MODEL_VERSION, RiskRequest.FEATURE_SET_VERSION, List.of()), false);
    }

    private RiskRecheckJobs.Execution recoveredJob() {
        return new RiskRecheckJobs.Execution("RISK-999-999", "OLDER-BOOKING", "RISK-999-999",
                RiskRecheckJobs.State.COMPLETED, null, null);
    }

    private OffsetDateTime cutoff() { return target.minusDays(1).atTime(9, 0).atOffset(ZoneOffset.ofHours(9)); }
    private record Fixture(String hotel, String booking) {}
}
