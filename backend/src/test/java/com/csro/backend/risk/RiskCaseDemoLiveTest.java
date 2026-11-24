package com.csro.backend.risk;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.csro.backend.reconfirmation.BookingReconfirmationEvidenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import static com.csro.backend.risk.RiskProjectionTest.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig
@EnabledIfEnvironmentVariable(named = "CSRO_RISK_DEMO_E2E", matches = "true")
@TestPropertySource(properties = {"ml.risk.base-url=http://127.0.0.1:18023",
        "booking.reconfirmation.mock.base-url=http://127.0.0.1:18027"})
class RiskCaseDemoLiveTest {
    @org.springframework.boot.test.context.TestConfiguration
    @Import({RiskAssessmentLiveTest.Config.class, RiskRecheckService.class, RiskRecheckJobs.class})
    @ComponentScan(basePackageClasses = BookingReconfirmationEvidenceService.class)
    static class Config {}

    @Autowired RiskAssessmentService risk;
    @Autowired RiskObservations observations;
    @Autowired RiskRecheckService selection;
    @Autowired JdbcClient jdbc;
    @Autowired RiskAssessmentStore store;

    // Same raw inference, two explicit comparison policies; no scheduler or production default.
    private void runDemo(RiskAssessmentService assessor, LocalDate date, Consumer<RiskAssessmentService.Assessment> afterScore) {
        var assessment = assessor.assess(HOTEL, date);
        afterScore.accept(assessment);
        assertEquals(List.of("YB-001"), assessment.prepared().bookings());
        var low = selection.assessAndReconfirm(HOTEL, date, RiskSelectionPolicy.F1_BASELINE);
        assertFalse(low.selected());
        assertEquals(assessment.id(), low.assessmentId());
        assertBeforeRecheck(1);
        assertEquals(0, jdbc.sql("SELECT count(*) FROM jobs").query(Integer.class).single());
        var first = selection.assessAndReconfirm(HOTEL, date, RiskSelectionPolicy.RECALL_50_F1);
        assertTrue(first.selected());
        assertEquals(1, first.rechecks().size());
        assertEquals("C-001", first.rechecks().getFirst().caseCode());
        var replay = selection.assessAndReconfirm(HOTEL, date, RiskSelectionPolicy.RECALL_50_F1);
        assertEquals(first, replay);
        assertEquals(1, jdbc.sql("SELECT count(*) FROM jobs WHERE status='COMPLETED' AND risk_assessment_id=:id")
                .param("id", assessment.id()).query(Integer.class).single());
        assertEquals(2, jdbc.sql("SELECT count(*) FROM evidence_records WHERE job_id IS NOT NULL")
                .query(Integer.class).single());
    }

    private void assertBeforeRecheck(int assessments) {
        var counts = jdbc.sql("""
                SELECT (SELECT count(*) FROM risk_assessments), (SELECT count(*) FROM evidence_records),
                       (SELECT count(*) FROM cases), (SELECT count(*) FROM case_tasks),
                       (SELECT count(*) FROM booking_nights WHERE fulfillment_status='UNKNOWN'
                            AND fact_status='UNCONFIRMED' AND accepted_assertion_id IS NULL)
                """).query((r,n) -> List.of(r.getInt(1),r.getInt(2),r.getInt(3),r.getInt(4),r.getInt(5))).single();
        assertEquals(List.of(assessments,0,0,0,3), counts);
    }

    @Test void explicitSelectionAfterSuccessfulAssessmentOnly() {
        assertBeforeRecheck(0);
        assertThrows(IllegalStateException.class, () -> runDemo(risk, DAY.plusDays(1), a -> fail()));
        assertBeforeRecheck(0);
        var unauthorized = new RiskAssessmentService(Optional.of(observations), store,
                "http://127.0.0.1:18023", "invalid-demo-token", "unused");
        assertThrows(HttpClientErrorException.Unauthorized.class, () -> runDemo(unauthorized, DAY, a -> fail()));
        assertBeforeRecheck(0);
        runDemo(risk, DAY, a -> {
            assertBeforeRecheck(1);
            var stored = jdbc.sql("""
                    SELECT risk_score FROM risk_assessments WHERE id=:id
                      AND model_version='process-synthetic-lightgbm-0.2-84d386281d22'
                      AND feature_snapshot=CAST(:snapshot AS jsonb)
                    """).param("id", a.id()).param("snapshot", JSON.writeValueAsString(a.prepared().input().features()))
                    .query(java.math.BigDecimal.class).single();
            assertEquals(a.modelScore().setScale(4, java.math.RoundingMode.HALF_UP), stored);
            System.out.printf("API-023 score=%s, PostgreSQL score=%s%n", a.modelScore(), stored);
        });
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory();
        factory.setReadTimeout(java.time.Duration.ofSeconds(10));
        var read = RestClient.builder().requestFactory(factory).baseUrl("http://127.0.0.1:18038")
                .defaultHeaders(h -> h.setBearerAuth(System.getenv("CSRO_DEMO_TOKEN"))).build();
        var items = read.get().uri("/api/v1/cases").retrieve().body(JsonNode.class).required("items");
        assertEquals(1, items.size());
        assertEquals("C-001", items.get(0).required("caseId").asString());
        var detail = read.get().uri("/api/v1/cases/C-001").retrieve().body(JsonNode.class);
        assertEquals("YB-001", detail.at("/booking/bookingId").asString());
        assertEquals("CONFIRMED", detail.at("/booking/supplierStatus").asString());
        assertEquals("OPEN", detail.required("status").asString());
        assertEquals("URGENT", detail.required("priority").asString());
        assertEquals(1, detail.required("tasks").size());
        assertEquals("CONTACT_CUSTOMER", detail.at("/nextAction/type").asString());
        assertEquals("TODO", detail.at("/nextAction/status").asString());
        assertEquals(2, detail.required("evidence").size());
        var nights = detail.required("nights");
        assertEquals(3, nights.size());
        for (int i = 0; i < nights.size(); i++) {
            assertEquals(DAY.plusDays(i).toString(), nights.get(i).required("stayDate").asString());
            assertEquals(i == 0 ? "UNAVAILABLE" : "UNKNOWN", nights.get(i).required("fulfillmentStatus").asString());
            assertEquals(i == 0 ? "VERIFIED" : "UNCONFIRMED", nights.get(i).required("factStatus").asString());
        }
    }
}
