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

    @Test
    void confirmsDueAirbnbBookingOnceAndCompletesItsTask() throws Exception {
        var source = isolatedSource();
        Flyway.configure().dataSource(source).locations("classpath:db/migration", "classpath:db/local").load().migrate();
        var jdbc = JdbcClient.create(source);
        String suffix = Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits());
        String caseCode = "C-" + suffix;
        String request = "ADB-" + suffix;
        long caseId = jdbc.sql("""
                WITH original AS (
                    INSERT INTO bookings (booking_code, customer_id, hotel_id, supplier_booking_id,
                        check_in_date, check_out_date, adults, original_total_amount)
                    SELECT :code, customer_id, hotel_id, :code, check_in_date, check_out_date, adults, original_total_amount
                    FROM bookings WHERE booking_code='YB-001' RETURNING id
                )
                INSERT INTO cases (case_code, booking_id) SELECT :code,id FROM original RETURNING id
                """).param("code",caseCode).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO bookings (booking_code, customer_id, hotel_id, supplier_code, supplier_booking_id,
                    check_in_date, check_out_date, adults, original_total_amount)
                SELECT :request,b.customer_id,b.hotel_id,'AIRBNB_DEMO',:request,
                    b.check_in_date,b.check_out_date,b.adults,b.original_total_amount
                FROM bookings b JOIN cases c ON c.booking_id=b.id WHERE c.id=:case
                """).param("request",request).param("case",caseId).update();
        jdbc.sql("""
                WITH search AS (
                    INSERT INTO jobs (job_code, booking_id, case_id, kind, trigger_type, status, parameters)
                    SELECT :request, booking_id,id,'QUOTE_SEARCH','COUNSELOR','COMPLETED','{}'
                    FROM cases WHERE id=:case RETURNING id
                ), quote AS (
                    INSERT INTO alternative_quotes (quote_code,search_job_id,hotel_id,room_type_code,rate_plan_code,
                        check_in_date,check_out_date,adults,rooms,base_amount,mandatory_fees_amount,total_amount,
                        distance_meters,available_room_count,max_adults,cancellation_policy,checked_at,expires_at,
                        source_payload,case_id,property_name,room_name)
                    SELECT :request,search.id,b.hotel_id,'DEMO','DEMO',b.check_in_date,b.check_out_date,
                        b.adults,b.rooms,100,0,100,0,1,b.adults,'DEMO',now(),now()+interval '1 hour',
                        '{}',c.id,'Demo','Demo'
                    FROM search CROSS JOIN cases c JOIN bookings b ON b.id=c.booking_id WHERE c.id=:case
                    RETURNING id
                ), offer AS (
                    INSERT INTO offers (offer_code,case_id,sent_by,sent_at,idempotency_key,request_hash)
                    SELECT :request,:case,u.id,now(),:request,repeat('a',64)
                    FROM users u WHERE u.subject_key='demo-counselor-001' RETURNING id,sent_by
                ), item AS (
                    INSERT INTO offer_items (offer_id,quote_id,display_order,case_id)
                    SELECT offer.id,quote.id,1,:case FROM offer CROSS JOIN quote RETURNING offer_id,quote_id
                ), selection AS (
                    INSERT INTO alternative_selections (selection_code,case_id,offer_id,quote_id,selected_by,
                        selected_at,idempotency_key,request_hash)
                    SELECT :request,c.id,item.offer_id,item.quote_id,b.customer_id,now(),:request,repeat('a',64)
                    FROM item CROSS JOIN cases c JOIN bookings b ON b.id=c.booking_id WHERE c.id=:case RETURNING id
                )
                INSERT INTO replacement_requests (request_code,selection_id,idempotency_key,request_hash,
                    status,expected_amount,confirmed_amount,supplier_booking_id,request_payload,response_payload,
                    confirmed_at,case_id,requested_by)
                SELECT :request,selection.id,:request,repeat('a',64),'CONFIRMED',100,100,:request,'{}',
                    '{"simulation":true}',now()+interval '1 hour',:case,offer.sent_by
                FROM selection CROSS JOIN offer
                """).param("request",request).param("case",caseId).update();
        jdbc.sql("""
                INSERT INTO case_tasks (case_id,booking_id,task_type)
                SELECT id,booking_id,'CHECKIN_CONFIRM' FROM cases WHERE id=:case
                """).param("case",caseId).update();

        try (var context = new org.springframework.context.support.StaticApplicationContext()) {
            context.getBeanFactory().registerSingleton("localChatDemoAuthentication", new Object());
            var service = new LocalAlternativeBooking(jdbc, new CaseAccess(jdbc), context);
            var tx = new org.springframework.transaction.support.TransactionTemplate(new JdbcTransactionManager(source));
            org.junit.jupiter.api.Assertions.assertFalse(service.dueCheckins().contains(caseCode));
            tx.executeWithoutResult(status -> service.checkIn(caseCode));
            jdbc.sql("UPDATE replacement_requests SET confirmed_at=now()-interval '6 seconds' WHERE request_code=:request")
                    .param("request",request).update();
            org.junit.jupiter.api.Assertions.assertTrue(service.dueCheckins().contains(caseCode));
            tx.executeWithoutResult(status -> service.checkIn(caseCode));
            tx.executeWithoutResult(status -> service.checkIn(caseCode));
            org.junit.jupiter.api.Assertions.assertFalse(service.dueCheckins().contains(caseCode));
        }
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM checkin_confirmations WHERE case_id=:case")
                .param("case",caseId).query(Long.class).single());
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM chat_messages WHERE case_id=:case AND message_code LIKE 'MSG-ADB-CHECKIN-%'")
                .param("case",caseId).query(Long.class).single());
        assertEquals("DONE", jdbc.sql("SELECT status FROM case_tasks WHERE case_id=:case AND task_type='CHECKIN_CONFIRM'")
                .param("case",caseId).query(String.class).single());
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
