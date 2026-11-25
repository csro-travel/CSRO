package com.csro.backend.incidents;

import java.util.Map;

/** Pure guard; validate before constructing a DataSource or running Flyway. */
final class IncidentTestDatabase {
    static final String URL = "jdbc:postgresql://127.0.0.1:25446/yeohaengbom_incidents_test";
    static final String DATABASE = "yeohaengbom_incidents_test";
    static final String USER = "yb_incident_test";

    private IncidentTestDatabase() {}

    static Settings validate(Map<String, String> env) {
        String password = env.get("INCIDENT_REPORT_TEST_DB_PASSWORD");
        if (!URL.equals(env.get("INCIDENT_REPORT_TEST_DB_URL"))
                || !USER.equals(env.get("INCIDENT_REPORT_TEST_DB_USER"))
                || password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "Dedicated incident test DB settings required before datasource/Flyway creation");
        }
        return new Settings(URL, USER, password);
    }

    static final class Settings {
        private final String url;
        private final String user;
        private final String password;

        Settings(String url, String user, String password) {
            this.url = url;
            this.user = user;
            this.password = password;
        }

        String url() { return url; }
        String user() { return user; }
        String password() { return password; }

        @Override
        public String toString() {
            return "IncidentTestDatabase.Settings[credentials redacted]";
        }
    }
}
