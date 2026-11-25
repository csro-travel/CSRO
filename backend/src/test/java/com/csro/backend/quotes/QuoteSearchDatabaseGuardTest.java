package com.csro.backend.quotes;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class QuoteSearchDatabaseGuardTest {
    @Test
    void ordinaryBusinessDatabaseAndAlternateHostsNeverReachDatasourceCreation() {
        for (String url : new String[]{
                "jdbc:postgresql://127.0.0.1:5432/yeohaengbom",
                "jdbc:postgresql://127.0.0.1:25440/yeohaengbom",
                "jdbc:postgresql://localhost:25440/yeohaengbom_quotes_test",
                QuoteSearchTestDatabase.URL + "?currentSchema=public"
        }) {
            assertThrows(IllegalStateException.class, () -> QuoteSearchTestDatabase.validate(
                    Map.of("QUOTE_SEARCH_TEST_DB_URL", url, "QUOTE_SEARCH_TEST_DB_USER", QuoteSearchTestDatabase.USER,
                            "QUOTE_SEARCH_TEST_DB_PASSWORD", "test-placeholder")));
        }
        assertThrows(IllegalStateException.class, () -> QuoteSearchTestDatabase.validate(Map.of()));
        assertThrows(IllegalStateException.class, () -> QuoteSearchTestDatabase.validate(
                Map.of("QUOTE_SEARCH_TEST_DB_URL", QuoteSearchTestDatabase.URL,
                        "QUOTE_SEARCH_TEST_DB_USER", "postgres", "QUOTE_SEARCH_TEST_DB_PASSWORD", "test-placeholder")));
        assertThrows(IllegalStateException.class, () -> QuoteSearchTestDatabase.validate(
                Map.of("QUOTE_SEARCH_TEST_DB_URL", QuoteSearchTestDatabase.URL,
                        "QUOTE_SEARCH_TEST_DB_USER", QuoteSearchTestDatabase.USER, "QUOTE_SEARCH_TEST_DB_PASSWORD", " ")));
    }

    @Test
    void actualDatabaseAndUserMustMatchBeforeFlywayCanRun() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var result = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT current_database(), current_user")).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getString(1)).thenReturn("yeohaengbom");
        when(result.getString(2)).thenReturn(QuoteSearchTestDatabase.USER);
        assertThrows(IllegalStateException.class, () -> QuoteSearchTestDatabase.verifyIdentity(source));
        when(result.getString(1)).thenReturn(QuoteSearchTestDatabase.DATABASE);
        when(result.getString(2)).thenReturn("postgres");
        assertThrows(IllegalStateException.class, () -> QuoteSearchTestDatabase.verifyIdentity(source));
        when(result.getString(2)).thenReturn(QuoteSearchTestDatabase.USER);
        assertDoesNotThrow(() -> QuoteSearchTestDatabase.verifyIdentity(source));
    }

    @Test
    void acceptedSettingsNeverPrintThePassword() {
        var settings = QuoteSearchTestDatabase.validate(Map.of(
                "QUOTE_SEARCH_TEST_DB_URL", QuoteSearchTestDatabase.URL,
                "QUOTE_SEARCH_TEST_DB_USER", QuoteSearchTestDatabase.USER,
                "QUOTE_SEARCH_TEST_DB_PASSWORD", "test-placeholder"));
        assertEquals(QuoteSearchTestDatabase.URL, settings.url());
        assertFalse(settings.toString().contains("test-placeholder"));
    }
}
