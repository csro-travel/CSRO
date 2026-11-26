package com.csro.backend.closure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** API-017/008/018: check-in, required task completion and guarded case closure. No external HTTP. */
@Service
public class ClosureService {
    private static final String PROTOCOL = "case-closure-v1";
    private static final String CLOSE_REASON = "REPLACEMENT_CHECKED_IN";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcClient jdbc;
    private final TransactionTemplate write;
    private final Clock clock;

    @Autowired
    public ClosureService(JdbcClient jdbc, PlatformTransactionManager manager) {
        this(jdbc, manager, Clock.systemUTC());
    }

    public ClosureService(JdbcClient jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
        write = new TransactionTemplate(Objects.requireNonNull(manager));
    }

    /** API-017. The owner customer records one check-in for a CONFIRMED replacement booking. */
    public Checkin checkin(Principal principal, String requestId, String key) {
        if (requestId == null || !requestId.matches("BRQ-[0-9a-f]{64}")) throw bad("INVALID_REPLACEMENT_ID");
        validKey(key, "INVALID_CHECKIN_REQUEST");
        String hash = digest(JSON.writeValueAsString(Map.of("replacementBookingId", requestId)));
        return Objects.requireNonNull(write.execute(transaction -> {
            User actor = user(principal);
            if (!"CUSTOMER".equals(actor.role())) throw forbidden();
            Replacement observed = replacement(requestId).filter(r -> r.customerId() == actor.id())
                    .orElseThrow(() -> notFound("REPLACEMENT_BOOKING_NOT_FOUND"));
            Context context = lockCase(observed.caseCode());
            Optional<Checkin> replay = jdbc.sql("""
                    SELECT k.id,k.request_hash,k.checkin_at,r.request_code FROM checkin_confirmations k
                    JOIN replacement_requests r ON r.id=k.replacement_request_id AND r.case_id=k.case_id
                    WHERE k.case_id=:caseKey AND k.idempotency_key=:key
                    """).param("caseKey", context.caseKey()).param("key", key)
                    .query((rs, index) -> {
                        if (!hash.equals(rs.getString("request_hash"))) throw conflict("IDEMPOTENCY_KEY_REUSED");
                        return new Checkin("CHK-" + rs.getLong("id"), context.caseCode(), rs.getString("request_code"),
                                "CUSTOMER_CONFIRMED", rs.getObject("checkin_at", OffsetDateTime.class));
                    }).optional();
            if (replay.isPresent()) return replay.orElseThrow();
            if ("CLOSED".equals(context.status())) throw conflict("CASE_CLOSED");
            String status = jdbc.sql("SELECT status FROM replacement_requests WHERE id=:id")
                    .param("id", observed.id()).query(String.class).single();
            if (!"CONFIRMED".equals(status)) throw conflict("REPLACEMENT_NOT_CONFIRMED");
            boolean confirmed = jdbc.sql("""
                    SELECT EXISTS(SELECT 1 FROM checkin_confirmations WHERE replacement_request_id=:id
                        AND confirmation_status IN ('CUSTOMER_CONFIRMED','PROVIDER_VERIFIED'))
                    """).param("id", observed.id()).query(Boolean.class).single();
            if (confirmed) throw conflict("CHECKIN_ALREADY_CONFIRMED");
            OffsetDateTime now = now();
            long id = jdbc.sql("""
                    INSERT INTO checkin_confirmations (case_id,booking_id,target_type,replacement_request_id,source_type,
                        confirmation_status,confirmed_by,idempotency_key,request_hash,checkin_at,created_at)
                    VALUES (:caseKey,:booking,'REPLACEMENT',:replacement,'CUSTOMER','CUSTOMER_CONFIRMED',:actor,:key,:hash,:now,:now)
                    RETURNING id
                    """).param("caseKey", context.caseKey()).param("booking", context.bookingId())
                    .param("replacement", observed.id()).param("actor", actor.id()).param("key", key)
                    .param("hash", hash).param("now", now).query(Long.class).single();
            String checkinId = "CHK-" + id;
            jdbc.sql("""
                    UPDATE case_tasks SET status='DONE',completed_at=:now,completion_note=:note,updated_at=:now
                    WHERE case_id=:caseKey AND task_type='CHECKIN_CONFIRM' AND is_required AND status IN ('TODO','IN_PROGRESS')
                    """).param("now", now).param("note", "고객 입실 확인 " + checkinId)
                    .param("caseKey", context.caseKey()).update();
            message(context, "고객이 대체 숙소 입실을 확인했습니다. 원예약 후속 처리와 남은 필수 업무를 확인한 뒤 사건을 종료해 주세요.", now);
            action(context, actor.id(), "CHECKIN_CONFIRMED",
                    Map.of("protocol", PROTOCOL, "checkinConfirmationId", checkinId, "replacementBookingId", requestId), now);
            return new Checkin(checkinId, context.caseCode(), requestId, "CUSTOMER_CONFIRMED", now);
        }));
    }

    /** API-008 (reduced). A counselor completes one required task with a manual note. */
    public TaskCompletion completeTask(Principal principal, String caseId, String taskId, String note) {
        validCase(caseId);
        if (taskId == null || !taskId.matches("TASK-[1-9][0-9]{0,17}")) throw bad("INVALID_TASK_ID");
        if (note == null || note.isBlank() || note.length() > 1000) throw bad("INVALID_TASK_UPDATE");
        long taskKey = Long.parseLong(taskId.substring(5));
        return Objects.requireNonNull(write.execute(transaction -> {
            User actor = user(principal);
            if (!actor.counselor()) throw forbidden();
            Context context = lockCase(caseId);
            Task task = jdbc.sql("""
                    SELECT task_type,status,completion_note,completed_at FROM case_tasks
                    WHERE id=:id AND case_id=:caseKey FOR UPDATE
                    """).param("id", taskKey).param("caseKey", context.caseKey())
                    .query((rs, index) -> new Task(rs.getString("task_type"), rs.getString("status"),
                            rs.getString("completion_note"), rs.getObject("completed_at", OffsetDateTime.class)))
                    .optional().orElseThrow(() -> notFound("TASK_NOT_FOUND"));
            if ("DONE".equals(task.status())) {
                if (!note.equals(task.note())) throw conflict("TASK_ALREADY_COMPLETED");
                return new TaskCompletion(caseId, taskId, task.type(), "DONE", task.completedAt(), task.note());
            }
            if ("CLOSED".equals(context.status())) throw conflict("CASE_CLOSED");
            if ("CHECKIN_CONFIRM".equals(task.type())) throw conflict("CHECKIN_REQUIRED");
            OffsetDateTime now = now();
            jdbc.sql("UPDATE case_tasks SET status='DONE',completed_at=:now,completion_note=:note,updated_at=:now WHERE id=:id")
                    .param("now", now).param("note", note).param("id", taskKey).update();
            action(context, actor.id(), "TASK_COMPLETED",
                    Map.of("protocol", PROTOCOL, "taskId", taskId, "taskType", task.type()), now);
            return new TaskCompletion(caseId, taskId, task.type(), "DONE", now, note);
        }));
    }

    /** API-018. Closes only when check-in, stay coverage and every required task are satisfied. */
    public Closure close(Principal principal, String caseId, String key) {
        validCase(caseId);
        validKey(key, "INVALID_CLOSURE_REQUEST");
        String hash = digest(JSON.writeValueAsString(Map.of("caseId", caseId)));
        return Objects.requireNonNull(write.execute(transaction -> {
            User actor = user(principal);
            if (!actor.counselor()) throw forbidden();
            Context context = lockCase(caseId);
            Optional<Closure> prior = jdbc.sql("""
                    SELECT a.id,a.details->>'idempotencyKey' AS idempotency_key,c.close_reason,c.closed_at
                    FROM case_actions a JOIN cases c ON c.id=a.case_id WHERE a.case_id=:caseKey AND a.action_type='CASE_CLOSED'
                    """).param("caseKey", context.caseKey())
                    .query((rs, index) -> {
                        if (!key.equals(rs.getString("idempotency_key"))) throw conflict("CASE_ALREADY_CLOSED");
                        return new Closure(caseId, "CLS-" + rs.getLong("id"), "CLOSED", rs.getString("close_reason"),
                                rs.getObject("closed_at", OffsetDateTime.class));
                    }).optional();
            if (prior.isPresent()) return prior.orElseThrow();
            if ("CLOSED".equals(context.status())) throw conflict("CASE_ALREADY_CLOSED");

            List<Map<String, Object>> blockers = new ArrayList<>();
            Optional<Confirmed> replacement = jdbc.sql("""
                    SELECT r.id,r.request_code,q.check_in_date,q.check_out_date FROM replacement_requests r
                    JOIN alternative_selections s ON s.id=r.selection_id AND s.case_id=r.case_id
                    JOIN alternative_quotes q ON q.id=s.quote_id AND q.case_id=s.case_id
                    WHERE r.case_id=:caseKey AND r.status='CONFIRMED' ORDER BY r.id DESC LIMIT 1
                    """).param("caseKey", context.caseKey())
                    .query((rs, index) -> new Confirmed(rs.getLong("id"), rs.getString("request_code"),
                            rs.getObject("check_in_date", LocalDate.class), rs.getObject("check_out_date", LocalDate.class)))
                    .optional();
            Optional<Long> checkin = Optional.empty();
            if (replacement.isEmpty()) blockers.add(Map.of("code", "REPLACEMENT_NOT_CONFIRMED"));
            else {
                checkin = jdbc.sql("""
                        SELECT id FROM checkin_confirmations WHERE case_id=:caseKey AND replacement_request_id=:id
                            AND confirmation_status IN ('CUSTOMER_CONFIRMED','PROVIDER_VERIFIED') ORDER BY id LIMIT 1
                        """).param("caseKey", context.caseKey()).param("id", replacement.orElseThrow().id())
                        .query(Long.class).optional();
                if (checkin.isEmpty()) blockers.add(Map.of("code", "CHECKIN_NOT_CONFIRMED"));
            }
            Confirmed covered = replacement.orElse(null);
            List<String> uncovered = jdbc.sql("""
                    SELECT stay_date FROM booking_nights WHERE booking_id=:booking
                        AND NOT (fact_status='VERIFIED' AND fulfillment_status='AVAILABLE')
                        AND NOT (:covered AND stay_date>=:start AND stay_date<:end)
                    ORDER BY stay_date
                    """).param("booking", context.bookingId()).param("covered", covered != null)
                    .param("start", covered == null ? LocalDate.EPOCH : covered.checkIn())
                    .param("end", covered == null ? LocalDate.EPOCH : covered.checkOut())
                    .query(LocalDate.class).list().stream().map(LocalDate::toString).toList();
            if (!uncovered.isEmpty()) blockers.add(Map.of("code", "STAY_NOT_COVERED", "stayDates", uncovered));
            List<Map<String, Object>> open = jdbc.sql("""
                    SELECT id,task_type FROM case_tasks WHERE case_id=:caseKey AND is_required AND status<>'DONE' ORDER BY id
                    """).param("caseKey", context.caseKey())
                    .query((rs, index) -> Map.<String, Object>of("taskId", "TASK-" + rs.getLong("id"), "type", rs.getString("task_type")))
                    .list();
            if (!open.isEmpty()) blockers.add(Map.of("code", "REQUIRED_TASKS_OPEN", "tasks", open));
            if (!blockers.isEmpty()) throw new CloseBlocked(blockers);

            OffsetDateTime now = now();
            jdbc.sql("""
                    UPDATE cases SET status='CLOSED',close_reason=:reason,closed_at=:now,version=version+1,updated_at=:now
                    WHERE id=:caseKey
                    """).param("reason", CLOSE_REASON).param("now", now).param("caseKey", context.caseKey()).update();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("protocol", PROTOCOL); details.put("idempotencyKey", key); details.put("requestHash", hash);
            details.put("closeReason", CLOSE_REASON); details.put("previousStatus", context.status());
            details.put("replacementBookingId", covered.requestId()); details.put("checkinConfirmationId", "CHK-" + checkin.orElseThrow());
            long actionId = action(context, actor.id(), "CASE_CLOSED", details, now);
            message(context, "필수 업무와 입실 확인을 마쳐 사건을 종료했습니다.", now);
            return new Closure(caseId, "CLS-" + actionId, "CLOSED", CLOSE_REASON, now);
        }));
    }

    private Optional<Replacement> replacement(String requestId) {
        return jdbc.sql("""
                SELECT r.id,c.case_code,b.customer_id FROM replacement_requests r JOIN cases c ON c.id=r.case_id
                JOIN bookings b ON b.id=c.booking_id WHERE r.request_code=:code
                """).param("code", requestId)
                .query((rs, index) -> new Replacement(rs.getLong("id"), rs.getString("case_code"), rs.getLong("customer_id")))
                .optional();
    }

    private Context lockCase(String caseCode) {
        long booking = jdbc.sql("SELECT booking_id FROM cases WHERE case_code=:code").param("code", caseCode)
                .query(Long.class).optional().orElseThrow(() -> notFound("CASE_NOT_FOUND"));
        jdbc.sql("SELECT id FROM bookings WHERE id=:id FOR UPDATE").param("id", booking).query(Long.class).single();
        return jdbc.sql("SELECT id,status FROM cases WHERE case_code=:code AND booking_id=:booking FOR UPDATE")
                .param("code", caseCode).param("booking", booking)
                .query((rs, index) -> new Context(rs.getLong("id"), caseCode, booking, rs.getString("status"))).single();
    }

    private User user(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        return jdbc.sql("SELECT id,role FROM users WHERE is_active AND subject_key=:subject").param("subject", principal.getName())
                .query((rs, index) -> new User(rs.getLong("id"), rs.getString("role"))).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED"));
    }

    private void message(Context context, String body, OffsetDateTime now) {
        jdbc.sql("INSERT INTO chat_messages (message_code,case_id,sender_type,message_type,body,sent_at) VALUES (:code,:caseKey,'SYSTEM','SYSTEM',:body,:now)")
                .param("code", "MSG-" + UUID.randomUUID()).param("caseKey", context.caseKey()).param("body", body).param("now", now).update();
    }

    private long action(Context context, long actor, String type, Map<String, Object> details, OffsetDateTime now) {
        return jdbc.sql("""
                INSERT INTO case_actions (case_id,booking_id,actor_id,action_type,details,acted_at)
                VALUES (:caseKey,:booking,:actor,:type,CAST(:details AS jsonb),:now) RETURNING id
                """).param("caseKey", context.caseKey()).param("booking", context.bookingId()).param("actor", actor)
                .param("type", type).param("details", JSON.writeValueAsString(details)).param("now", now)
                .query(Long.class).single();
    }

    private OffsetDateTime now() { return clock.instant().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC); }
    private static void validCase(String id) { if (id == null || !id.matches("C-[0-9]{3,38}")) throw bad("INVALID_CASE_ID"); }
    private static void validKey(String key, String code) { if (key == null || !key.matches("[A-Za-z0-9._:-]{1,100}")) throw bad(code); }
    private static ResponseStatusException bad(String code) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, code); }
    private static ResponseStatusException conflict(String code) { return new ResponseStatusException(HttpStatus.CONFLICT, code); }
    private static ResponseStatusException forbidden() { return new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE"); }
    private static ResponseStatusException notFound(String code) { return new ResponseStatusException(HttpStatus.NOT_FOUND, code); }
    private static String digest(String text) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }

    public static final class CloseBlocked extends ResponseStatusException {
        private final List<Map<String, Object>> blockers;
        CloseBlocked(List<Map<String, Object>> blockers) { super(HttpStatus.CONFLICT, "CASE_CLOSE_BLOCKED"); this.blockers = List.copyOf(blockers); }
        public List<Map<String, Object>> blockers() { return blockers; }
    }
    public record Checkin(String checkinConfirmationId, String caseId, String replacementBookingId, String status, OffsetDateTime checkedInAt) {}
    public record TaskCompletion(String caseId, String taskId, String type, String status, OffsetDateTime completedAt, String completionNote) {}
    public record Closure(String caseId, String closureId, String status, String closeReason, OffsetDateTime closedAt) {}
    private record User(long id, String role) { boolean counselor() { return "COUNSELOR".equals(role); } }
    private record Context(long caseKey, String caseCode, long bookingId, String status) {}
    private record Replacement(long id, String caseCode, long customerId) {}
    private record Confirmed(long id, String requestId, LocalDate checkIn, LocalDate checkOut) {}
    private record Task(String type, String status, String note, OffsetDateTime completedAt) {}
}
