package com.csro.backend.incidents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.csro.backend.reconfirmation.BookingReconfirmationEvidenceService;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** Real PostgreSQL receipt transactions; no application startup or external HTTP is needed. */
@EnabledIfEnvironmentVariable(named = "INCIDENT_REPORT_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IncidentReportIntegrationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-25T03:00:00Z"), ZoneOffset.UTC);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private JdbcClient jdbc;
    private DataSourceTransactionManager manager;
    private IncidentRecheckJobs jobs;
    private IncidentReportService service;
    private BookingReconfirmationEvidenceService evidence;
    private long bookingId;
    private long ownerId;
    private long counselorId;
    private String bookingCode;
    private Principal owner;
    private Principal foreign;
    private Principal counselor;
    private Principal inactive;

    @BeforeAll
    void verifyConfiguredAndActualDatabaseIdentityBeforeFlyway() {
        var settings = IncidentTestDatabase.validate(System.getenv());
        var dataSource = new DriverManagerDataSource(settings.url(), settings.user(), settings.password());
        jdbc = JdbcClient.create(dataSource);
        var identity = jdbc.sql("SELECT current_database() AS database_name, current_user AS user_name")
                .query((rs, row) -> List.of(rs.getString("database_name"), rs.getString("user_name"))).single();
        assertEquals(List.of(IncidentTestDatabase.DATABASE, IncidentTestDatabase.USER), identity);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration", "classpath:db/local")
                .cleanDisabled(true).load().migrate();
        manager = new DataSourceTransactionManager(dataSource);
    }

    @BeforeEach
    void newFixtureWithoutDeletingOrResettingPreviousData() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ownerId = user("owner-" + suffix, "CUSTOMER", true);
        owner = () -> "owner-" + suffix;
        user("foreign-" + suffix, "CUSTOMER", true);
        foreign = () -> "foreign-" + suffix;
        counselorId = user("counselor-" + suffix, "COUNSELOR", true);
        counselor = () -> "counselor-" + suffix;
        user("inactive-" + suffix, "CUSTOMER", false);
        inactive = () -> "inactive-" + suffix;
        long hotel = jdbc.sql("""
                INSERT INTO hotels(hotel_code,name,data_source) VALUES (:code,'Incident test hotel','SIMULATED') RETURNING id
                """).param("code", "H-INC-" + suffix).query(Long.class).single();
        bookingCode = "YB-INC-" + suffix;
        bookingId = jdbc.sql("""
                INSERT INTO bookings(booking_code,customer_id,hotel_id,supplier_code,supplier_booking_id,
                    supplier_status,check_in_date,check_out_date,adults,rooms,original_total_amount,currency)
                VALUES (:code,:customer,:hotel,'MOCK_SUPPLIER',:supplier,'CONFIRMED',DATE '2026-11-25',
                    DATE '2026-11-28',2,1,450000,'KRW') RETURNING id
                """).param("code", bookingCode).param("customer", ownerId).param("hotel", hotel)
                .param("supplier", "SUP-INC-" + suffix).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO booking_nights(booking_id,stay_date)
                SELECT :id, DATE '2026-11-25'+n FROM generate_series(0,2) n
                """).param("id", bookingId).update();
        evidence = mock(BookingReconfirmationEvidenceService.class);
        jobs = spy(new IncidentRecheckJobs(evidence, jdbc, manager, CLOCK, IncidentRecheckJobs.RetryPolicy.defaults()));
        service = new IncidentReportService(jdbc, manager, jobs, CLOCK);
    }

    @Test
    void initialReceiptCommitsRequiredWorkAndUnverifiedMessageWithoutChangingRoomFacts() {
        String before = roomFacts();
        var receipt = submit("first", false, "  호텔에서 예약을 찾지 못합니다.  ");
        assertEquals("PENDING", receipt.verificationStatus());
        assertEquals(bookingCode, receipt.bookingId());
        assertTrue(receipt.incidentCase().caseId().matches("C-[0-9]{3,38}"));
        assertEquals(new IncidentReportService.CaseReceipt(receipt.incidentCase().caseId(), true, "OPEN", "URGENT"),
                receipt.incidentCase());
        assertEquals(OffsetDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC), receipt.receivedAt());
        assertEquals(1L, rows("incident_reports"));
        assertEquals(1L, rows("cases"));
        assertEquals(1L, rows("case_tasks"));
        assertEquals(1L, rows("case_actions"));
        assertEquals(1L, rows("jobs"));
        assertEquals(1L, messageCount());
        assertEquals("호텔에서 예약을 찾지 못합니다.", jdbc.sql("SELECT message FROM incident_reports WHERE booking_id=:id")
                .param("id", bookingId).query(String.class).single());
        assertFalse(jdbc.sql("SELECT at_hotel FROM incident_reports WHERE booking_id=:id")
                .param("id", bookingId).query(Boolean.class).single());
        assertEquals("CONTACT_CUSTOMER:TODO:true", jdbc.sql("""
                SELECT task_type||':'||status||':'||is_required FROM case_tasks WHERE booking_id=:id
                """).param("id", bookingId).query(String.class).single());
        assertTrue(jdbc.sql("SELECT due_at IS NULL FROM case_tasks WHERE booking_id=:id")
                .param("id", bookingId).query(Boolean.class).single());
        assertEquals(1L, jdbc.sql("""
                SELECT count(*) FROM case_actions a JOIN jobs j ON a.details->>'recheckJobCode'=j.job_code
                WHERE a.booking_id=:id AND j.booking_id=:id AND a.action_type='CUSTOMER_REPORT_RECEIVED'
                """).param("id", bookingId).query(Long.class).single());
        Map<?, ?> wire = JSON.readValue(JSON.writeValueAsString(receipt), Map.class);
        assertEquals(java.util.Set.of("reportId","bookingId","chatMessageId","case","verificationStatus","receivedAt"), wire.keySet());
        assertEquals(before, roomFacts());
        verifyNoInteractions(evidence);
    }

    @Test
    void normalizedUnicodeReplayIsImmutableAndChangedBodyConflictsWithoutNewWrites() {
        String message = "🌸".repeat(1000);
        var first = submit("unicode", false, "\n " + message + " \n");
        assertEquals(first, submit("unicode", false, message));
        assertEquals(1000, jdbc.sql("SELECT char_length(message) FROM incident_reports WHERE booking_id=:id")
                .param("id", bookingId).query(Integer.class).single());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> submit("unicode", true, message)).getStatusCode().value());
        assertEquals(1L, rows("incident_reports"));
        assertEquals(1L, rows("case_tasks"));
        assertEquals(1L, rows("case_actions"));
        verify(jobs, times(1)).enqueue(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void waitingCaseKeepsAssigneeAndStatusAndGetsNewRequiredVerificationTask() {
        long caseId = existingCase("WAITING");
        jdbc.sql("""
                INSERT INTO case_tasks(booking_id,case_id,task_type,status,is_required)
                VALUES (:booking,:case,'CONTACT_CUSTOMER','TODO',true)
                """).param("booking", bookingId).param("case", caseId).update();
        var receipt = submit("waiting", true, "현장에서 다시 확인해 주세요");
        assertFalse(receipt.incidentCase().created());
        assertEquals("WAITING", receipt.incidentCase().status());
        assertEquals("URGENT", receipt.incidentCase().priority());
        assertEquals(counselorId, jdbc.sql("SELECT assignee_id FROM cases WHERE id=:id")
                .param("id", caseId).query(Long.class).single());
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM case_tasks WHERE case_id=:id AND task_type='CONTACT_CUSTOMER'")
                .param("id", caseId).query(Long.class).single());
        assertEquals(1L, jdbc.sql("""
                SELECT count(*) FROM case_tasks WHERE case_id=:id AND task_type='VERIFY_HOTEL'
                    AND status='TODO' AND is_required AND due_at=:now
                """).param("id", caseId).param("now", OffsetDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC))
                .query(Long.class).single());
    }

    @Test
    void aForeignActiveJobStaysUnchangedAndTheNewCaseGetsHumanVerificationWork() {
        String jobCode = "FOREIGN-" + UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO jobs(job_code,booking_id,kind,trigger_type,status,parameters,result_payload,attempt_count)
                VALUES (:code,:booking,'RECHECK','OTHER_SOURCE','RUNNING','{}'::jsonb,
                    '{"preserve":"original"}'::jsonb,2)
                """).param("code", jobCode).param("booking", bookingId).update();
        String original = jdbc.sql("SELECT to_jsonb(j)::text FROM jobs j WHERE job_code=:code")
                .param("code", jobCode).query(String.class).single();
        var receipt = submit("foreign-job", true, "현장 확인 필요");
        assertEquals(original, jdbc.sql("SELECT to_jsonb(j)::text FROM jobs j WHERE job_code=:code")
                .param("code", jobCode).query(String.class).single());
        assertEquals(1L, rows("jobs"));
        assertEquals(2L, rows("case_tasks"));
        assertEquals(List.of("CONTACT_CUSTOMER", "VERIFY_HOTEL"), jdbc.sql("""
                SELECT task_type FROM case_tasks WHERE booking_id=:booking AND is_required AND status='TODO' ORDER BY id
                """).param("booking", bookingId).query(String.class).list());
        assertTrue(receipt.incidentCase().created());
        assertEquals(jobCode, jdbc.sql("SELECT details->>'recheckJobCode' FROM case_actions WHERE booking_id=:id")
                .param("id", bookingId).query(String.class).single());
        verifyNoInteractions(evidence);
    }

    @Test
    void replayAfterClosureReturnsFirstSnapshotButNewKeyCreatesAnotherActiveCase() {
        var first = submit("closed", true, "입실 문제");
        jdbc.sql("""
                UPDATE cases SET status='CLOSED',closed_at=:now,close_reason='TEST_RESOLVED'
                WHERE case_code=:code
                """).param("now", OffsetDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC))
                .param("code", first.incidentCase().caseId()).update();
        String snapshot = receiptState();
        assertEquals(first, submit("closed", true, "입실 문제"));
        assertEquals(snapshot, receiptState());
        var next = submit("after-closed", true, "새 입실 문제");
        assertTrue(next.incidentCase().created());
        assertNotEquals(first.incidentCase().caseId(), next.incidentCase().caseId());
        assertEquals(2L, rows("cases"));
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM cases WHERE booking_id=:id AND status<>'CLOSED'")
                .param("id", bookingId).query(Long.class).single());
    }

    @Test
    void authorizationAndMissingOrForeignBookingsHaveNoSideEffects() {
        assertStatus(401, null, bookingCode);
        assertStatus(401, () -> "unknown-subject", bookingCode);
        assertStatus(401, inactive, bookingCode);
        assertStatus(403, counselor, bookingCode);
        var foreignError = assertStatus(404, foreign, bookingCode);
        var missingError = assertStatus(404, owner, "MISSING-BOOKING");
        assertEquals(foreignError.getReason(), missingError.getReason());
        assertEquals(0L, rows("incident_reports"));
        assertEquals(0L, rows("cases"));
        assertEquals(0L, rows("jobs"));
        verifyNoInteractions(jobs, evidence);
    }

    @Test
    void failureAfterQueueInsertRollsBackReceiptCaseTaskMessageAndJobTogether() {
        String before = roomFacts();
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            call.callRealMethod();
            assertEquals(1L, rows("jobs"));
            throw new IllegalStateException("controlled queue completion failure");
        }).when(jobs).enqueue(anyLong(), anyLong(), anyLong(), anyString());
        assertThrows(IllegalStateException.class, () -> submit("rollback", true, "입실 문제"));
        for (String table : List.of("cases","case_tasks","incident_reports","case_actions","jobs")) {
            assertEquals(0L, rows(table), table);
        }
        assertEquals(0L, messageCount());
        assertEquals(before, roomFacts());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentRetriesOrReportsKeepOneActiveCaseAndOneActiveRecheck(boolean sameKey) throws Exception {
        try (var executor = Executors.newFixedThreadPool(4)) {
            var start = new CountDownLatch(1);
            var results = new ArrayList<java.util.concurrent.Future<IncidentReportService.Receipt>>();
            for (int i = 0; i < 4; i++) {
                String key = sameKey ? "parallel" : "parallel-" + i;
                results.add(executor.submit(() -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return submit(key, true, "동시 신고");
                }));
            }
            start.countDown();
            var first = results.getFirst().get(15, TimeUnit.SECONDS);
            for (var result : results) {
                var receipt = result.get(15, TimeUnit.SECONDS);
                assertEquals(first.incidentCase().caseId(), receipt.incidentCase().caseId());
                if (sameKey) assertEquals(first, receipt);
            }
        }
        assertEquals(1L, rows("cases"));
        assertEquals(1L, rows("jobs"));
        assertEquals(sameKey ? 1L : 4L, rows("incident_reports"));
        assertEquals(sameKey ? 1L : 4L, rows("case_tasks"));
        assertEquals(sameKey ? 1L : 4L, messageCount());
    }

    private IncidentReportService.Receipt submit(String key, boolean atHotel, String message) {
        return service.submit(owner, bookingCode, key, "CHECKIN_FAILED", atHotel, message);
    }

    private ResponseStatusException assertStatus(int expected, Principal principal, String code) {
        var error = assertThrows(ResponseStatusException.class,
                () -> service.submit(principal, code, "auth", "CHECKIN_FAILED", true, "입실 문제"));
        assertEquals(expected, error.getStatusCode().value());
        return error;
    }

    private long user(String subject, String role, boolean active) {
        return jdbc.sql("""
                INSERT INTO users(subject_key,display_name,role,is_active) VALUES (:subject,'Test user',:role,:active) RETURNING id
                """).param("subject", subject).param("role", role).param("active", active).query(Long.class).single();
    }

    private long existingCase(String status) {
        return jdbc.sql("""
                WITH candidate AS (SELECT nextval(pg_get_serial_sequence('cases','id')) AS id)
                INSERT INTO cases(id,case_code,booking_id,status,priority,assignee_id)
                SELECT id,'C-'||lpad(id::text,greatest(3,length(id::text)),'0'),:booking,:status,'NORMAL',:assignee
                FROM candidate RETURNING id
                """).param("booking", bookingId).param("status", status).param("assignee", counselorId)
                .query(Long.class).single();
    }

    private long rows(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE booking_id=:id")
                .param("id", bookingId).query(Long.class).single();
    }

    private long messageCount() {
        return jdbc.sql("SELECT count(*) FROM chat_messages WHERE case_id IN (SELECT id FROM cases WHERE booking_id=:id)")
                .param("id", bookingId).query(Long.class).single();
    }

    private String roomFacts() {
        return jdbc.sql("""
                SELECT jsonb_build_object('supplier',b.supplier_status,
                    'nights',(SELECT jsonb_agg(to_jsonb(n) ORDER BY stay_date) FROM booking_nights n WHERE booking_id=b.id),
                    'evidence',(SELECT count(*) FROM evidence_records WHERE booking_id=b.id),
                    'risk',(SELECT count(*) FROM risk_assessments WHERE hotel_id=b.hotel_id))::text
                FROM bookings b WHERE b.id=:id
                """).param("id", bookingId).query(String.class).single();
    }

    private String receiptState() {
        return jdbc.sql("""
                SELECT jsonb_build_object('reports',(SELECT jsonb_agg(to_jsonb(r) ORDER BY id) FROM incident_reports r WHERE booking_id=:id),
                    'cases',(SELECT jsonb_agg(to_jsonb(c) ORDER BY id) FROM cases c WHERE booking_id=:id),
                    'jobs',(SELECT jsonb_agg(to_jsonb(j) ORDER BY id) FROM jobs j WHERE booking_id=:id),
                    'tasks',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM case_tasks t WHERE booking_id=:id),
                    'audit',(SELECT jsonb_agg(to_jsonb(a) ORDER BY id) FROM case_actions a WHERE booking_id=:id))::text
                """).param("id", bookingId).query(String.class).single();
    }
}
