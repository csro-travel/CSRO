package com.csro.backend.hotelanalysis;

import java.util.Map;

/** Pure endpoint guard; call before constructing a DataSource or starting Flyway. */
final class HotelAnalysisTestDatabase {
    static final String URL = "jdbc:postgresql://127.0.0.1:25447/yeohaengbom_hotel_analysis_test";
    static final String DATABASE = "yeohaengbom_hotel_analysis_test";
    static final String USER = "yb_hotel_analysis_test";

    private HotelAnalysisTestDatabase() {}

    static Settings validate(Map<String, String> env) {
        String password = env.get("HOTEL_ANALYSIS_TEST_DB_PASSWORD");
        if (!URL.equals(env.get("HOTEL_ANALYSIS_TEST_DB_URL"))
                || !USER.equals(env.get("HOTEL_ANALYSIS_TEST_DB_USER"))
                || password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "Dedicated hotel analysis test DB settings required before datasource/Flyway creation");
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
            return "HotelAnalysisTestDatabase.Settings[credentials redacted]";
        }
    }
}
