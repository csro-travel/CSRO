package com.csro.backend.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.json.JsonMapper;
import static com.csro.backend.risk.RiskProjectionTest.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig
@EnabledIfEnvironmentVariable(named="CSRO_RISK_LIVE_TEST", matches="true")
class RiskAssessmentLiveTest {
    // No Boot/local profile or Flyway initialization. Only a separately provisioned test DB.
    @org.springframework.boot.test.context.TestConfiguration
    @EnableTransactionManagement
    @Import({RiskAssessmentService.class, RiskAssessmentStore.class})
    static class Config {
        @Bean DataSource dataSource() {
            String password = System.getenv("CSRO_RISK_TEST_DB_PASSWORD");
            if (password == null || password.isBlank()) throw new IllegalStateException("Missing isolated DB credentials");
            return new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25439/csro_risk_fixture_test", "yb", password);
        }
        @Bean DataSourceTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean JdbcClient jdbc(DataSource ds) { return JdbcClient.create(ds); }
        @Bean JsonMapper json() { return JSON; }
        @Bean RiskObservations observations() {
            return (hotel, cutoff) -> HOTEL.equals(hotel) && CUTOFF.isEqual(cutoff)
                    ? Optional.of(history()) : Optional.empty();
        }
    }

    @Autowired RiskAssessmentService service;
    @Autowired JdbcClient jdbc;
    @Autowired RiskAssessmentStore store;
    @Autowired RiskObservations source;

    @Test void storesOnlyValidatedInferenceFromLiveModel() {
        long before = count();
        assertThrows(IllegalStateException.class, () -> service.assess(HOTEL, DAY.plusDays(1)));
        assertEquals(before, count());
        var rejected = new RiskAssessmentService(Optional.of(source), store,
                System.getenv("ML_RISK_BASE_URL"), "deliberately-invalid", "unused");
        var denied = assertThrows(RiskMlException.class, () -> rejected.assess(HOTEL, DAY));
        assertEquals(RiskMlException.Kind.AUTHENTICATION, denied.kind());
        assertEquals(401, denied.httpStatus());
        assertEquals(before, count());

        var result = service.assess(HOTEL, DAY);
        var score = jdbc.sql("""
                SELECT risk_score FROM risk_assessments r JOIN hotels h ON h.id=r.hotel_id
                WHERE r.id=:id AND hotel_code=:hotel AND target_check_in_date=:target AND as_of=:cutoff
                  AND model_version='process-synthetic-lightgbm-0.2-84d386281d22'
                  AND feature_set_version='risk-features-0.2' AND feature_snapshot=CAST(:snapshot AS jsonb)
                """).param("id", result.id()).param("hotel", HOTEL).param("target", DAY).param("cutoff", CUTOFF)
                .param("snapshot", JSON.writeValueAsString(result.prepared().input().features()))
                .query(BigDecimal.class).single();
        assertEquals(result.modelScore().setScale(4, RoundingMode.HALF_UP), score);
        assertEquals(result.id(), service.assess(HOTEL, DAY).id());
        assertEquals(before + 1, count());
        System.out.printf("API-023 score=%s, PostgreSQL score=%s%n", result.modelScore(), score);
    }

    private long count() {
        return jdbc.sql("SELECT count(*) FROM risk_assessments").query(Long.class).single();
    }
}
