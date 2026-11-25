package com.csro.backend.incidents;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** API-002: receipt and follow-up intent commit together; customer claims never become room facts. */
@Service
public class IncidentReportService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,100}");
    private final JdbcClient jdbc;
    private final IncidentRecheckJobs jobs;
    private final TransactionTemplate write;
    private final Clock clock;

    @Autowired
    public IncidentReportService(JdbcClient jdbc, PlatformTransactionManager manager, IncidentRecheckJobs jobs) {
        this(jdbc, manager, jobs, Clock.systemUTC());
    }

    public IncidentReportService(JdbcClient jdbc, PlatformTransactionManager manager,
                                 IncidentRecheckJobs jobs, Clock clock) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.clock = clock;
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Receipt submit(Principal principal, String bookingCode, String key, String issueType,
                          boolean atHotel, String message) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        }
        if (key == null || !KEY.matcher(key).matches() || !"CHECKIN_FAILED".equals(issueType)
                || message == null) throw error(HttpStatus.BAD_REQUEST, "INVALID_INCIDENT_REPORT");
        String normalized = message.strip();
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 1 || length > 1000 || normalized.codePoints().anyMatch(
                point -> point == 0 || point >= 0xD800 && point <= 0xDFFF)) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_INCIDENT_REPORT");
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("issueType", issueType);
        payload.put("atHotel", atHotel);
        payload.put("message", normalized);
        String hash = hash(JSON.writeValueAsBytes(payload));
        return write.execute(status -> accept(principal.getName(), bookingCode, key, issueType,
                atHotel, normalized, hash));
    }

    private Receipt accept(String subject, String bookingCode, String key, String issueType,
                           boolean atHotel, String message, String hash) {
        Account customer = jdbc.sql("SELECT id, role, is_active FROM users WHERE subject_key=:subject")
                .param("subject", subject).query((rs, row) -> new Account(rs.getLong("id"),
                        rs.getString("role"), rs.getBoolean("is_active")))
                .optional().orElseThrow(() -> error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED"));
        if (!customer.active()) throw error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        if (!"CUSTOMER".equals(customer.role())) throw error(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");

        long bookingId = jdbc.sql("""
                SELECT id FROM bookings WHERE booking_code=:code AND customer_id=:customer FOR UPDATE
                """).param("code", bookingCode).param("customer", customer.id()).query(Long.class)
                .optional().orElseThrow(() -> error(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND"));

        // Authorization and the booking lock precede replay; current case state follows replay.
        var existing = jdbc.sql("""
                SELECT request_hash, response_snapshot::text AS snapshot FROM incident_reports
                WHERE booking_id=:booking AND idempotency_key=:key
                """).param("booking", bookingId).param("key", key)
                .query((rs, row) -> new Replay(rs.getString("request_hash"), rs.getString("snapshot"))).optional();
        if (existing.isPresent()) {
            Replay replay = existing.orElseThrow();
            if (!replay.hash().equals(hash)) throw error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED");
            return JSON.readValue(replay.snapshot(), Receipt.class);
        }

        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        var active = jdbc.sql("""
                SELECT id, case_code, status FROM cases WHERE booking_id=:booking
                  AND status IN ('OPEN','IN_PROGRESS','WAITING') FOR UPDATE
                """).param("booking", bookingId).query((rs, row) -> new ActiveCase(rs.getLong("id"),
                        rs.getString("case_code"), rs.getString("status"))).optional();
        boolean created = active.isEmpty();
        ActiveCase incidentCase;
        if (created) {
            incidentCase = createCase(bookingId, now);
        } else {
            incidentCase = active.orElseThrow();
            jdbc.sql("UPDATE cases SET priority='URGENT', updated_at=:now, version=version+1 WHERE id=:id")
                    .param("now", now).param("id", incidentCase.id()).update();
        }
        String reportCode = "IR-" + UUID.randomUUID();
        String messageCode = "MSG-" + UUID.randomUUID();
        Receipt receipt = new Receipt(reportCode, bookingCode, messageCode,
                new CaseReceipt(incidentCase.code(), created, incidentCase.status(), "URGENT"), "PENDING", now);
        long reportId = jdbc.sql("""
                INSERT INTO incident_reports (report_code,booking_id,case_id,customer_id,issue_type,at_hotel,
                    message,idempotency_key,request_hash,response_snapshot,received_at)
                VALUES (:code,:booking,:case,:customer,:issue,:atHotel,:message,:key,:hash,CAST(:snapshot AS jsonb),:now)
                RETURNING id
                """).param("code", reportCode).param("booking", bookingId).param("case", incidentCase.id())
                .param("customer", customer.id()).param("issue", issueType).param("atHotel", atHotel)
                .param("message", message).param("key", key).param("hash", hash)
                .param("snapshot", JSON.writeValueAsString(receipt)).param("now", now).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO chat_messages (message_code,case_id,sender_user_id,sender_type,message_type,
                    body,incident_report_id,sent_at)
                VALUES (:code,:case,:customer,'CUSTOMER','TEXT',:body,:report,:now)
                """).param("code", messageCode).param("case", incidentCase.id()).param("customer", customer.id())
                .param("body", message).param("report", reportId).param("now", now).update();
        jdbc.sql("""
                INSERT INTO case_tasks (booking_id,case_id,task_type,status,is_required,due_at,created_at,updated_at)
                VALUES (:booking,:case,:type,'TODO',true,CASE WHEN :contact THEN NULL ELSE :now END,:now,:now)
                """).param("booking", bookingId).param("case", incidentCase.id())
                .param("type", created ? "CONTACT_CUSTOMER" : "VERIFY_HOTEL")
                .param("contact", created).param("now", now).update();
        String recheckJobCode = jobs.enqueue(bookingId, incidentCase.id(), reportId, bookingCode);
        String protocol = jdbc.sql("SELECT coalesce(parameters->>'protocol','') FROM jobs WHERE job_code=:code")
                .param("code", recheckJobCode).query(String.class).single();
        if (created && !"incident-recheck-v1".equals(protocol)) {
            // A foreign worker owns the active job. Keep that row untouched,
            // while giving this case an explicit human verification obligation.
            jdbc.sql("""
                    INSERT INTO case_tasks(booking_id,case_id,task_type,status,is_required,due_at,created_at,updated_at)
                    VALUES (:booking,:case,'VERIFY_HOTEL','TODO',true,:now,:now,:now)
                    """).param("booking", bookingId).param("case", incidentCase.id()).param("now", now).update();
        }
        jdbc.sql("""
                INSERT INTO case_actions (booking_id,case_id,actor_id,action_type,details,acted_at)
                VALUES (:booking,:case,:customer,'CUSTOMER_REPORT_RECEIVED',CAST(:details AS jsonb),:now)
                """).param("booking", bookingId).param("case", incidentCase.id()).param("customer", customer.id())
                .param("details", JSON.writeValueAsString(java.util.Map.of("reportId", reportCode,
                        "caseCreated", created, "atHotel", atHotel, "recheckJobCode", recheckJobCode)))
                .param("now", now).update();
        return receipt;
    }

    private ActiveCase createCase(long bookingId, OffsetDateTime now) {
        while (true) {
            var created = jdbc.sql("""
                    WITH candidate AS (SELECT nextval(pg_get_serial_sequence('cases','id')) AS id)
                    INSERT INTO cases (id,case_code,booking_id,status,priority,created_at,updated_at)
                    SELECT id,'C-' || lpad(id::text,greatest(3,length(id::text)),'0'),:booking,'OPEN','URGENT',:now,:now
                    FROM candidate ON CONFLICT DO NOTHING RETURNING id,case_code,status
                    """).param("booking", bookingId).param("now", now).query((rs, row) -> new ActiveCase(
                            rs.getLong("id"), rs.getString("case_code"), rs.getString("status"))).optional();
            if (created.isPresent()) return created.orElseThrow();
        }
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static ResponseStatusException error(HttpStatus status, String code) {
        return new ResponseStatusException(status, code);
    }

    public record Receipt(String reportId, String bookingId, String chatMessageId,
                          @JsonProperty("case") CaseReceipt incidentCase,
                          String verificationStatus, OffsetDateTime receivedAt) {}
    public record CaseReceipt(String caseId, boolean created, String status, String priority) {}
    private record Account(long id, String role, boolean active) {}
    private record ActiveCase(long id, String code, String status) {}
    private record Replay(String hash, String snapshot) {}
}
