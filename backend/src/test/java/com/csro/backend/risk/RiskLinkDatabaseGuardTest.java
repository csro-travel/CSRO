package com.csro.backend.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** Runs without Spring and therefore cannot connect to any database. */
class RiskLinkDatabaseGuardTest {

    @Test
    void permitsOnlyTheDedicatedEndpointAndNonblankPassword() {
        var settings = RiskLinkTestDatabase.validate(Map.of(
                "RISK_LINK_TEST_DB_URL", RiskLinkTestDatabase.URL,
                "RISK_LINK_TEST_DB_PASSWORD", "unit-test-placeholder"));
        assertEquals(RiskLinkTestDatabase.URL, settings.url());
        assertFalse(settings.toString().contains("unit-test-placeholder"));
    }

    @Test
    void rejectsBusinessDatabaseAndEndpointVariantsBeforeSpringExists() {
        for (String url : new String[] {
                "jdbc:postgresql://127.0.0.1:5432/yeohaengbom",
                "jdbc:postgresql://127.0.0.1:25434/yeohaengbom",
                "jdbc:postgresql://localhost:25434/yeohaengbom_risk_link_test",
                RiskLinkTestDatabase.URL + "?currentSchema=public",
                "jdbc:postgresql://127.0.0.1:25433/yeohaengbom_reconfirmation_test",
                ""
        }) {
            var failure = assertThrows(IllegalStateException.class, () -> RiskLinkTestDatabase.validate(Map.of(
                    "RISK_LINK_TEST_DB_URL", url,
                    "RISK_LINK_TEST_DB_PASSWORD", "unit-test-placeholder")));
            assertFalse(failure.getMessage().contains("unit-test-placeholder"));
        }
    }

    @Test
    void rejectsMissingOrBlankCredentialsWithoutOpeningAContext() {
        assertThrows(IllegalStateException.class, () -> RiskLinkTestDatabase.validate(Map.of()));
        assertThrows(IllegalStateException.class, () -> RiskLinkTestDatabase.validate(Map.of(
                "RISK_LINK_TEST_DB_URL", RiskLinkTestDatabase.URL)));
        assertThrows(IllegalStateException.class, () -> RiskLinkTestDatabase.validate(Map.of(
                "RISK_LINK_TEST_DB_URL", RiskLinkTestDatabase.URL,
                "RISK_LINK_TEST_DB_PASSWORD", " \t")));
    }
}
