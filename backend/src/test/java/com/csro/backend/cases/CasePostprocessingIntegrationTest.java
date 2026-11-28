package com.csro.backend.cases;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "POSTPROCESSING_INTEGRATION_TEST", matches = "true")
class CasePostprocessingIntegrationTest {
    @Test
    void finalizesDocumentAndTasksTogetherAndReplaysTheSameRequest() throws Exception {
        var source = isolatedSource();
        Flyway.configure().dataSource(source).locations("classpath:db/migration", "classpath:db/local").load().migrate();
        var jdbc = JdbcClient.create(source);
        String suffix = Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits());
        String caseId = "C-" + suffix;
        long caseKey = jdbc.sql("""
            WITH fixture AS (
                INSERT INTO bookings (booking_code, customer_id, hotel_id, supplier_booking_id,
                    check_in_date, check_out_date, adults, original_total_amount)
                SELECT :booking, customer_id, hotel_id, :booking, check_in_date, check_out_date, adults, original_total_amount
                FROM bookings WHERE booking_code='YB-001' RETURNING id
            )
            INSERT INTO cases (case_code, booking_id, status, priority)
            SELECT :code, id, 'OPEN', 'URGENT' FROM fixture RETURNING id
            """).param("code", caseId).param("booking", "POST-" + suffix).query(Long.class).single();

        var calls = new AtomicInteger();
        byte[] docx = {'P', 'K', 3, 4, 5};
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/case-postprocessing", exchange -> {
            try (exchange) {
                assertEquals("Bearer integration-token", exchange.getRequestHeaders().getFirst("Authorization"));
                exchange.getRequestBody().readAllBytes();
                var draft = exchange.getRequestURI().getPath().endsWith("/drafts");
                boolean reject = !draft && calls.getAndIncrement() == 0;
                byte[] payload = draft ? """
                    {"draft_text":"원예약 후속 처리","follow_up_suggestions":[
                    {"description":"환불 확인","evidence_refs":[],"policy_refs":[]}]}
                    """.getBytes(StandardCharsets.UTF_8) : docx;
                var type = draft ? "application/json" : CasePostprocessingService.DOCX.toString();
                exchange.getResponseHeaders().set("Content-Type", type);
                exchange.sendResponseHeaders(reject ? 503 : 200, payload.length);
                exchange.getResponseBody().write(payload);
            }
        });
        server.start();
        try {
            var mapper = JsonMapper.builder().build();
            var service = new CasePostprocessingService(jdbc, mapper, new CaseAccess(jdbc),
                    new CasePostprocessingSnapshot(jdbc, mapper), new JdbcTransactionManager(source),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "integration-token");
            var counselor = (java.security.Principal) () -> "demo-counselor-001";
            UUID id = UUID.randomUUID();
            assertThrows(ResponseStatusException.class, () -> service.latest(() -> "demo-customer-001", caseId));
            assertEquals(id, service.draft(counselor, caseId, id).draftId());
            assertEquals(id, service.draft(counselor, caseId, UUID.randomUUID()).draftId());
            var submission = new CasePostprocessingService.Submission("검토한 원예약 후속 처리", List.of(
                    new CasePostprocessingService.TaskChoice(0, "ORIGINAL_FOLLOWUP", true,
                            OffsetDateTime.parse("2026-11-28T12:00:00+09:00"), true)));
            assertThrows(ResponseStatusException.class, () -> service.finalizeDraft(counselor, caseId, id, submission));
            assertEquals(0L, taskCount(jdbc, caseKey));
            var saved = service.finalizeDraft(counselor, caseId, id, submission);
            assertEquals(1, saved.taskIds().size());
            assertEquals(saved, service.finalizeDraft(counselor, caseId, id, submission));
            assertEquals(2, calls.get());
            assertEquals(1L, taskCount(jdbc, caseKey));
            assertArrayEquals(docx, service.download(counselor, caseId, id));
            assertThrows(ResponseStatusException.class, () -> service.finalizeDraft(counselor, caseId, id,
                    new CasePostprocessingService.Submission("다른 내용", submission.tasks())));
        } finally {
            server.stop(0);
        }
    }

    private static long taskCount(JdbcClient jdbc, long caseKey) {
        return jdbc.sql("SELECT count(*) FROM case_tasks WHERE case_id=:id AND task_type='ORIGINAL_FOLLOWUP'")
                .param("id", caseKey).query(Long.class).single();
    }

    private static DriverManagerDataSource isolatedSource() throws Exception {
        String expected = "jdbc:postgresql://127.0.0.1:25441/csro_postprocessing_test";
        String url = System.getenv("POSTPROCESSING_TEST_DB_URL");
        String password = System.getenv("POSTPROCESSING_TEST_DB_PASSWORD");
        if (!expected.equals(url) || password == null || password.isBlank())
            throw new IllegalStateException("The postprocessing test requires its dedicated loopback database.");
        var dataSource = new DriverManagerDataSource(url, "yb_postprocessing_test", password);
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT current_database(), current_user")) {
            if (!rows.next() || !"csro_postprocessing_test".equals(rows.getString(1))
                    || !"yb_postprocessing_test".equals(rows.getString(2)))
                throw new IllegalStateException("Postprocessing test database identity does not match.");
        }
        return dataSource;
    }
}
