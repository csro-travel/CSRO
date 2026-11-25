package com.csro.backend.hotelanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class HotelAnalysisDatabaseGuardTest {
    @Test
    void rejectsOtherDatabasesHostsAndOptionsBeforeCreatingAnyDatasource() {
        for (String unsafe : new String[] {
                "jdbc:postgresql://127.0.0.1:5432/yeohaengbom",
                "jdbc:postgresql://127.0.0.1:25447/yeohaengbom",
                "jdbc:postgresql://localhost:25447/yeohaengbom_hotel_analysis_test",
                "jdbc:postgresql://127.0.0.1:25446/yeohaengbom_hotel_analysis_test",
                HotelAnalysisTestDatabase.URL + "?currentSchema=public"
        }) {
            assertThrows(IllegalStateException.class, () -> HotelAnalysisTestDatabase.validate(Map.of(
                    "HOTEL_ANALYSIS_TEST_DB_URL", unsafe,
                    "HOTEL_ANALYSIS_TEST_DB_USER", HotelAnalysisTestDatabase.USER,
                    "HOTEL_ANALYSIS_TEST_DB_PASSWORD", "test-placeholder")));
        }
    }

    @Test
    void rejectsMissingCredentialsWrongUserAndBlankPasswordWithoutOpeningAConnection() {
        assertThrows(IllegalStateException.class, () -> HotelAnalysisTestDatabase.validate(Map.of()));
        assertThrows(IllegalStateException.class, () -> HotelAnalysisTestDatabase.validate(Map.of(
                "HOTEL_ANALYSIS_TEST_DB_URL", HotelAnalysisTestDatabase.URL,
                "HOTEL_ANALYSIS_TEST_DB_USER", "postgres",
                "HOTEL_ANALYSIS_TEST_DB_PASSWORD", "test-placeholder")));
        assertThrows(IllegalStateException.class, () -> HotelAnalysisTestDatabase.validate(Map.of(
                "HOTEL_ANALYSIS_TEST_DB_URL", HotelAnalysisTestDatabase.URL,
                "HOTEL_ANALYSIS_TEST_DB_USER", HotelAnalysisTestDatabase.USER)));
        assertThrows(IllegalStateException.class, () -> HotelAnalysisTestDatabase.validate(Map.of(
                "HOTEL_ANALYSIS_TEST_DB_URL", HotelAnalysisTestDatabase.URL,
                "HOTEL_ANALYSIS_TEST_DB_USER", HotelAnalysisTestDatabase.USER,
                "HOTEL_ANALYSIS_TEST_DB_PASSWORD", " \t")));
    }

    @Test
    void acceptsOnlyTheDedicatedIdentityAndKeepsCredentialsOutOfDiagnostics() {
        var settings = HotelAnalysisTestDatabase.validate(Map.of(
                "HOTEL_ANALYSIS_TEST_DB_URL", HotelAnalysisTestDatabase.URL,
                "HOTEL_ANALYSIS_TEST_DB_USER", HotelAnalysisTestDatabase.USER,
                "HOTEL_ANALYSIS_TEST_DB_PASSWORD", "test-placeholder"));
        assertEquals(HotelAnalysisTestDatabase.URL, settings.url());
        assertEquals(HotelAnalysisTestDatabase.USER, settings.user());
        assertFalse(settings.toString().contains("test-placeholder"));
        assertFalse(settings.toString().contains("HOTEL_ANALYSIS_TEST_DB_PASSWORD"));
    }
}
