package com.csro.backend.risk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RiskJobsDatabaseGuardTest {
    @Test
    void normalLocalDatabaseAndAlternateHostsAreRejectedWithoutCreatingAnyDatasource() {
        for (String unsafe : new String[] {
                "jdbc:postgresql://127.0.0.1:5432/yeohaengbom",
                "jdbc:postgresql://127.0.0.1:25435/yeohaengbom",
                "jdbc:postgresql://localhost:25435/yeohaengbom_risk_stage2_test",
                RiskJobsTestDatabase.URL + "?currentSchema=public"
        }) {
            assertThrows(IllegalStateException.class, () -> RiskJobsTestDatabase.validate(
                    Map.of("RISK_JOBS_TEST_URL", unsafe, "RISK_JOBS_TEST_USER", "yb",
                            "RISK_JOBS_TEST_PASSWORD", "test-placeholder")));
        }
    }

    @Test
    void missingCredentialsOrWrongUserDoNotReachDatasourceCreation() {
        assertThrows(IllegalStateException.class, () -> RiskJobsTestDatabase.validate(Map.of()));
        assertThrows(IllegalStateException.class, () -> RiskJobsTestDatabase.validate(Map.of(
                "RISK_JOBS_TEST_URL", RiskJobsTestDatabase.URL, "RISK_JOBS_TEST_USER", "postgres",
                "RISK_JOBS_TEST_PASSWORD", "test-placeholder")));
        assertThrows(IllegalStateException.class, () -> RiskJobsTestDatabase.validate(Map.of(
                "RISK_JOBS_TEST_URL", RiskJobsTestDatabase.URL, "RISK_JOBS_TEST_USER", "yb",
                "RISK_JOBS_TEST_PASSWORD", " ")));
    }

    @Test
    void exactDedicatedEndpointIsAcceptedWithoutLeakingCredentialsInDiagnostics() {
        var settings = RiskJobsTestDatabase.validate(Map.of(
                "RISK_JOBS_TEST_URL", RiskJobsTestDatabase.URL, "RISK_JOBS_TEST_USER", "yb",
                "RISK_JOBS_TEST_PASSWORD", "test-placeholder"));
        assertEquals(RiskJobsTestDatabase.URL, settings.url());
        assertFalse(settings.toString().contains("test-placeholder"));
    }
}
