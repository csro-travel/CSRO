package com.csro.backend.quotes;

import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;

/** Fails closed before Flyway or an application context can use the dedicated test database. */
final class QuoteSearchTestDatabase {
    static final String URL = "jdbc:postgresql://127.0.0.1:25440/yeohaengbom_quotes_test";
    static final String DATABASE = "yeohaengbom_quotes_test";
    static final String USER = "yb_quote_test";

    private QuoteSearchTestDatabase() {}

    static Settings validate(Map<String, String> environment) {
        String password = environment.get("QUOTE_SEARCH_TEST_DB_PASSWORD");
        if (!URL.equals(environment.get("QUOTE_SEARCH_TEST_DB_URL"))
                || !USER.equals(environment.get("QUOTE_SEARCH_TEST_DB_USER"))
                || password == null || password.isBlank()) {
            throw new IllegalStateException("Exact dedicated quote test database settings are required before datasource creation");
        }
        return new Settings(URL, USER, password);
    }

    static void verifyIdentity(DataSource dataSource) {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT current_database(), current_user")) {
            if (!result.next() || !DATABASE.equals(result.getString(1)) || !USER.equals(result.getString(2))) {
                throw new IllegalStateException("Dedicated quote test database identity mismatch before Flyway");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not verify the dedicated quote test database before Flyway");
        }
    }

    record Settings(String url, String user, String password) {
        @Override public String toString() { return "QuoteSearchTestDatabase.Settings[credentials redacted]"; }
    }
}
