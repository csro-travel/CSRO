package com.csro.backend.risk;

import java.util.Map;

/** Validate before constructing a datasource or starting Flyway. */
final class RiskBatchTestDatabase {
    static final String URL = "jdbc:postgresql://127.0.0.1:25436/yeohaengbom_risk_batch_test";

    private RiskBatchTestDatabase() {}

    static Settings validate(Map<String, String> environment) {
        String password = environment.get("RISK_BATCH_TEST_DB_PASSWORD");
        if (!URL.equals(environment.get("RISK_BATCH_TEST_DB_URL"))
                || !"yb".equals(environment.get("RISK_BATCH_TEST_DB_USER"))
                || password == null || password.isBlank()) {
            throw new IllegalStateException("Dedicated risk batch DB settings required before datasource/Flyway creation");
        }
        return new Settings(URL, "yb", password);
    }

    record Settings(String url, String user, String password) {
        @Override public String toString() { return "RiskBatchTestDatabase.Settings[credentials redacted]"; }
    }
}
