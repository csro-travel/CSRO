package com.csro.backend.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.csro.backend.reconfirmation.BookingReconfirmationEvidenceService;
import com.csro.backend.reconfirmation.BookingReconfirmationException;
import com.csro.backend.reconfirmation.BookingReconfirmationException.Kind;

/** Worker boundaries and retry classification; persistence races use the isolated DB suite. */
class RiskRecheckJobsTest {

    private final BookingReconfirmationEvidenceService evidence = mock(BookingReconfirmationEvidenceService.class);
    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final RiskRecheckJobs jobs = new RiskRecheckJobs(evidence, jdbc, transactions,
            Clock.systemUTC(), RiskRecheckJobs.RetryPolicy.defaults());

    @AfterEach
    void clearOnlyTestTransactionState() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void enqueueCannotCommitIndependentlyOfItsAssessment() {
        assertThrows(IllegalStateException.class, () -> jobs.enqueueSelected(1, request(),
                RiskSelectionPolicy.F1_BASELINE, .9, "test-model", List.of(scope())));

        verifyNoInteractions(evidence, jdbc, transactions);
    }

    @Test
    void enqueueRejectsAReadOnlyCallerBeforeTouchingJobs() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

        assertThrows(IllegalStateException.class, () -> jobs.enqueueSelected(1, request(),
                RiskSelectionPolicy.F1_BASELINE, .9, "test-model", List.of(scope())));

        verifyNoInteractions(evidence, jdbc, transactions);
    }

    @Test
    void workerRejectsCallerTransactionsBeforeClaimOrHttp() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThrows(IllegalStateException.class, () -> jobs.execute("RISK-1-1"));
        assertThrows(IllegalStateException.class, () -> jobs.runDue(10));

        verifyNoInteractions(evidence, jdbc, transactions);
    }

    @Test
    void workerAlsoRejectsEmptySynchronizationScopesBeforeClaimOrHttp() {
        TransactionSynchronizationManager.initSynchronization();

        assertThrows(IllegalStateException.class, () -> jobs.execute("RISK-1-1"));
        assertThrows(IllegalStateException.class, () -> jobs.runDue(10));

        verifyNoInteractions(evidence, jdbc, transactions);
    }

    @Test
    void invalidOrUnselectedCohortsCannotCreateJobs() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThrows(IllegalArgumentException.class, () -> jobs.enqueueSelected(1, request(),
                RiskSelectionPolicy.F1_BASELINE, .01, "test-model", List.of(scope())));
        assertThrows(IllegalArgumentException.class, () -> jobs.enqueueSelected(1, request(),
                RiskSelectionPolicy.F1_BASELINE, .9, "test-model", List.of(scope(), scope())));

        verifyNoInteractions(evidence, jdbc, transactions);
    }

    @Test
    void manualDrainIsBoundedBeforeReadingAnyJob() {
        assertThrows(IllegalArgumentException.class, () -> jobs.runDue(0));
        assertThrows(IllegalArgumentException.class, () -> jobs.runDue(101));

        verifyNoInteractions(evidence, jdbc, transactions);
    }

    @ParameterizedTest
    @MethodSource("failureClassification")
    void onlyRecoverableTransportAndServerFailuresRetry(Kind kind, Integer httpStatus, boolean expected) {
        BookingReconfirmationException failure = mock(BookingReconfirmationException.class);
        when(failure.kind()).thenReturn(kind);
        when(failure.httpStatus()).thenReturn(httpStatus);

        assertEquals(expected, RiskRecheckJobs.retryable(failure));
    }

    private static Stream<Arguments> failureClassification() {
        return Stream.of(
                Arguments.of(Kind.TIMEOUT, null, true),
                Arguments.of(Kind.CONNECTION_FAILURE, null, true),
                Arguments.of(Kind.UNAVAILABLE, 503, true),
                Arguments.of(Kind.HTTP_ERROR, 500, true),
                Arguments.of(Kind.HTTP_ERROR, 429, true),
                Arguments.of(Kind.HTTP_ERROR, 422, false),
                Arguments.of(Kind.AUTHENTICATION, 401, false),
                Arguments.of(Kind.NOT_FOUND, 404, false),
                Arguments.of(Kind.REQUEST_MISMATCH, null, false),
                Arguments.of(Kind.INVALID_RESPONSE, 200, false),
                Arguments.of(Kind.INVALID_BOOKING_NIGHTS, null, false),
                Arguments.of(Kind.NO_SERVICE_TOKEN, null, false));
    }

    private static RiskRequest request() {
        return new RiskRequest("HOTEL-001", LocalDate.parse("2026-11-10"),
                OffsetDateTime.parse("2026-11-09T09:00:00+09:00"), RiskRequest.FEATURE_SET_VERSION,
                new RiskRequest.Features(.1, 1, 0, 0, 0, false));
    }

    private static RiskRecheckJobs.Scope scope() {
        return new RiskRecheckJobs.Scope(1, "YB-001", "HOTEL-001", "MOCK_SUPPLIER", "SUP-001",
                LocalDate.parse("2026-11-10"), LocalDate.parse("2026-11-13"), 2, 1);
    }
}
