package com.csro.backend.resolution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Direct customer check-in and evidence-gated resolution; no provider calls or original-booking fact writes. */
@Service
public class CaseResolutionService {
    private static final String PROTOCOL = "case-resolution-v1";
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcClient jdbc;
    private final TransactionTemplate write, read;
    private final Clock clock;

    @Autowired
    public CaseResolutionService(JdbcClient jdbc, PlatformTransactionManager manager) { this(jdbc, manager, Clock.systemUTC()); }

    public CaseResolutionService(JdbcClient jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc); this.clock = Objects.requireNonNull(clock);
        write = new TransactionTemplate(Objects.requireNonNull(manager));
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read = new TransactionTemplate(manager);
        // Join API-004/011's existing repeatable-read snapshot.
        read.setReadOnly(true); read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public String checkin(Principal principal, String brq, String key, OffsetDateTime effectiveAt, boolean synthetic) {
        if (brq == null || !brq.matches("BRQ-[0-9a-f]{64}")) throw bad("INVALID_REPLACEMENT_ID");
        validKey(key, "INVALID_CHECKIN_REQUEST");
        if (effectiveAt == null) throw bad("INVALID_CHECKIN_REQUEST");
        String hash = hash(List.of(brq, true));
        return Objects.requireNonNull(write.execute(transaction -> {
            User actor = user(principal);
            if (!"CUSTOMER".equals(actor.role())) throw forbidden();
            String caseId = jdbc.sql("""
                    SELECT c.case_code FROM replacement_requests r JOIN cases c ON c.id=r.case_id JOIN bookings b ON b.id=c.booking_id
                    WHERE r.request_code=:brq AND b.customer_id=:actor
                    """).param("brq", brq).param("actor", actor.id()).query(String.class).optional()
                    .orElseThrow(() -> notFound("REPLACEMENT_BOOKING_NOT_FOUND"));
            Context context = context(caseId, actor, true);
            Optional<String> replay = replay(context, actor, "CHECKIN", key, hash);
            if (replay.isPresent()) return replay.orElseThrow();
            Optional<JsonNode> prior = checkinAction(context, brq, actor.id());
            if (prior.isPresent()) throw new CheckinAlreadyConfirmed(text(prior.orElseThrow().path("response"), "checkinConfirmationId"));
            open(context);
            if (unresolved(context.id())) throw conflict("REPLACEMENT_RESULT_UNRESOLVED");
            Reservation reservation = reservations(context).stream().filter(r -> r.brq().equals(brq)).findFirst()
                    .orElseThrow(() -> conflict("REPLACEMENT_NOT_CONFIRMED"));
            if (!reservation.valid()) throw conflict("QUOTE_INVALID");
            LocalDate day = effectiveAt.atZoneSameInstant(KOREA).toLocalDate();
            if (day.isBefore(reservation.start()) || !day.isBefore(reservation.end())) throw conflict("CHECKIN_OUTSIDE_STAY");
            boolean already = jdbc.sql("""
                    SELECT EXISTS(SELECT 1 FROM checkin_confirmations WHERE case_id=:caseKey AND replacement_request_id=:request
                        AND source_type='CUSTOMER' AND confirmation_status='CUSTOMER_CONFIRMED' AND confirmed_by=:actor)
                    """).param("caseKey", context.id()).param("request", reservation.id()).param("actor", actor.id()).query(Boolean.class).single();
            if (already) throw conflict("CHECKIN_RECORD_INCONSISTENT");
            OffsetDateTime auditAt = now(), checkinAt = effectiveAt.truncatedTo(ChronoUnit.MICROS);
            String publicId = "CHECKIN-" + UUID.randomUUID();
            long confirmation = jdbc.sql("""
                    INSERT INTO checkin_confirmations (case_id,target_type,replacement_request_id,source_type,confirmation_status,
                        confirmed_by,idempotency_key,checkin_at,created_at,booking_id,request_hash)
                    VALUES (:caseKey,'REPLACEMENT',:request,'CUSTOMER','CUSTOMER_CONFIRMED',:actor,:key,:at,:now,:booking,:hash) RETURNING id
                    """).param("caseKey", context.id()).param("request", reservation.id()).param("actor", actor.id()).param("key", key)
                    .param("at", checkinAt).param("now", auditAt).param("booking", context.booking()).param("hash", hash).query(Long.class).single();
            List<String> completed = new ArrayList<>();
            Optional<Long> task = jdbc.sql("""
                    SELECT id FROM case_tasks WHERE case_id=:caseKey AND booking_id=:booking AND task_type='CHECKIN_CONFIRM'
                        AND is_required AND status IN ('TODO','IN_PROGRESS') ORDER BY id LIMIT 1 FOR UPDATE
                    """).param("caseKey", context.id()).param("booking", context.booking()).query(Long.class).optional();
            if (task.isPresent()) {
                long taskKey = task.orElseThrow();
                String note = "고객 직접 입실 확인 " + publicId + " · " + brq + " · " + checkinAt;
                jdbc.sql("UPDATE case_tasks SET status='DONE',completion_note=:note,completed_at=:now,updated_at=:now WHERE id=:id")
                        .param("note", note).param("now", auditAt).param("id", taskKey).update();
                completed.add("TASK-" + taskKey);
            }
            bump(context, auditAt);
            Map<String, Object> receipt = linked("checkinConfirmationId", publicId, "caseId", context.code(), "replacementBookingId", brq,
                    "status", "CUSTOMER_CONFIRMED", "checkinAt", checkinAt, "isSynthetic", true, "clockMode", mode(synthetic));
            String response = json(receipt);
            audit(context, actor, "CHECKIN_CONFIRMED", "CHECKIN", brq, key, hash, response,
                    linked("checkinRowId", confirmation, "replacementBookingId", brq, "completedTaskIds", completed,
                            "effectiveAt", effectiveAt, "clockMode", mode(synthetic)), auditAt);
            message(context, "고객이 대체 숙소 입실을 직접 확인했습니다. 고객 직접 확인 기록이며 호텔·공급사의 입실 검증이나 사건 자동 종료가 아닙니다.", auditAt);
            return response;
        }));
    }

    public String completeTask(Principal principal, String caseId, String taskId, String key, TaskCompletion command) {
        validCase(caseId); long taskKey = taskKey(taskId); validKey(key, "INVALID_TASK_COMPLETION");
        if (command == null || !"DONE".equals(command.status()) || command.expectedVersion() == null || command.expectedVersion() < 0
                || !validText(command.completionNote(), 1000)) throw bad("INVALID_TASK_COMPLETION");
        String note = command.completionNote().strip();
        String hash = hash(linked("targetId", taskId, "status", "DONE", "completionNote", note,
                "expectedVersion", command.expectedVersion(), "followupOutcome", command.followupOutcome()));
        return Objects.requireNonNull(write.execute(transaction -> {
            User actor = user(principal); if (!actor.counselor()) throw forbidden();
            Context context = context(caseId, actor, true);
            Optional<String> replay = replay(context, actor, "TASK_COMPLETE", key, hash);
            if (replay.isPresent()) return replay.orElseThrow();
            open(context); version(context, command.expectedVersion());
            Task task = tasks(context).stream().filter(t -> t.id() == taskKey).findFirst().orElseThrow(() -> notFound("TASK_NOT_FOUND"));
            if ("CHECKIN_CONFIRM".equals(task.type())) throw conflict("CHECKIN_REQUIRES_CONFIRMATION");
            if ("DONE".equals(task.status())) throw conflict("TASK_ALREADY_COMPLETED");
            if ("ORIGINAL_FOLLOWUP".equals(task.type())) {
                if (!Set.of("COMPLETED", "NOT_REQUIRED").contains(Objects.requireNonNullElse(command.followupOutcome(), "")))
                    throw bad("INVALID_TASK_COMPLETION");
            } else if (command.followupOutcome() != null) throw bad("INVALID_TASK_COMPLETION");
            OffsetDateTime now = now();
            jdbc.sql("""
                    UPDATE case_tasks SET status='DONE',completion_note=:note,completed_at=:now,updated_at=:now
                    WHERE id=:task AND case_id=:caseKey AND booking_id=:booking
                    """).param("note", note).param("now", now).param("task", taskKey).param("caseKey", context.id())
                    .param("booking", context.booking()).update();
            long nextVersion = bump(context, now);
            String response = json(linked("caseId", caseId, "taskId", taskId, "status", "DONE", "completionNote", note,
                    "completedAt", now, "version", nextVersion));
            audit(context, actor, "TASK_COMPLETED", "TASK_COMPLETE", taskId, key, hash, response,
                    linked("taskId", taskId, "taskType", task.type(), "followupOutcome", command.followupOutcome(), "completionNote", note), now);
            return response;
        }));
    }

    public String close(Principal principal, String caseId, String key, Closure command, OffsetDateTime effectiveAt, boolean synthetic) {
        validCase(caseId); validKey(key, "INVALID_CLOSURE_REQUEST");
        if (command == null || !validText(command.reason(), 80) || command.expectedVersion() == null || command.expectedVersion() < 0
                || effectiveAt == null) throw bad("INVALID_CLOSURE_REQUEST");
        String reason = command.reason().strip();
        String hash = hash(linked("reason", reason, "expectedVersion", command.expectedVersion()));
        return Objects.requireNonNull(write.execute(transaction -> {
            User actor = user(principal); if (!actor.counselor()) throw forbidden();
            Context context = context(caseId, actor, true);
            Optional<String> replay = replay(context, actor, "CLOSE", key, hash);
            if (replay.isPresent()) return replay.orElseThrow();
            open(context); version(context, command.expectedVersion());
            Evaluation evaluated = evaluate(context, effectiveAt);
            if (!evaluated.blockers().isEmpty()) throw new CaseNotResolved(evaluated.blockers(), evaluated.blockers().stream()
                    .map(b -> (String) b.get("code")).distinct().toList());
            OffsetDateTime now = now(); long nextVersion = Math.addExact(context.version(), 1);
            String closureId = "CLOSURE-" + UUID.randomUUID();
            jdbc.sql("UPDATE cases SET status='CLOSED',closed_at=:now,close_reason=:reason,version=:version,updated_at=:now WHERE id=:id")
                    .param("now", now).param("reason", reason).param("version", nextVersion).param("id", context.id()).update();
            String response = json(linked("closureId", closureId, "caseId", caseId, "status", "CLOSED", "reason", reason,
                    "closedAt", now, "version", nextVersion, "isSynthetic", true, "clockMode", mode(synthetic)));
            audit(context, actor, "CASE_CLOSED", "CLOSE", caseId, key, hash, response,
                    linked("effectiveAt", effectiveAt, "clockMode", mode(synthetic), "coverage", evaluated.coverage(),
                            "checkin", evaluated.checkin(), "requiredTasks", evaluated.requiredTasks()), now);
            message(context, "고객 직접 입실 확인, 전체 숙박일 보장 및 필수 후속 업무 완료를 확인하여 상담 사건을 종료했습니다. 실제 결제·환불·원예약 자동 취소를 실행한 것은 아닙니다.", now);
            return response;
        }));
    }

    public String resolution(Principal principal, String caseId, OffsetDateTime effectiveAt, boolean synthetic) {
        validCase(caseId);
        if (effectiveAt == null) throw bad("INVALID_RESOLUTION_REQUEST");
        return Objects.requireNonNull(read.execute(transaction -> {
            User actor = user(principal);
            if (!actor.counselor() && !"CUSTOMER".equals(actor.role())) throw forbidden();
            Context context = context(caseId, actor, false);
            Evaluation evaluated = evaluate(context, effectiveAt);
            JsonNode closure = closure(context);
            Object closureView = closure;
            if (!actor.counselor() && closure != null) closureView = linked("closureId", text(closure, "closureId"),
                    "status", "CLOSED", "closedAt", text(closure, "closedAt"), "isSynthetic", true,
                    "clockMode", text(closure, "clockMode"));
            Map<String, Object> result = linked("caseStatus", context.status(), "version", context.version(), "checkin", evaluated.checkin(),
                    "closure", closureView, "effectiveAt", effectiveAt, "clockMode", mode(synthetic), "isSynthetic", true);
            if (actor.counselor()) {
                result.put("coverage", evaluated.coverage()); result.put("requiredTasks", evaluated.requiredTasks());
                result.put("canClose", evaluated.blockers().isEmpty()); result.put("blockers", evaluated.blockers());
            }
            return json(result);
        }));
    }

    private Evaluation evaluate(Context context, OffsetDateTime effectiveAt) {
        List<Map<String, Object>> blockers = new ArrayList<>();
        if ("CLOSED".equals(context.status())) blockers.add(blocker("CASE_CLOSED"));
        if (unresolved(context.id())) blockers.add(blocker("REPLACEMENT_RESULT_UNRESOLVED"));
        // A booking-scoped late worker could otherwise recreate a case after closure.
        // Observe every owner and case association; never cancel or rewrite another worker's job.
        if (jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM jobs WHERE booking_id=:booking AND kind='RECHECK'
                    AND status IN ('PENDING','RUNNING','WAITING'))
                """).param("booking", context.booking()).query(Boolean.class).single()) {
            blockers.add(blocker("RECHECK_PENDING"));
        }
        List<LocalDate> required = context.start().datesUntil(context.end()).toList();
        List<Night> nights = jdbc.sql("""
                SELECT n.stay_date,n.fulfillment_status,n.fact_status,
                    (a.id IS NOT NULL AND a.booking_id=:booking AND a.review_status='ACCEPTED' AND a.reported_status='AVAILABLE') accepted
                FROM booking_nights n LEFT JOIN evidence_nights a ON a.id=n.accepted_assertion_id AND a.booking_night_id=n.id
                WHERE n.booking_id=:booking ORDER BY n.stay_date
                """).param("booking", context.booking()).query((rs, index) -> new Night(rs.getObject("stay_date", LocalDate.class),
                        rs.getString("fulfillment_status"), rs.getString("fact_status"), rs.getBoolean("accepted"))).list();
        List<LocalDate> existing = nights.stream().map(Night::date).toList();
        if (!existing.equals(required)) blockers.add(linked("code", "BOOKING_NIGHTS_INCOMPLETE", "stayDates",
                required.stream().filter(d -> !existing.contains(d)).toList()));
        Set<LocalDate> covered = new HashSet<>();
        nights.stream().filter(n -> n.accepted() && "VERIFIED".equals(n.fact()) && "AVAILABLE".equals(n.fulfillment()))
                .map(Night::date).forEach(covered::add);
        List<Reservation> reservations = reservations(context);
        List<Confirmation> confirmations = confirmations(context);
        Set<String> eligibleCheckins = new HashSet<>();
        JsonNode latestCheckin = null;
        for (Reservation reservation : reservations) {
            if (!reservation.valid()) { blockers.add(blocker("REPLACEMENT_SCOPE_INVALID")); continue; }
            for (Confirmation confirmation : confirmations) {
                if (confirmation.requestKey() != reservation.id() || confirmation.audit() == null || confirmation.at() == null) continue;
                JsonNode details = confirmation.audit(), receipt = details.path("response");
                LocalDate checkedDay = confirmation.at().atZoneSameInstant(KOREA).toLocalDate();
                if (confirmation.customer() != context.customer() || checkedDay.isBefore(reservation.start()) || !checkedDay.isBefore(reservation.end())
                        || !reservation.brq().equals(receipt.path("replacementBookingId").asString(""))
                        || !confirmation.at().toInstant().equals(time(receipt, "checkinAt").toInstant())) continue;
                latestCheckin = receipt;
                if (confirmation.at().isAfter(effectiveAt)) continue;
                eligibleCheckins.add(text(receipt, "checkinConfirmationId"));
                reservation.start().datesUntil(reservation.end()).forEach(covered::add);
            }
        }
        if (eligibleCheckins.isEmpty()) blockers.add(blocker("CHECKIN_MISSING"));
        List<LocalDate> uncovered = required.stream().filter(d -> !covered.contains(d)).toList();
        if (!uncovered.isEmpty()) blockers.add(linked("code", "STAY_NOT_COVERED", "stayDates", uncovered));
        List<Task> allTasks = tasks(context);
        List<Task> requiredTasks = allTasks.stream().filter(Task::required).toList();
        if (requiredTasks.stream().noneMatch(t -> "CHECKIN_CONFIRM".equals(t.type()))) blockers.add(blocker("REQUIRED_CHECKIN_TASK_MISSING"));
        if (requiredTasks.stream().noneMatch(t -> "ORIGINAL_FOLLOWUP".equals(t.type()))) blockers.add(blocker("REQUIRED_ORIGINAL_FOLLOWUP_MISSING"));
        List<Map<String, Object>> taskViews = new ArrayList<>();
        for (Task task : requiredTasks) {
            JsonNode completed = taskAction(context, task.id());
            String followup = completed == null ? null : completed.path("basis").path("followupOutcome").asString(null);
            taskViews.add(linked("taskId", "TASK-" + task.id(), "type", task.type(), "status", task.status(),
                    "completionNote", task.note(), "completedAt", task.completedAt(), "followupOutcome", followup));
            if (!"DONE".equals(task.status())) { blockers.add(taskBlocker("REQUIRED_TASK_INCOMPLETE", task.id())); continue; }
            if (task.completedAt() == null || task.booking() != context.booking() || task.evidence() == null && (task.note() == null || task.note().isBlank()))
                blockers.add(taskBlocker("TASK_COMPLETION_PROOF_MISSING", task.id()));
            if ("CHECKIN_CONFIRM".equals(task.type())) {
                boolean supported = confirmations.stream().filter(c -> c.audit() != null)
                        .anyMatch(c -> eligibleCheckins.contains(c.audit().path("response").path("checkinConfirmationId").asString(""))
                                && contains(c.audit().path("basis").path("completedTaskIds"), "TASK-" + task.id()));
                if (!supported) blockers.add(taskBlocker("CHECKIN_TASK_PROOF_MISSING", task.id()));
            }
            if ("ORIGINAL_FOLLOWUP".equals(task.type()) && (completed == null
                    || !Set.of("COMPLETED", "NOT_REQUIRED").contains(Objects.requireNonNullElse(followup, ""))
                    || !Objects.equals(task.note(), completed.path("response").path("completionNote").asString(null))))
                blockers.add(taskBlocker("ORIGINAL_FOLLOWUP_NOT_CONFIRMED", task.id()));
        }
        Map<String, Object> coverage = linked("fullyCovered", uncovered.isEmpty() && existing.equals(required), "requiredStayDates", required,
                "coveredStayDates", required.stream().filter(covered::contains).toList(), "uncoveredStayDates", uncovered);
        return new Evaluation(coverage, taskViews, blockers, latestCheckin);
    }

    /** A confirmed row counts only while its saved quote still agrees with the frozen accepted request. */
    private List<Reservation> reservations(Context context) {
        List<String> rows = jdbc.sql("""
                SELECT jsonb_build_object('id',r.id,'brq',r.request_code,'expected',r.expected_amount,'confirmed',r.confirmed_amount,
                    'currency',r.currency,'supplierBookingId',r.supplier_booking_id,'confirmedAt',r.confirmed_at,
                    'request',r.request_payload,'response',r.response_payload,'quote',to_jsonb(q),'propertyId',h.hotel_code,
                    'scope',a.details->'scope')::text
                FROM replacement_requests r JOIN alternative_selections s ON s.id=r.selection_id AND s.case_id=r.case_id
                JOIN alternative_quotes q ON q.id=s.quote_id AND q.case_id=s.case_id JOIN hotels h ON h.id=q.hotel_id
                LEFT JOIN LATERAL (SELECT details FROM case_actions WHERE case_id=r.case_id AND action_type='REPLACEMENT_WORK'
                    AND details->>'protocol'='replacement-booking-v1' AND details->>'requestId'=r.request_code ORDER BY id DESC LIMIT 1) a ON true
                WHERE r.case_id=:caseKey AND r.status='CONFIRMED' ORDER BY r.id
                """).param("caseKey", context.id()).query(String.class).list();
        List<Reservation> result = new ArrayList<>();
        for (String saved : rows) {
            JsonNode row = JSON.readTree(saved), quote = row.path("quote");
            long id = number(row, "id"); String brq = text(row, "brq");
            LocalDate start = LocalDate.parse(text(quote, "check_in_date")), end = LocalDate.parse(text(quote, "check_out_date"));
            boolean valid;
            try {
                JsonNode scope = row.path("scope"), request = row.path("request"), response = row.path("response"), source = quote.path("source_payload");
                valid = number(scope, "bookingId") == context.booking() && number(scope, "caseKey") == context.id()
                        && number(quote, "adults") == context.adults() && number(quote, "rooms") == context.rooms()
                        && LocalDate.parse(text(scope.path("stay"), "checkInDate")).equals(start)
                        && LocalDate.parse(text(scope.path("stay"), "checkOutDate")).equals(end)
                        && number(scope.path("stay"), "adults") == context.adults() && number(scope.path("stay"), "rooms") == context.rooms()
                        && !start.isBefore(context.start()) && !end.isAfter(context.end()) && start.isBefore(end)
                        && text(scope, "quoteId").equals(text(quote, "quote_code")) && text(scope, "propertyId").equals(text(row, "propertyId"))
                        && text(source, "quoteId").equals(text(quote, "quote_code")) && text(source, "propertyId").equals(text(row, "propertyId"))
                        && text(source, "roomTypeId").equals(text(quote, "room_type_code"))
                        && text(source, "ratePlanId").equals(text(quote, "rate_plan_code"))
                        && text(request, "quoteId").equals(text(quote, "quote_code"))
                        && number(request, "expectedTotalAmount") == number(quote, "total_amount")
                        && number(scope, "amount") == number(row, "expected") && number(row, "expected") == number(row, "confirmed")
                        && number(row, "expected") == number(quote, "total_amount")
                        && "KRW".equals(text(request, "currency")) && "KRW".equals(text(row, "currency"))
                        && "KRW".equals(text(quote, "currency")) && "CONFIRMED".equals(text(response, "status"))
                        && brq.equals(text(response, "requestId")) && text(response, "quoteId").equals(text(quote, "quote_code"))
                        && text(response, "propertyId").equals(text(row, "propertyId"))
                        && text(response, "supplierBookingId").equals(text(row, "supplierBookingId"))
                        && number(response.path("confirmedPrice"), "totalAmount") == number(row, "confirmed")
                        && "KRW".equals(text(response.path("confirmedPrice"), "currency"));
            } catch (RuntimeException malformed) { valid = false; }
            result.add(new Reservation(id, brq, start, end, valid));
        }
        return List.copyOf(result);
    }

    private List<Confirmation> confirmations(Context context) {
        return jdbc.sql("""
                SELECT i.id,i.replacement_request_id,i.confirmed_by,i.checkin_at,a.details::text
                FROM checkin_confirmations i LEFT JOIN LATERAL (SELECT details FROM case_actions
                    WHERE case_id=i.case_id AND details->>'protocol'=:protocol AND details->>'operation'='CHECKIN'
                    AND details->'basis'->>'checkinRowId'=CAST(i.id AS text) ORDER BY id DESC LIMIT 1) a ON true
                WHERE i.case_id=:caseKey AND i.booking_id=:booking AND i.target_type='REPLACEMENT'
                    AND i.source_type='CUSTOMER' AND i.confirmation_status='CUSTOMER_CONFIRMED' ORDER BY i.id
                """).param("protocol", PROTOCOL).param("caseKey", context.id()).param("booking", context.booking())
                .query((rs, index) -> new Confirmation(rs.getLong("id"), rs.getLong("replacement_request_id"), rs.getLong("confirmed_by"),
                        rs.getObject("checkin_at", OffsetDateTime.class), rs.getString("details") == null ? null : JSON.readTree(rs.getString("details")))).list();
    }

    private List<Task> tasks(Context context) {
        return jdbc.sql("SELECT id,booking_id,task_type,status,is_required,completion_note,completed_at,completion_evidence_id FROM case_tasks WHERE case_id=:caseKey ORDER BY id")
                .param("caseKey", context.id()).query((rs, index) -> new Task(rs.getLong("id"), rs.getLong("booking_id"), rs.getString("task_type"),
                        rs.getString("status"), rs.getBoolean("is_required"), rs.getString("completion_note"),
                        rs.getObject("completed_at", OffsetDateTime.class), rs.getObject("completion_evidence_id", Long.class))).list();
    }

    private Optional<JsonNode> checkinAction(Context context, String brq, long actor) {
        return jdbc.sql("""
                SELECT details::text FROM case_actions WHERE case_id=:caseKey AND actor_id=:actor AND details->>'protocol'=:protocol
                    AND details->>'operation'='CHECKIN' AND details->>'targetId'=:brq ORDER BY id DESC LIMIT 1
                """).param("caseKey", context.id()).param("actor", actor).param("protocol", PROTOCOL).param("brq", brq)
                .query(String.class).optional().map(JSON::readTree);
    }

    private JsonNode taskAction(Context context, long task) {
        return jdbc.sql("""
                SELECT details::text FROM case_actions WHERE case_id=:caseKey AND details->>'protocol'=:protocol
                    AND details->>'operation'='TASK_COMPLETE' AND details->>'targetId'=:task ORDER BY id DESC LIMIT 1
                """).param("caseKey", context.id()).param("protocol", PROTOCOL).param("task", "TASK-" + task)
                .query(String.class).optional().map(JSON::readTree).orElse(null);
    }

    private JsonNode closure(Context context) {
        return jdbc.sql("SELECT details->'response' FROM case_actions WHERE case_id=:caseKey AND action_type='CASE_CLOSED' AND details->>'protocol'=:protocol")
                .param("caseKey", context.id()).param("protocol", PROTOCOL).query(String.class).optional().map(JSON::readTree).orElse(null);
    }

    private boolean unresolved(long caseKey) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM replacement_requests WHERE case_id=:caseKey AND status IN ('PROCESSING','RESULT_UNKNOWN'))")
                .param("caseKey", caseKey).query(Boolean.class).single();
    }

    private Optional<String> replay(Context context, User actor, String operation, String key, String hash) {
        return jdbc.sql("""
                SELECT details::text FROM case_actions WHERE case_id=:caseKey AND details->>'protocol'=:protocol
                    AND details->>'operation'=:operation AND details->>'key'=:key ORDER BY id DESC LIMIT 1
                """).param("caseKey", context.id()).param("protocol", PROTOCOL).param("operation", operation).param("key", key)
                .query(String.class).optional().map(saved -> {
                    JsonNode details = JSON.readTree(saved);
                    if (!hash.equals(text(details, "requestHash")) || number(details, "actorId") != actor.id()) throw conflict("IDEMPOTENCY_KEY_REUSED");
                    String response = text(details, "responseJson");
                    if (!JSON.readTree(response).equals(details.path("response"))) throw new IllegalStateException("Resolution receipt mismatch");
                    return response;
                });
    }

    private void audit(Context context, User actor, String action, String operation, String target, String key, String hash,
                       String response, Map<String, Object> basis, OffsetDateTime at) {
        Map<String, Object> details = linked("protocol", PROTOCOL, "operation", operation, "targetId", target, "key", key,
                "requestHash", hash, "actorId", actor.id(), "response", JSON.readTree(response), "responseJson", response, "basis", basis);
        jdbc.sql("INSERT INTO case_actions (case_id,actor_id,action_type,details,acted_at,booking_id) VALUES (:caseKey,:actor,:action,CAST(:details AS jsonb),:at,:booking)")
                .param("caseKey", context.id()).param("actor", actor.id()).param("action", action).param("details", json(details))
                .param("at", at).param("booking", context.booking()).update();
    }

    private void message(Context context, String text, OffsetDateTime at) {
        jdbc.sql("INSERT INTO chat_messages (message_code,case_id,sender_type,message_type,body,sent_at) VALUES (:code,:caseKey,'SYSTEM','SYSTEM',:body,:at)")
                .param("code", "MSG-" + UUID.randomUUID()).param("caseKey", context.id()).param("body", text).param("at", at).update();
    }

    private long bump(Context context, OffsetDateTime at) {
        long version = Math.addExact(context.version(), 1);
        jdbc.sql("UPDATE cases SET version=:version,updated_at=:at WHERE id=:caseKey")
                .param("version", version).param("at", at).param("caseKey", context.id()).update();
        return version;
    }

    private User user(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        return jdbc.sql("SELECT id,role FROM users WHERE is_active AND subject_key=:subject").param("subject", principal.getName())
                .query((rs, index) -> new User(rs.getLong("id"), rs.getString("role"))).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED"));
    }

    private Context context(String caseId, User actor, boolean lock) {
        if (lock) {
            long booking = jdbc.sql("SELECT b.id FROM cases c JOIN bookings b ON b.id=c.booking_id WHERE c.case_code=:code AND (:counselor OR b.customer_id=:actor)")
                    .param("code", caseId).param("counselor", actor.counselor()).param("actor", actor.id()).query(Long.class).optional()
                    .orElseThrow(() -> notFound("CASE_NOT_FOUND"));
            jdbc.sql("SELECT id FROM bookings WHERE id=:booking FOR UPDATE").param("booking", booking).query(Long.class).single();
            jdbc.sql("SELECT id FROM cases WHERE case_code=:code AND booking_id=:booking FOR UPDATE")
                    .param("code", caseId).param("booking", booking).query(Long.class).single();
        }
        return jdbc.sql("""
                SELECT c.id,c.case_code,c.status,c.version,b.id booking_id,b.customer_id,b.check_in_date,b.check_out_date,b.adults,b.rooms
                FROM cases c JOIN bookings b ON b.id=c.booking_id WHERE c.case_code=:code AND (:counselor OR b.customer_id=:actor)
                """).param("code", caseId).param("counselor", actor.counselor()).param("actor", actor.id())
                .query((rs, index) -> new Context(rs.getLong("id"), rs.getString("case_code"), rs.getLong("booking_id"), rs.getLong("customer_id"),
                        rs.getString("status"), rs.getLong("version"), rs.getObject("check_in_date", LocalDate.class),
                        rs.getObject("check_out_date", LocalDate.class), rs.getInt("adults"), rs.getInt("rooms"))).optional()
                .orElseThrow(() -> notFound("CASE_NOT_FOUND"));
    }

    private OffsetDateTime now() { return clock.instant().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC); }
    private static void version(Context c, long expected) { if (c.version() != expected) throw new CaseVersionConflict(c.version()); }
    private static void open(Context c) { if ("CLOSED".equals(c.status())) throw conflict("CASE_CLOSED"); }
    private static boolean validText(String value, int max) { return value != null && !value.strip().isEmpty() && value.strip().codePointCount(0, value.strip().length()) <= max && value.indexOf(0) < 0; }
    private static void validKey(String key, String code) { if (key == null || !key.matches("[A-Za-z0-9._:-]{1,100}")) throw bad(code); }
    private static void validCase(String id) { if (id == null || !id.matches("C-[0-9]{3,38}")) throw bad("INVALID_CASE_ID"); }
    private static long taskKey(String id) {
        if (id == null || !id.matches("TASK-[0-9]{1,19}")) throw bad("INVALID_TASK_ID");
        try { long value = Long.parseLong(id.substring(5)); if (value < 1 || !id.equals("TASK-" + value)) throw bad("INVALID_TASK_ID"); return value; }
        catch (NumberFormatException invalid) { throw bad("INVALID_TASK_ID"); }
    }
    private static boolean contains(JsonNode array, String value) { if (!array.isArray()) return false; for (JsonNode item : array) if (value.equals(item.asString(""))) return true; return false; }
    private static String text(JsonNode node, String field) { JsonNode value = node.path(field); if (!value.isString() || value.stringValue().isBlank()) throw new IllegalArgumentException("Invalid resolution record"); return value.stringValue(); }
    private static long number(JsonNode node, String field) { JsonNode value = node.path(field); if (!value.isIntegralNumber() || !value.canConvertToLong()) throw new IllegalArgumentException("Invalid resolution record"); return value.longValue(); }
    private static OffsetDateTime time(JsonNode node, String field) { return OffsetDateTime.parse(text(node, field)); }
    private static String mode(boolean synthetic) { return synthetic ? "SYNTHETIC_DEMO" : "REAL_TIME"; }
    private static String json(Object value) { return JSON.writeValueAsString(value); }
    private static String hash(Object value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json(value).getBytes(StandardCharsets.UTF_8))); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
    private static Map<String, Object> linked(Object... values) { Map<String, Object> result = new LinkedHashMap<>(); for (int index = 0; index < values.length; index += 2) result.put((String) values[index], values[index + 1]); return result; }
    private static Map<String, Object> blocker(String code) { return Map.of("code", code); }
    private static Map<String, Object> taskBlocker(String code, long task) { return Map.of("code", code, "taskId", "TASK-" + task); }
    private static ResponseStatusException bad(String code) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, code); }
    private static ResponseStatusException forbidden() { return new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE"); }
    private static ResponseStatusException conflict(String code) { return new ResponseStatusException(HttpStatus.CONFLICT, code); }
    private static ResponseStatusException notFound(String code) { return new ResponseStatusException(HttpStatus.NOT_FOUND, code); }

    public record TaskCompletion(String status, String completionNote, Long expectedVersion, String followupOutcome) {}
    public record Closure(String reason, Long expectedVersion) {}
    public static final class CheckinAlreadyConfirmed extends ResponseStatusException {
        private final String id;
        public CheckinAlreadyConfirmed(String id) { super(HttpStatus.CONFLICT, "CHECKIN_ALREADY_CONFIRMED"); this.id = id; }
        public String checkinConfirmationId() { return id; }
    }
    public static final class CaseVersionConflict extends ResponseStatusException {
        private final long version;
        public CaseVersionConflict(long version) { super(HttpStatus.CONFLICT, "CASE_VERSION_CONFLICT"); this.version = version; }
        public long currentVersion() { return version; }
    }
    public static final class CaseNotResolved extends ResponseStatusException {
        private final List<Map<String, Object>> blockers;
        private final List<String> missing;
        public CaseNotResolved(List<Map<String, Object>> blockers, List<String> missingConditions) {
            super(HttpStatus.CONFLICT, "CASE_NOT_RESOLVED"); this.blockers = List.copyOf(blockers); missing = List.copyOf(missingConditions);
        }
        public List<Map<String, Object>> blockers() { return blockers; }
        public List<String> missingConditions() { return missing; }
    }
    private record User(long id, String role) { boolean counselor() { return "COUNSELOR".equals(role); } }
    private record Context(long id, String code, long booking, long customer, String status, long version, LocalDate start, LocalDate end, int adults, int rooms) {}
    private record Task(long id, long booking, String type, String status, boolean required, String note, OffsetDateTime completedAt, Long evidence) {}
    private record Reservation(long id, String brq, LocalDate start, LocalDate end, boolean valid) {}
    private record Confirmation(long id, long requestKey, long customer, OffsetDateTime at, JsonNode audit) {}
    private record Night(LocalDate date, String fulfillment, String fact, boolean accepted) {}
    private record Evaluation(Map<String, Object> coverage, List<Map<String, Object>> requiredTasks, List<Map<String, Object>> blockers, JsonNode checkin) {}
}
