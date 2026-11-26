package com.csro.backend.evidence;

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
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** API-006: a counselor's direct hotel call becomes evidence under the same night rule as automated rechecks. */
@Service
public class ManualEvidenceService {
    private static final String PROTOCOL = "manual-hotel-evidence-v1";
    private static final String ACTION = "MANUAL_EVIDENCE_RECORDED";
    private static final Set<String> STATUSES = Set.of("AVAILABLE", "UNAVAILABLE", "UNKNOWN");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcClient jdbc;
    private final TransactionTemplate write;
    private final Clock clock;

    @Autowired
    public ManualEvidenceService(JdbcClient jdbc, PlatformTransactionManager manager) {
        this(jdbc, manager, Clock.systemUTC());
    }

    public ManualEvidenceService(JdbcClient jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
        write = new TransactionTemplate(Objects.requireNonNull(manager));
    }

    public Recorded record(Principal principal, String caseId, String key, String callNote, List<Claim> nights) {
        if (caseId == null || !caseId.matches("C-[0-9]{3,38}")) throw bad("INVALID_CASE_ID");
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,100}") || callNote == null || callNote.isBlank()
                || callNote.length() > 2000 || nights == null || nights.isEmpty()
                || nights.stream().anyMatch(n -> n.stayDate() == null || !STATUSES.contains(n.reportedProvisionStatus()))
                || nights.stream().map(Claim::stayDate).distinct().count() != nights.size())
            throw bad("INVALID_EVIDENCE_REQUEST");
        List<Claim> ordered = nights.stream().sorted((a, b) -> a.stayDate().compareTo(b.stayDate())).toList();
        String hash = digest(JSON.writeValueAsString(Map.of("caseId", caseId, "callNote", callNote,
                "nights", ordered.stream().map(n -> List.of(n.stayDate().toString(), n.reportedProvisionStatus())).toList())));
        return Objects.requireNonNull(write.execute(transaction -> {
            User actor = user(principal);
            if (!"COUNSELOR".equals(actor.role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
            Context context = lockCase(caseId);
            Optional<Recorded> replay = jdbc.sql("""
                    SELECT details->>'requestHash' AS hash, details->>'response' AS response FROM case_actions
                    WHERE case_id=:caseKey AND action_type=:action AND details->>'idempotencyKey'=:key
                    """).param("caseKey", context.caseKey()).param("action", ACTION).param("key", key)
                    .query((rs, index) -> {
                        if (!hash.equals(rs.getString("hash"))) throw conflict("IDEMPOTENCY_KEY_REUSED");
                        return JSON.readValue(rs.getString("response"), Recorded.class);
                    }).optional();
            if (replay.isPresent()) return replay.orElseThrow();
            if ("CLOSED".equals(context.status())) throw conflict("CASE_CLOSED");
            Map<LocalDate, Night> stored = new LinkedHashMap<>();
            jdbc.sql("SELECT id,stay_date,fulfillment_status,fact_status FROM booking_nights WHERE booking_id=:booking FOR UPDATE")
                    .param("booking", context.bookingId())
                    .query((rs, index) -> new Night(rs.getLong("id"), rs.getObject("stay_date", LocalDate.class),
                            rs.getString("fulfillment_status"), rs.getString("fact_status")))
                    .list().forEach(night -> stored.put(night.stayDate(), night));
            if (!stored.keySet().containsAll(ordered.stream().map(Claim::stayDate).toList()))
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "STAY_DATE_OUT_OF_RANGE");

            OffsetDateTime now = now();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("protocol", PROTOCOL); payload.put("recordedBy", actor.subject());
            payload.put("nights", ordered.stream().map(n -> Map.of("stayDate", n.stayDate().toString(),
                    "reportedProvisionStatus", n.reportedProvisionStatus())).toList());
            long evidenceId = jdbc.sql("""
                    INSERT INTO evidence_records (booking_id,source_type,source_authenticated,raw_text,raw_payload,
                        occurred_at,received_at,payload_hash)
                    VALUES (:booking,'MANUAL_HOTEL',false,:note,CAST(:payload AS jsonb),:now,:now,:hash) RETURNING id
                    """).param("booking", context.bookingId()).param("note", callNote)
                    .param("payload", JSON.writeValueAsString(payload)).param("now", now).param("hash", hash)
                    .query(Long.class).single();
            List<NightResult> results = new ArrayList<>();
            for (Claim claim : ordered) results.add(apply(context.bookingId(), evidenceId, stored.get(claim.stayDate()), claim, actor.id(), now));

            // Evidence is sufficient only when every original night is a verified fact.
            boolean settled = jdbc.sql("SELECT bool_and(fact_status='VERIFIED') FROM booking_nights WHERE booking_id=:booking")
                    .param("booking", context.bookingId()).query(Boolean.class).single();
            String status = context.status();
            boolean taskCompleted = false;
            if (settled) {
                taskCompleted = jdbc.sql("""
                        UPDATE case_tasks SET status='DONE',completed_at=:now,completion_evidence_id=:evidence,
                            completion_note='호텔 직접 확인으로 전 숙박일 확인',updated_at=:now
                        WHERE case_id=:caseKey AND task_type='VERIFY_HOTEL' AND is_required AND status IN ('TODO','IN_PROGRESS')
                        """).param("now", now).param("evidence", evidenceId).param("caseKey", context.caseKey()).update() > 0;
                if ("WAITING".equals(status)) status = "IN_PROGRESS";
            } else {
                status = "WAITING";
            }
            if (!status.equals(context.status())) {
                jdbc.sql("UPDATE cases SET status=:status,version=version+1,updated_at=:now WHERE id=:caseKey")
                        .param("status", status).param("now", now).param("caseKey", context.caseKey()).update();
            }
            Recorded recorded = new Recorded(caseId, "EV-" + evidenceId, status, taskCompleted, results);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("protocol", PROTOCOL); details.put("idempotencyKey", key); details.put("requestHash", hash);
            details.put("previousStatus", context.status()); details.put("response", JSON.writeValueAsString(recorded));
            jdbc.sql("""
                    INSERT INTO case_actions (case_id,booking_id,actor_id,action_type,evidence_id,details,acted_at)
                    VALUES (:caseKey,:booking,:actor,:action,:evidence,CAST(:details AS jsonb),:now)
                    """).param("caseKey", context.caseKey()).param("booking", context.bookingId()).param("actor", actor.id())
                    .param("action", ACTION).param("evidence", evidenceId).param("details", JSON.writeValueAsString(details))
                    .param("now", now).update();
            return recorded;
        }));
    }

    /** Same rule as automated reconfirmation: a known claim settles an unconfirmed night, contradicts a verified one. */
    private NightResult apply(long booking, long evidenceId, Night night, Claim claim, long actor, OffsetDateTime now) {
        String reported = claim.reportedProvisionStatus();
        boolean known = !"UNKNOWN".equals(reported);
        boolean accept = known && "UNCONFIRMED".equals(night.fact());
        long assertion = jdbc.sql("""
                INSERT INTO evidence_nights (booking_id,evidence_id,booking_night_id,reported_status,review_status,reviewed_by,reviewed_at)
                VALUES (:booking,:evidence,:night,:reported,:review,:actor,:reviewedAt) RETURNING id
                """).param("booking", booking).param("evidence", evidenceId).param("night", night.id()).param("reported", reported)
                .param("review", accept ? "ACCEPTED" : "PENDING_REVIEW").param("actor", accept ? actor : null)
                .param("reviewedAt", accept ? now : null).query(Long.class).single();
        String fact = night.fact();
        if (accept) {
            jdbc.sql("UPDATE booking_nights SET fulfillment_status=:reported,fact_status='VERIFIED',accepted_assertion_id=:assertion,updated_at=:now WHERE id=:id")
                    .param("reported", reported).param("assertion", assertion).param("now", now).param("id", night.id()).update();
            fact = "VERIFIED";
        } else if (known && "VERIFIED".equals(night.fact()) && !reported.equals(night.fulfillment())) {
            jdbc.sql("UPDATE booking_nights SET fact_status='CONFLICT',updated_at=:now WHERE id=:id")
                    .param("now", now).param("id", night.id()).update();
            fact = "CONFLICT";
        }
        return new NightResult(claim.stayDate().toString(), reported, accept ? "ACCEPTED" : "PENDING_REVIEW", fact);
    }

    private Context lockCase(String caseCode) {
        long booking = jdbc.sql("SELECT booking_id FROM cases WHERE case_code=:code").param("code", caseCode)
                .query(Long.class).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND"));
        jdbc.sql("SELECT id FROM bookings WHERE id=:id FOR UPDATE").param("id", booking).query(Long.class).single();
        return jdbc.sql("SELECT id,status FROM cases WHERE case_code=:code AND booking_id=:booking FOR UPDATE")
                .param("code", caseCode).param("booking", booking)
                .query((rs, index) -> new Context(rs.getLong("id"), booking, rs.getString("status"))).single();
    }

    private User user(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        return jdbc.sql("SELECT id,role,subject_key FROM users WHERE is_active AND subject_key=:subject").param("subject", principal.getName())
                .query((rs, index) -> new User(rs.getLong("id"), rs.getString("role"), rs.getString("subject_key"))).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED"));
    }

    private OffsetDateTime now() { return clock.instant().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC); }
    private static ResponseStatusException bad(String code) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, code); }
    private static ResponseStatusException conflict(String code) { return new ResponseStatusException(HttpStatus.CONFLICT, code); }
    private static String digest(String text) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }

    public record Claim(LocalDate stayDate, String reportedProvisionStatus) {}
    public record NightResult(String stayDate, String reportedProvisionStatus, String reviewStatus, String factStatus) {}
    public record Recorded(String caseId, String evidenceId, String caseStatus, boolean verifyHotelTaskCompleted, List<NightResult> nights) {}
    private record User(long id, String role, String subject) {}
    private record Context(long caseKey, long bookingId, String status) {}
    private record Night(long id, LocalDate stayDate, String fulfillment, String fact) {}
}
