package com.csro.backend.cases;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
class CasePostprocessingService {
    static final MediaType DOCX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    private static final Set<String> TYPES = Set.of("CHECKIN_CONFIRM", "ORIGINAL_FOLLOWUP",
            "TRANSPORT_REVIEW", "REPLACEMENT_COST_REVIEW", "POINTS_REVIEW", "CONTACT_CUSTOMER", "VERIFY_HOTEL");
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final CaseAccess access;
    private final CasePostprocessingSnapshot snapshot;
    private final TransactionTemplate tx;
    private final RestClient agent;
    private final String token;

    CasePostprocessingService(JdbcClient jdbc, ObjectMapper json, CaseAccess access,
                              CasePostprocessingSnapshot snapshot, PlatformTransactionManager transactions,
                              @Value("${POSTPROCESSING_AGENT_URL:http://127.0.0.1:8002}") String agentUrl,
                              @Value("${POSTPROCESSING_SERVICE_TOKEN:}") String token) {
        this.jdbc = jdbc;
        this.json = json;
        this.access = access;
        this.snapshot = snapshot;
        this.tx = new TransactionTemplate(transactions);
        this.token = token;
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(65));
        this.agent = RestClient.builder().baseUrl(agentUrl).requestFactory(factory).build();
    }

    record Draft(UUID draftId, JsonNode draft, boolean finalized, JsonNode taskIds) {}
    record TaskChoice(Integer index, String type, Boolean assignToMe, OffsetDateTime dueAt, Boolean required) {}
    record Submission(String reviewedText, List<TaskChoice> tasks) {}
    record Finalized(UUID draftId, JsonNode taskIds) {}
    private record Context(long caseKey, long counselor, String status) {}
    private record Stored(Draft draft, JsonNode input, String hash) {}

    Optional<Draft> latest(Principal principal, String caseId) {
        return latest(context(principal, caseId));
    }

    private Optional<Draft> latest(Context context) {
        return jdbc.sql("SELECT draft_id FROM case_postprocessing WHERE case_id=:case ORDER BY created_at DESC, draft_id DESC LIMIT 1")
                .param("case", context.caseKey()).query(UUID.class).optional()
                .map(id -> stored(context, id).draft());
    }

    Draft draft(Principal principal, String caseId, UUID requestId) {
        var context = context(principal, caseId);
        if (requestId == null) throw invalid();
        var existing = latest(context);
        if (existing.isPresent()) return existing.get();
        requireOpen(context.status());
        var input = snapshot.read(context.caseKey(), context.counselor());
        if (input.toString().length() > 250_000)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "POSTPROCESSING_INPUT_TOO_LARGE");
        var generated = call("/drafts", input, MediaType.APPLICATION_JSON, JsonNode.class);
        if (!generated.path("draft_text").isString() || !generated.path("follow_up_suggestions").isArray())
            throw unavailable();
        return tx.execute(status -> {
            String state = lock(context.caseKey());
            var current = latest(context);
            if (current.isPresent()) return current.get();
            requireOpen(state);
            jdbc.sql("""
                INSERT INTO case_postprocessing (draft_id, case_id, created_by, case_input, draft)
                VALUES (:id,:case,:user,CAST(:input AS jsonb),CAST(:draft AS jsonb))
                ON CONFLICT (draft_id) DO NOTHING
                """).param("id", requestId).param("case", context.caseKey()).param("user", context.counselor())
                    .param("input", input.toString()).param("draft", generated.toString()).update();
            return stored(context, requestId).draft();
        });
    }

    Finalized finalizeDraft(Principal principal, String caseId, UUID draftId, Submission request) {
        var context = context(principal, caseId);
        validate(request);
        var stored = stored(context, draftId);
        String submitted = json.writeValueAsString(request);
        String hash = hash(submitted);
        if (stored.hash() != null) return replay(stored, hash);
        requireOpen(context.status());

        var suggestions = stored.draft().draft().path("follow_up_suggestions");
        var body = json.createObjectNode();
        body.set("case_input", stored.input());
        body.put("reviewed_text", request.reviewedText());
        var selected = body.putArray("selected_follow_ups");
        for (var task : request.tasks()) {
            if (task.index() >= suggestions.size() || !suggestions.get(task.index()).isObject()) throw invalid();
            var suggestion = (tools.jackson.databind.node.ObjectNode) suggestions.get(task.index()).deepCopy();
            if (task.assignToMe()) suggestion.put("owner", "담당 상담사");
            else suggestion.putNull("owner");
            if (task.dueAt() != null) suggestion.put("due_at", task.dueAt().toString());
            else suggestion.putNull("due_at");
            selected.add(suggestion);
        }
        byte[] document = call("/documents", body, DOCX, byte[].class);
        if (document.length < 4 || document.length > 5_000_000 || document[0] != 'P' || document[1] != 'K')
            throw unavailable();

        return tx.execute(status -> {
            // Booking → case lock order matches reconfirmation and replacement writes.
            String caseStatus = lock(context.caseKey());
            var current = stored(context, draftId);
            if (current.hash() != null) return replay(current, hash);
            requireOpen(caseStatus);
            List<String> taskIds = new ArrayList<>();
            for (var choice : request.tasks()) {
                if ("CHECKIN_CONFIRM".equals(choice.type())) {
                    var pending = jdbc.sql("""
                        SELECT id FROM case_tasks WHERE case_id=:case AND task_type='CHECKIN_CONFIRM'
                            AND is_required AND status IN ('TODO','IN_PROGRESS') ORDER BY id LIMIT 1
                        """).param("case", context.caseKey()).query(Long.class).optional();
                    if (pending.isPresent()) {
                        String existing = "TASK-" + pending.get();
                        if (!taskIds.contains(existing)) taskIds.add(existing);
                        continue;
                    }
                    boolean checkinRecorded = jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM checkin_confirmations i
                            JOIN replacement_requests r ON r.id=i.replacement_request_id
                            JOIN alternative_selections s ON s.id=r.selection_id
                            WHERE i.case_id=:case AND s.superseded_at IS NULL
                                AND i.confirmation_status IN ('CUSTOMER_CONFIRMED','PROVIDER_VERIFIED')
                        )
                        """).param("case", context.caseKey()).query(Boolean.class).single();
                    if (checkinRecorded)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "POSTPROCESSING_ALREADY_CHECKED_IN");
                }
                long id = jdbc.sql("""
                    INSERT INTO case_tasks (case_id, booking_id, task_type, assignee_id, due_at, is_required)
                    SELECT id, booking_id, :type, :owner, :due, :required FROM cases WHERE id=:case
                    RETURNING id
                    """).param("case", context.caseKey()).param("type", choice.type())
                        .param("owner", choice.assignToMe() ? context.counselor() : null, java.sql.Types.BIGINT)
                        .param("due", choice.dueAt(), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                        .param("required", choice.required()).query(Long.class).single();
                taskIds.add("TASK-" + id);
            }
            JsonNode ids = json.valueToTree(taskIds);
            jdbc.sql("""
                UPDATE case_postprocessing SET submitted=CAST(:submitted AS jsonb), request_hash=:hash,
                    finalized_by=:user, finalized_at=now(), task_ids=CAST(:tasks AS jsonb), document=:doc
                WHERE draft_id=:id AND case_id=:case
                """).param("submitted", submitted).param("hash", hash).param("user", context.counselor())
                    .param("tasks", ids.toString()).param("doc", document)
                    .param("id", draftId).param("case", context.caseKey()).update();
            return new Finalized(draftId, ids);
        });
    }

    byte[] download(Principal principal, String caseId, UUID draftId) {
        var context = context(principal, caseId);
        return jdbc.sql("SELECT document FROM case_postprocessing WHERE case_id=:case AND draft_id=:id AND document IS NOT NULL")
                .param("case", context.caseKey()).param("id", draftId)
                .query((row, index) -> row.getBytes(1)).optional().orElseThrow(CasePostprocessingService::notFound);
    }

    private Context context(Principal principal, String caseId) {
        var user = access.chatUser(principal, caseId);
        if (!user.isCounselor()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
        var entry = access.chatCase(user, caseId, false);
        return new Context(entry.id(), user.id(), entry.status());
    }

    private Optional<Stored> find(Context context, UUID id) {
        return jdbc.sql("""
            SELECT draft::text, case_input::text, request_hash, task_ids::text
            FROM case_postprocessing WHERE case_id=:case AND draft_id=:id
            """).param("case", context.caseKey()).param("id", id).query((row, index) -> {
            String hash = row.getString(3);
            return new Stored(new Draft(id, json.readTree(row.getString(1)), hash != null,
                    hash == null ? json.createArrayNode() : json.readTree(row.getString(4))),
                    json.readTree(row.getString(2)), hash);
        }).optional();
    }

    private Stored stored(Context context, UUID id) {
        return find(context, id).orElseThrow(CasePostprocessingService::notFound);
    }

    private String lock(long caseKey) {
        jdbc.sql("SELECT b.id FROM bookings b JOIN cases c ON c.booking_id=b.id WHERE c.id=:id FOR UPDATE OF b")
                .param("id", caseKey).query(Long.class).single();
        return jdbc.sql("SELECT status FROM cases WHERE id=:id FOR UPDATE")
                .param("id", caseKey).query(String.class).single();
    }

    private static void requireOpen(String status) {
        if ("CLOSED".equals(status)) throw new ResponseStatusException(HttpStatus.CONFLICT, "CASE_CLOSED");
    }

    private static void validate(Submission request) {
        if (request.reviewedText() == null || request.reviewedText().isBlank()
                || request.reviewedText().length() > 30000 || request.tasks() == null || request.tasks().size() > 20)
            throw invalid();
        var used = new HashSet<Integer>();
        for (var choice : request.tasks()) {
            if (choice == null || choice.index() == null || choice.index() < 0 || !used.add(choice.index())
                    || choice.type() == null || !TYPES.contains(choice.type())
                    || choice.required() == null || choice.assignToMe() == null
                    || ("CHECKIN_CONFIRM".equals(choice.type()) && !choice.required())) throw invalid();
        }
    }

    private static Finalized replay(Stored stored, String hash) {
        if (!hash.equals(stored.hash()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "POSTPROCESSING_CONFLICT");
        return new Finalized(stored.draft().draftId(), stored.draft().taskIds());
    }

    private <T> T call(String path, JsonNode input, MediaType type, Class<T> output) {
        if (token.isBlank()) throw unavailable();
        try {
            var result = agent.post().uri("/internal/v1/case-postprocessing" + path)
                    .headers(headers -> headers.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON).accept(type).body(input).retrieve().body(output);
            if (result == null) throw unavailable();
            return result;
        } catch (RestClientException error) {
            throw unavailable();
        }
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_POSTPROCESSING_REQUEST");
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "POSTPROCESSING_NOT_FOUND");
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "POSTPROCESSING_AGENT_UNAVAILABLE");
    }
}
