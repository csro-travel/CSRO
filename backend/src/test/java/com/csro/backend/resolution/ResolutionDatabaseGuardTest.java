package com.csro.backend.resolution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class ResolutionDatabaseGuardTest {
    @Test
    void ordinaryDatabaseAndAlternateHostsCannotCreateTheTestDatasource() {
        for (String url : new String[]{
                "jdbc:postgresql://127.0.0.1:5432/yeohaengbom",
                "jdbc:postgresql://localhost:25445/yeohaengbom_resolution_test",
                "jdbc:postgresql://127.0.0.1:25445/yeohaengbom",
                ResolutionTestDatabase.URL + "?currentSchema=public",
                ResolutionTestDatabase.URL + "?options=-c%20search_path%3Dpublic"
        }) {
            assertThrows(IllegalStateException.class, () -> ResolutionTestDatabase.validate(settings(url,
                    ResolutionTestDatabase.USER, "test-placeholder")));
        }
        assertThrows(IllegalStateException.class, () -> ResolutionTestDatabase.validate(Map.of()));
        assertThrows(IllegalStateException.class, () -> ResolutionTestDatabase.validate(settings(
                ResolutionTestDatabase.URL, "postgres", "test-placeholder")));
        assertThrows(IllegalStateException.class, () -> ResolutionTestDatabase.validate(settings(
                ResolutionTestDatabase.URL, ResolutionTestDatabase.USER, " ")));
        assertFalse(ResolutionTestDatabase.validate(settings(ResolutionTestDatabase.URL,
                ResolutionTestDatabase.USER, "test-placeholder")).toString().contains("test-placeholder"));
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
        when(result.getString(2)).thenReturn(ResolutionTestDatabase.USER);
        assertThrows(IllegalStateException.class, () -> ResolutionTestDatabase.verifyIdentity(source));
        when(result.getString(1)).thenReturn(ResolutionTestDatabase.DATABASE);
        when(result.getString(2)).thenReturn("postgres");
        assertThrows(IllegalStateException.class, () -> ResolutionTestDatabase.verifyIdentity(source));
        when(result.getString(2)).thenReturn(ResolutionTestDatabase.USER);
        assertDoesNotThrow(() -> ResolutionTestDatabase.verifyIdentity(source));
    }

    private static Map<String, String> settings(String url, String user, String password) {
        return Map.of("RESOLUTION_TEST_DB_URL", url, "RESOLUTION_TEST_DB_USER", user,
                "RESOLUTION_TEST_DB_PASSWORD", password);
    }
}
