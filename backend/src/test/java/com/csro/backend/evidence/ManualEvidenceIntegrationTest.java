package com.csro.backend.evidence;

import static org.junit.jupiter.api.Assertions.*;

import com.csro.backend.evidence.ManualEvidenceService.Claim;
import java.security.Principal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

/**
 * Scenario 3 on real V1 rows: a counselor's direct hotel call follows the automated night rule, keeps the case
 * waiting while any night is unsettled and completes VERIFY_HOTEL with the new evidence once every night is a fact.
 */
@EnabledIfEnvironmentVariable(named = "REPLACEMENT_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManualEvidenceIntegrationTest {
    private static final String URL = "jdbc:postgresql://127.0.0.1:25444/yeohaengbom_replacements_test";
    private static final String DATABASE = "yeohaengbom_replacements_test";
    private static final String USER = "yb_replacement_test";
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-09T10:20:00+09:00");
    private static final Principal COUNSELOR = () -> "demo-counselor-001";
    private static final Principal CUSTOMER = () -> "demo-customer-001";
    private static final LocalDate FIRST = LocalDate.of(2026, 11, 10), SECOND = FIRST.plusDays(1), THIRD = FIRST.plusDays(2);
    private static final AtomicInteger CASE_NUMBER = new AtomicInteger(1400000 + (int) (System.nanoTime() & 65535));
    private JdbcClient jdbc;
    private ManualEvidenceService service;
    private long booking, caseKey;
    private String caseCode;

    @BeforeAll
    void checkEndpointAndActualIdentityBeforeFlyway() throws SQLException {
        var environment = System.getenv();
        String password = environment.get("REPLACEMENT_TEST_DB_PASSWORD");
        if (!URL.equals(environment.get("REPLACEMENT_TEST_DB_URL")) || !USER.equals(environment.get("REPLACEMENT_TEST_DB_USER"))
                || password == null || password.isBlank())
            throw new IllegalStateException("Exact dedicated replacement test database settings are required before datasource creation");
        var source = new DriverManagerDataSource(URL, USER, password);
        try (var connection = source.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT current_database(), current_user")) {
            if (!result.next() || !DATABASE.equals(result.getString(1)) || !USER.equals(result.getString(2)))
                throw new IllegalStateException("Dedicated replacement test database identity mismatch before Flyway");
        }
        Flyway.configure().dataSource(source).locations("classpath:db/migration", "classpath:db/local")
                .cleanDisabled(true).load().migrate();
        jdbc = JdbcClient.create(source);
        service = new ManualEvidenceService(jdbc, new DataSourceTransactionManager(source));
    }

    @BeforeEach
    void createIncidentCaseWithOpenHotelCheckWithoutDeletingRows() {
        String suffix = UUID.randomUUID().toString().substring(0, 12);
        booking = jdbc.sql("""
                INSERT INTO bookings (booking_code,customer_id,hotel_id,supplier_booking_id,check_in_date,
                    check_out_date,adults,rooms,original_total_amount,created_at,updated_at)
                SELECT :code,u.id,h.id,:supplier,DATE '2026-11-10',DATE '2026-11-13',2,1,450000,:at,:at
                FROM users u CROSS JOIN hotels h WHERE u.subject_key='demo-customer-001' AND h.hotel_code='HOTEL-001' RETURNING id
                """).param("code", "YB-E-" + suffix).param("supplier", "SUP-E-" + suffix).param("at", AT)
                .query(Long.class).single();
        jdbc.sql("INSERT INTO booking_nights (booking_id,stay_date) SELECT :id,DATE '2026-11-10'+n FROM generate_series(0,2)n")
                .param("id", booking).update();
        caseCode = "C-" + CASE_NUMBER.incrementAndGet();
        caseKey = jdbc.sql("INSERT INTO cases (case_code,booking_id,status,priority) VALUES (:code,:booking,'OPEN','URGENT') RETURNING id")
                .param("code", caseCode).param("booking", booking).query(Long.class).single();
        // The incident recheck path leaves this task after the hotel did not answer within the deadline.
        jdbc.sql("INSERT INTO case_tasks (case_id,booking_id,task_type,status,is_required,due_at) VALUES (:caseId,:booking,'VERIFY_HOTEL','TODO',true,:at)")
                .param("caseId", caseKey).param("booking", booking).param("at", AT).update();
    }

    @Test
    void insufficientCallWaitsAndACompleteCallSettlesEveryNightAndTheHotelCheck() {
        var partial = service.record(COUNSELOR, caseCode, "call-1", "11/10 객실 없음, 나머지는 내일 회신 예정",
                List.of(new Claim(FIRST, "UNAVAILABLE"), new Claim(SECOND, "UNKNOWN")));
        assertEquals("WAITING", partial.caseStatus());
        assertFalse(partial.verifyHotelTaskCompleted());
        assertEquals(List.of("ACCEPTED", "PENDING_REVIEW"), partial.nights().stream().map(ManualEvidenceService.NightResult::reviewStatus).toList());
        assertEquals("VERIFIED|UNAVAILABLE", night(FIRST));
        assertEquals("UNCONFIRMED|UNKNOWN", night(SECOND));
        assertEquals("WAITING", caseStatus());
        assertEquals("TODO", hotelTask());

        assertEquals(partial, service.record(COUNSELOR, caseCode, "call-1", "11/10 객실 없음, 나머지는 내일 회신 예정",
                List.of(new Claim(SECOND, "UNKNOWN"), new Claim(FIRST, "UNAVAILABLE"))));
        assertCode(409, "IDEMPOTENCY_KEY_REUSED", () -> service.record(COUNSELOR, caseCode, "call-1", "다른 내용",
                List.of(new Claim(FIRST, "UNAVAILABLE"))));
        assertEquals(1L, evidenceCount());

        var complete = service.record(COUNSELOR, caseCode, "call-2", "11/11·11/12 객실 제공 가능 확인",
                List.of(new Claim(SECOND, "AVAILABLE"), new Claim(THIRD, "AVAILABLE")));
        assertEquals("IN_PROGRESS", complete.caseStatus());
        assertTrue(complete.verifyHotelTaskCompleted());
        assertEquals("VERIFIED|AVAILABLE", night(SECOND));
        assertEquals("VERIFIED|AVAILABLE", night(THIRD));
        assertEquals("DONE", hotelTask());
        assertEquals(complete.evidenceId(), "EV-" + jdbc.sql("SELECT completion_evidence_id FROM case_tasks WHERE case_id=:id AND task_type='VERIFY_HOTEL'")
                .param("id", caseKey).query(Long.class).single());
    }

    @Test
    void contradictingAVerifiedNightBecomesAConflictAndKeepsTheCaseWaiting() {
        service.record(COUNSELOR, caseCode, "all-known", "전 숙박일 확인",
                List.of(new Claim(FIRST, "UNAVAILABLE"), new Claim(SECOND, "AVAILABLE"), new Claim(THIRD, "AVAILABLE")));
        assertEquals("DONE", hotelTask());
        var contradicted = service.record(COUNSELOR, caseCode, "correction", "11/10도 제공 가능하다고 정정",
                List.of(new Claim(FIRST, "AVAILABLE")));
        assertEquals("CONFLICT", contradicted.nights().getFirst().factStatus());
        assertEquals("CONFLICT|UNAVAILABLE", night(FIRST));
        assertEquals("WAITING", caseStatus());
    }

    @Test
    void onlyCounselorsRecordOriginalNightsOnOpenCases() {
        assertCode(403, "FORBIDDEN_ROLE", () -> service.record(CUSTOMER, caseCode, "customer", "메모", List.of(new Claim(FIRST, "AVAILABLE"))));
        assertCode(422, "STAY_DATE_OUT_OF_RANGE", () -> service.record(COUNSELOR, caseCode, "outside", "메모", List.of(new Claim(THIRD.plusDays(1), "AVAILABLE"))));
        assertCode(400, "INVALID_EVIDENCE_REQUEST", () -> service.record(COUNSELOR, caseCode, "dup", "메모",
                List.of(new Claim(FIRST, "AVAILABLE"), new Claim(FIRST, "UNKNOWN"))));
        jdbc.sql("UPDATE cases SET status='CLOSED',closed_at=now(),close_reason='TEST_COMPLETED' WHERE id=:id").param("id", caseKey).update();
        assertCode(409, "CASE_CLOSED", () -> service.record(COUNSELOR, caseCode, "closed", "메모", List.of(new Claim(FIRST, "AVAILABLE"))));
        assertEquals(0L, evidenceCount());
    }

    private String night(LocalDate date) {
        return jdbc.sql("SELECT fact_status || '|' || fulfillment_status FROM booking_nights WHERE booking_id=:id AND stay_date=:date")
                .param("id", booking).param("date", date).query(String.class).single();
    }

    private String caseStatus() { return jdbc.sql("SELECT status FROM cases WHERE id=:id").param("id", caseKey).query(String.class).single(); }
    private String hotelTask() { return jdbc.sql("SELECT status FROM case_tasks WHERE case_id=:id AND task_type='VERIFY_HOTEL'").param("id", caseKey).query(String.class).single(); }
    private long evidenceCount() { return jdbc.sql("SELECT count(*) FROM evidence_records WHERE booking_id=:id").param("id", booking).query(Long.class).single(); }

    private static void assertCode(int status, String code, Runnable command) {
        var failure = assertThrows(ResponseStatusException.class, command::run);
        assertEquals(status, failure.getStatusCode().value());
        assertEquals(code, failure.getReason());
    }
}
