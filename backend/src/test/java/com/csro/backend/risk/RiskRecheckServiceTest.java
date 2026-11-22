package com.csro.backend.risk;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDate;
import java.time.OffsetDateTime;

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
}
