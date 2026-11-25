package com.csro.backend.incidents;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.csro.backend.reconfirmation.BookingReconfirmationEvidenceService;

class IncidentRecheckJobsTest {
    private final BookingReconfirmationEvidenceService evidence=mock(BookingReconfirmationEvidenceService.class);
    private final JdbcClient jdbc=mock(JdbcClient.class);
    private final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    private final IncidentRecheckJobs jobs=new IncidentRecheckJobs(evidence,jdbc,manager,Clock.systemUTC(),
            IncidentRecheckJobs.RetryPolicy.defaults());

    @AfterEach void clearTestState() { TransactionSynchronizationManager.clear(); }

    @Test void pendingWorkCannotCommitSeparatelyFromItsReceipt() {
        assertThrows(IllegalStateException.class,()->jobs.enqueue(1,1,1,"YB-001"));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThrows(IllegalStateException.class,()->jobs.enqueue(1,1,1,"YB-001"));
        verifyNoInteractions(evidence,jdbc,manager);
    }

    @Test void workerNeverRunsInsideACallerTransactionOrSynchronization() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThrows(IllegalStateException.class,()->jobs.execute("INC-1-1"));
        assertThrows(IllegalStateException.class,()->jobs.runDue(10));
        TransactionSynchronizationManager.clear();
        TransactionSynchronizationManager.initSynchronization();
        assertThrows(IllegalStateException.class,()->jobs.execute("INC-1-1"));
        verifyNoInteractions(evidence,jdbc,manager);
    }

    @Test void manualDrainCannotRunAnUnboundedBatchOrAForeignJob() {
        assertThrows(IllegalArgumentException.class,()->jobs.runDue(0));
        assertThrows(IllegalArgumentException.class,()->jobs.runDue(101));
        assertThrows(IllegalArgumentException.class,()->jobs.execute("RISK-1-1"));
        verifyNoInteractions(evidence,jdbc,manager);
    }
}
