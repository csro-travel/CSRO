package com.csro.backend.risk;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.csro.backend.reconfirmation.BookingReconfirmationEvidenceService;

/** Early guards must work even on direct calls that bypass Spring's NEVER proxy. */
class RiskRecheckServiceTest {

    private final RiskMlClient ml = mock(RiskMlClient.class);
    private final BookingReconfirmationEvidenceService reconfirmation =
            mock(BookingReconfirmationEvidenceService.class);
    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final RiskRecheckService service = new RiskRecheckService(ml, reconfirmation, jdbc, transactions);

    private static RiskRequest request() {
        return new RiskRequest("HOTEL-001", LocalDate.parse("2026-11-10"),
                OffsetDateTime.parse("2026-11-09T09:00:00+09:00"), "risk-features-0.2",
                new RiskRequest.Features(0.05, 1, 0.4, 0.2, 0.1, false));
    }

    @AfterEach
    void clearOnlyTestTransactionState() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void directCallRejectsAnActualOuterTransactionBeforeReadingOrCallingHttp() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThrows(IllegalStateException.class,
                () -> service.assessAndReconfirm(request(), RiskSelectionPolicy.F1_BASELINE));

        verifyNoInteractions(ml, reconfirmation, jdbc, transactions);
    }

    @Test
    void directCallRejectsSynchronizationWithoutAnActualTransaction() {
        TransactionSynchronizationManager.initSynchronization();

        assertThrows(IllegalStateException.class,
                () -> service.assessAndReconfirm(request(), RiskSelectionPolicy.F1_BASELINE));

        verifyNoInteractions(ml, reconfirmation, jdbc, transactions);
    }

    @Test
    void requiresAnExplicitPolicyBeforeAnyDatabaseOrHttpWork() {
        assertThrows(IllegalArgumentException.class, () -> service.assessAndReconfirm(request(), null));

        verifyNoInteractions(ml, reconfirmation, jdbc, transactions);
    }

    @Test
    void rejectsMissingRequestBeforeAnyDatabaseOrHttpWork() {
        assertThrows(IllegalArgumentException.class,
                () -> service.assessAndReconfirm(null, RiskSelectionPolicy.F1_BASELINE));

        verifyNoInteractions(ml, reconfirmation, jdbc, transactions);
    }

    @Test
    void rejectsAnUnsupportedFeatureVersionBeforeAnyDatabaseOrHttpWork() {
        RiskRequest valid = request();
        RiskRequest incompatible = new RiskRequest(valid.hotelId(), valid.targetCheckInDate(), valid.asOf(),
                "risk-features-0.1", valid.features());

        assertThrows(IllegalArgumentException.class,
                () -> service.assessAndReconfirm(incompatible, RiskSelectionPolicy.F1_BASELINE));

        verifyNoInteractions(ml, reconfirmation, jdbc, transactions);
    }
    @Test
    void missingObservedHistoryCannotFallBackToCallerFeaturesOrCallMl() {
        var store = mock(RiskAssessmentStore.class);
        var jobs = mock(RiskRecheckJobs.class);
        var common = new RiskAssessmentService(Optional.empty(), store, ml, RiskMlClient.MODEL_VERSION);
        var observed = new RiskRecheckService(common, store, jdbc, transactions, jobs);

        assertThrows(IllegalStateException.class, () -> observed.assessBatchAndReconfirm(
                "HOTEL-001", request().targetCheckInDate(), RiskSelectionPolicy.F1_BASELINE,
                request(), List.of("YB-001")));

        verifyNoInteractions(ml, store, jobs, jdbc, transactions);
    }

    @Test
    void computedFeaturesMustMatchThePythonReferenceBeforeHttpOrBusinessReads() {
        var store = mock(RiskAssessmentStore.class);
        var jobs = mock(RiskRecheckJobs.class);
        var common = new RiskAssessmentService(Optional.of(coldStartHistory()), store, ml,
                RiskMlClient.MODEL_VERSION);
        var observed = new RiskRecheckService(common, store, jdbc, transactions, jobs);

        // Actual history computes growth/overdue/inventory zero, unlike this caller projection.
        assertThrows(IllegalArgumentException.class, () -> observed.assessBatchAndReconfirm(
                "HOTEL-001", request().targetCheckInDate(), RiskSelectionPolicy.F1_BASELINE,
                request(), List.of("YB-001")));

        verifyNoInteractions(ml, store, jobs, jdbc, transactions);
    }

    @Test
    void computedCohortMustMatchReferenceEvenWhenAllSixFeaturesMatch() {
        var store = mock(RiskAssessmentStore.class);
        var jobs = mock(RiskRecheckJobs.class);
        var common = new RiskAssessmentService(Optional.of(coldStartHistory()), store, ml,
                RiskMlClient.MODEL_VERSION);
        var observed = new RiskRecheckService(common, store, jdbc, transactions, jobs);
        var expected = new RiskRequest("HOTEL-001", request().targetCheckInDate(), request().asOf(),
                RiskRequest.FEATURE_SET_VERSION, new RiskRequest.Features(.05, 1, 0, 0, 0, false));

        assertThrows(IllegalArgumentException.class, () -> observed.assessBatchAndReconfirm(
                "HOTEL-001", expected.targetCheckInDate(), RiskSelectionPolicy.F1_BASELINE,
                expected, List.of("OTHER-BOOKING")));

        verifyNoInteractions(ml, store, jobs, jdbc, transactions);
    }

    private static RiskObservations coldStartHistory() {
        var json = JsonMapper.builder().build();
        return (hotel, cutoff) -> Optional.of(List.of(new RiskObservations.Event("observed-confirmation",
                "HOTEL-001", "YB-001", "BOOKING_STATUS", request().asOf(), request().asOf(),
                json.valueToTree(Map.of("status", "CONFIRMED", "channel", "PLATFORM",
                        "check_in_date", "2026-11-10", "check_out_date", "2026-11-13")))));
    }

}
