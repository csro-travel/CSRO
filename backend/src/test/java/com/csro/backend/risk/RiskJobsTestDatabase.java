package com.csro.backend.risk;

import java.util.Map;

/** Pure fail-closed guard: callers must run this before datasource or Flyway creation. */
final class RiskJobsTestDatabase {
    static final String URL = "jdbc:postgresql://127.0.0.1:25435/yeohaengbom_risk_stage2_test";

    private RiskJobsTestDatabase() {}

    static Settings validate(Map<String, String> env) {
        String password = env.get("RISK_JOBS_TEST_PASSWORD");
        if (!URL.equals(env.get("RISK_JOBS_TEST_URL")) || !"yb".equals(env.get("RISK_JOBS_TEST_USER"))
                || password == null || password.isBlank()) {
            throw new IllegalStateException("Dedicated risk jobs DB settings required before datasource/Flyway creation");
        }
        return new Settings(URL, "yb", password);
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
        @Override public String toString() { return "RiskJobsTestDatabase.Settings[credentials redacted]"; }
    }
}
