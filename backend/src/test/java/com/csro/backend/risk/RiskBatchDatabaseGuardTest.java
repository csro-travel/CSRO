package com.csro.backend.risk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RiskBatchDatabaseGuardTest {
    @Test
    void rejectsNormalDatabaseAlternativeHostsAndMissingCredentialsBeforeConnecting() {
        for (String url : new String[] {"jdbc:postgresql://127.0.0.1:5432/yeohaengbom",
                "jdbc:postgresql://localhost:25436/yeohaengbom_risk_batch_test",
                RiskBatchTestDatabase.URL + "?currentSchema=public"}) {
            assertThrows(IllegalStateException.class, () -> RiskBatchTestDatabase.validate(Map.of(
                    "RISK_BATCH_TEST_DB_URL", url, "RISK_BATCH_TEST_DB_USER", "yb",
                    "RISK_BATCH_TEST_DB_PASSWORD", "test-placeholder")));
        }
        assertThrows(IllegalStateException.class, () -> RiskBatchTestDatabase.validate(Map.of()));
        assertThrows(IllegalStateException.class, () -> RiskBatchTestDatabase.validate(Map.of(
                "RISK_BATCH_TEST_DB_URL", RiskBatchTestDatabase.URL,
                "RISK_BATCH_TEST_DB_USER", "postgres", "RISK_BATCH_TEST_DB_PASSWORD", "test-placeholder")));
    }

    @Test
    void acceptsOnlyTheDedicatedEndpointAndRedactsCredentials() {
        var settings = RiskBatchTestDatabase.validate(Map.of(
                "RISK_BATCH_TEST_DB_URL", RiskBatchTestDatabase.URL, "RISK_BATCH_TEST_DB_USER", "yb",
                "RISK_BATCH_TEST_DB_PASSWORD", "test-placeholder"));
        assertEquals(RiskBatchTestDatabase.URL, settings.url());
        assertFalse(settings.toString().contains("test-placeholder"));
    }
}
