package com.csro.backend.risk;

import java.util.Map;

/** Fail closed before building a Spring application or starting Flyway. */
final class RiskLinkTestDatabase {
    static final String URL = "jdbc:postgresql://127.0.0.1:25434/yeohaengbom_risk_link_test";

    private RiskLinkTestDatabase() {}

    static Settings validate(Map<String, String> environment) {
        String url = environment.get("RISK_LINK_TEST_DB_URL");
        String password = environment.get("RISK_LINK_TEST_DB_PASSWORD");
        if (!URL.equals(url) || password == null || password.isBlank()) {
            throw new IllegalStateException("Risk integration tests require RISK_LINK_TEST_DB_URL="
                    + URL + " and a nonblank RISK_LINK_TEST_DB_PASSWORD; Spring has not been created");
        }
        return new Settings(url, password);
    }

    static final class Settings {
        private final String url;
        private final String password;

        private Settings(String url, String password) {
            this.url = url;
            this.password = password;
        }

        String url() { return url; }
        String password() { return password; }

        @Override
        public String toString() { return "RiskLinkTestDatabase.Settings[credentials redacted]"; }
    }
}
