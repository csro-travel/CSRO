package com.csro.backend.offers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class OfferSelectionDatabaseGuardTest {
    @Test
    void ordinaryDatabaseAndAlternateHostsCannotCreateTheTestDatasource() {
        for (String url : new String[]{
                "jdbc:postgresql://127.0.0.1:5432/yeohaengbom",
                "jdbc:postgresql://localhost:25441/yeohaengbom_offers_test",
                "jdbc:postgresql://127.0.0.1:25441/yeohaengbom",
                OfferSelectionTestDatabase.URL + "?currentSchema=public"
        }) {
            assertThrows(IllegalStateException.class, () -> OfferSelectionTestDatabase.validate(settings(url,
                    OfferSelectionTestDatabase.USER, "test-placeholder")));
        }
        assertThrows(IllegalStateException.class, () -> OfferSelectionTestDatabase.validate(Map.of()));
        assertThrows(IllegalStateException.class, () -> OfferSelectionTestDatabase.validate(settings(
                OfferSelectionTestDatabase.URL, "postgres", "test-placeholder")));
        assertThrows(IllegalStateException.class, () -> OfferSelectionTestDatabase.validate(settings(
                OfferSelectionTestDatabase.URL, OfferSelectionTestDatabase.USER, " ")));
        assertFalse(OfferSelectionTestDatabase.validate(settings(OfferSelectionTestDatabase.URL,
                OfferSelectionTestDatabase.USER, "test-placeholder")).toString().contains("test-placeholder"));
    }

    @Test
    void actualDatabaseAndUserMustMatchBeforeFlyway() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var result = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT current_database(), current_user")).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getString(1)).thenReturn("yeohaengbom");
        when(result.getString(2)).thenReturn(OfferSelectionTestDatabase.USER);
        assertThrows(IllegalStateException.class, () -> OfferSelectionTestDatabase.verifyIdentity(source));
        when(result.getString(1)).thenReturn(OfferSelectionTestDatabase.DATABASE);
        when(result.getString(2)).thenReturn("postgres");
        assertThrows(IllegalStateException.class, () -> OfferSelectionTestDatabase.verifyIdentity(source));
        when(result.getString(2)).thenReturn(OfferSelectionTestDatabase.USER);
        assertDoesNotThrow(() -> OfferSelectionTestDatabase.verifyIdentity(source));
    }

    private static Map<String, String> settings(String url, String user, String password) {
        return Map.of("OFFER_SELECTION_TEST_DB_URL", url, "OFFER_SELECTION_TEST_DB_USER", user,
                "OFFER_SELECTION_TEST_DB_PASSWORD", password);
    }
}
