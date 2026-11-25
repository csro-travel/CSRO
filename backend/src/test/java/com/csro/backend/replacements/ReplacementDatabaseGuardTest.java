package com.csro.backend.replacements;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class ReplacementDatabaseGuardTest {
    @Test
    void ordinaryDatabaseAndAlternateHostsCannotCreateTheTestDatasource() {
        for (String url : new String[]{
                "jdbc:postgresql://127.0.0.1:5432/yeohaengbom",
                "jdbc:postgresql://localhost:25444/yeohaengbom_replacements_test",
                "jdbc:postgresql://127.0.0.1:25444/yeohaengbom",
                ReplacementTestDatabase.URL + "?currentSchema=public"
        }) {
            assertThrows(IllegalStateException.class, () -> ReplacementTestDatabase.validate(settings(url,
                    ReplacementTestDatabase.USER, "test-placeholder")));
        }
        assertThrows(IllegalStateException.class, () -> ReplacementTestDatabase.validate(Map.of()));
        assertThrows(IllegalStateException.class, () -> ReplacementTestDatabase.validate(settings(
                ReplacementTestDatabase.URL, "postgres", "test-placeholder")));
        assertThrows(IllegalStateException.class, () -> ReplacementTestDatabase.validate(settings(
                ReplacementTestDatabase.URL, ReplacementTestDatabase.USER, " ")));
        assertFalse(ReplacementTestDatabase.validate(settings(ReplacementTestDatabase.URL,
                ReplacementTestDatabase.USER, "test-placeholder")).toString().contains("test-placeholder"));
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
        when(result.getString(2)).thenReturn(ReplacementTestDatabase.USER);
        assertThrows(IllegalStateException.class, () -> ReplacementTestDatabase.verifyIdentity(source));
        when(result.getString(1)).thenReturn(ReplacementTestDatabase.DATABASE);
        when(result.getString(2)).thenReturn("postgres");
        assertThrows(IllegalStateException.class, () -> ReplacementTestDatabase.verifyIdentity(source));
        when(result.getString(2)).thenReturn(ReplacementTestDatabase.USER);
        assertDoesNotThrow(() -> ReplacementTestDatabase.verifyIdentity(source));
    }

    private static Map<String, String> settings(String url, String user, String password) {
        return Map.of("REPLACEMENT_TEST_DB_URL", url, "REPLACEMENT_TEST_DB_USER", user,
                "REPLACEMENT_TEST_DB_PASSWORD", password);
    }
}
