package com.csro.backend.replacements;

import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;

/** Verifies both the configured endpoint and its actual identity before Flyway can run. */
final class ReplacementTestDatabase {
    static final String URL = "jdbc:postgresql://127.0.0.1:25444/yeohaengbom_replacements_test";
    static final String DATABASE = "yeohaengbom_replacements_test";
    static final String USER = "yb_replacement_test";

    private ReplacementTestDatabase() {}

    static Settings validate(Map<String, String> environment) {
        String password = environment.get("REPLACEMENT_TEST_DB_PASSWORD");
        if (!URL.equals(environment.get("REPLACEMENT_TEST_DB_URL"))
                || !USER.equals(environment.get("REPLACEMENT_TEST_DB_USER"))
                || password == null || password.isBlank()) {
            throw new IllegalStateException("Exact dedicated replacement test database settings are required before datasource creation");
        }
        return new Settings(URL, USER, password);
    }

    static void verifyIdentity(DataSource source) {
        try (var connection = source.getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT current_database(), current_user")) {
            if (!result.next() || !DATABASE.equals(result.getString(1)) || !USER.equals(result.getString(2))) {
                throw new IllegalStateException("Dedicated replacement test database identity mismatch before Flyway");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not verify dedicated replacement test database identity before Flyway");
        }
    }

    record Settings(String url, String user, String password) {
        @Override public String toString() { return "ReplacementTestDatabase.Settings[credentials redacted]"; }
    }
}
