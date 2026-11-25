package com.csro.backend.incidents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;

class IncidentDatabaseGuardTest {
    @Test
    void existingDatabasesAlternateHostsAndConnectionOptionsAreRejectedBeforeDatasourceCreation() {
        for (String unsafe : new String[] {
                "jdbc:postgresql://127.0.0.1:5432/yeohaengbom",
                "jdbc:postgresql://127.0.0.1:25446/yeohaengbom",
                "jdbc:postgresql://localhost:25446/yeohaengbom_incidents_test",
                "jdbc:postgresql://127.0.0.1:25435/yeohaengbom_incidents_test",
                IncidentTestDatabase.URL + "?currentSchema=public"
        }) {
            assertThrows(IllegalStateException.class, () -> IncidentTestDatabase.validate(Map.of(
                    "INCIDENT_REPORT_TEST_DB_URL", unsafe,
                    "INCIDENT_REPORT_TEST_DB_USER", IncidentTestDatabase.USER,
                    "INCIDENT_REPORT_TEST_DB_PASSWORD", "test-placeholder")));
        }
    }

    @Test
    void missingCredentialsBlankPasswordsAndWrongUsersFailClosed() {
        assertThrows(IllegalStateException.class, () -> IncidentTestDatabase.validate(Map.of()));
        assertThrows(IllegalStateException.class, () -> IncidentTestDatabase.validate(Map.of(
                "INCIDENT_REPORT_TEST_DB_URL", IncidentTestDatabase.URL,
                "INCIDENT_REPORT_TEST_DB_USER", IncidentTestDatabase.USER)));
        assertThrows(IllegalStateException.class, () -> IncidentTestDatabase.validate(Map.of(
                "INCIDENT_REPORT_TEST_DB_URL", IncidentTestDatabase.URL,
                "INCIDENT_REPORT_TEST_DB_USER", IncidentTestDatabase.USER,
                "INCIDENT_REPORT_TEST_DB_PASSWORD", " ")));
        assertThrows(IllegalStateException.class, () -> IncidentTestDatabase.validate(Map.of(
                "INCIDENT_REPORT_TEST_DB_URL", IncidentTestDatabase.URL,
                "INCIDENT_REPORT_TEST_DB_USER", "yb",
                "INCIDENT_REPORT_TEST_DB_PASSWORD", "test-placeholder")));
    }

    @Test
    void onlyExactDedicatedSettingsAreAcceptedWithoutLeakingThePassword() {
        var settings = IncidentTestDatabase.validate(Map.of(
                "INCIDENT_REPORT_TEST_DB_URL", IncidentTestDatabase.URL,
                "INCIDENT_REPORT_TEST_DB_USER", IncidentTestDatabase.USER,
                "INCIDENT_REPORT_TEST_DB_PASSWORD", "test-placeholder"));
        assertEquals(IncidentTestDatabase.URL, settings.url());
        assertEquals(IncidentTestDatabase.USER, settings.user());
        assertFalse(settings.toString().contains("test-placeholder"));

        var error = assertThrows(IllegalStateException.class, () -> IncidentTestDatabase.validate(Map.of(
                "INCIDENT_REPORT_TEST_DB_URL", IncidentTestDatabase.URL,
                "INCIDENT_REPORT_TEST_DB_USER", "postgres",
                "INCIDENT_REPORT_TEST_DB_PASSWORD", "test-placeholder")));
        assertFalse(error.toString().contains("test-placeholder"));
    }
}
