package com.csro.backend.replacements;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.csro.backend.replacements.ReplacementSupplierClient.FrozenRequest;
import com.csro.backend.replacements.ReplacementSupplierClient.Outcome;
import com.csro.backend.replacements.ReplacementSupplierClient.Stay;

/** Durable, fenced booking commands. Provider calls never run inside the business transaction. */
@Service
public class ReplacementService {
    private static final String PROTOCOL = "replacement-booking-v1";
    private static final String WORK_ACTION = "REPLACEMENT_WORK";
    private static final int MAX_POSTS = 3, MAX_GETS = 8;
    private static final long DEADLINE_SECONDS = 300, LEASE_SECONDS = 45;
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final String ROW = """
            SELECT r.*,c.case_code,c.booking_id,b.customer_id,s.selection_code,o.offer_code,q.quote_code
            FROM replacement_requests r JOIN cases c ON c.id=r.case_id
            JOIN bookings b ON b.id=c.booking_id
            JOIN alternative_selections s ON s.id=r.selection_id AND s.case_id=r.case_id
            JOIN offers o ON o.id=s.offer_id AND o.case_id=s.case_id
            JOIN alternative_quotes q ON q.id=s.quote_id AND q.case_id=s.case_id
            """;
    private final JdbcClient jdbc;
    private final ReplacementSupplierClient supplier;
    private final TransactionTemplate write, read;
    private final Clock clock;

    @Autowired
    public ReplacementService(JdbcClient jdbc, PlatformTransactionManager manager, ReplacementSupplierClient supplier) {
        this(jdbc, manager, supplier, Clock.systemUTC());
    }

    public ReplacementService(JdbcClient jdbc, PlatformTransactionManager manager, ReplacementSupplierClient supplier, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc); this.supplier = Objects.requireNonNull(supplier);
        this.clock = Objects.requireNonNull(clock);
        write = new TransactionTemplate(Objects.requireNonNull(manager));
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read = new TransactionTemplate(manager);
        read.setReadOnly(true); read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public Acceptance request(Principal principal, String caseId, String selectionId, String clientKey,
                              OffsetDateTime effectiveAt, boolean syntheticDemo) {
        validCase(caseId);
        if (!identifier(selectionId, 60) || clientKey == null || !clientKey.matches("[A-Za-z0-9._:-]{1,100}") || effectiveAt == null)
            throw bad("INVALID_REPLACEMENT_REQUEST");
        String requestId = "BRQ-" + digest(caseId + "\u0000" + clientKey);
        String requestHash = publicHash(selectionId);
        return Objects.requireNonNull(write.execute(transaction -> {
            User actor = user(principal);
            if (!actor.counselor()) throw forbidden();
            Context context = lockCase(caseId);
            Optional<Stored> prior = find(requestId, false);
            if (prior.isPresent()) {
                Stored row = prior.orElseThrow();
                if (row.caseKey() != context.caseKey() || !requestHash.equals(row.requestHash()))
                    throw conflict("IDEMPOTENCY_KEY_REUSED");
                return acceptance(row);
            }
            if ("CLOSED".equals(context.status())) throw conflict("CASE_CLOSED");
            Optional<String> selectedRequest = jdbc.sql("""
                    SELECT r.request_code FROM replacement_requests r JOIN alternative_selections s
                        ON s.id=r.selection_id AND s.case_id=r.case_id
                    WHERE r.case_id=:caseKey AND s.selection_code=:selection
                    """).param("caseKey", context.caseKey()).param("selection", selectionId).query(String.class).optional();
            if (selectedRequest.isPresent()) throw new SelectionAlreadyRequested(selectedRequest.orElseThrow());
            boolean locked = jdbc.sql("SELECT EXISTS(SELECT 1 FROM replacement_requests WHERE case_id=:caseKey AND status IN ('PROCESSING','RESULT_UNKNOWN','CONFIRMED'))")
                    .param("caseKey", context.caseKey()).query(Boolean.class).single();
            if (locked) throw conflict("REPLACEMENT_LOCKED");
            Selected selected = selection(context, selectionId, effectiveAt, syntheticDemo);
            OffsetDateTime now = now();
            String guest = "GUEST-" + digest("customer\u0000" + context.customerId());
            Scope scope = new Scope(context.caseCode(), context.caseKey(), context.bookingId(), selectionId,
                    selected.offerId(), selected.quoteId(), selected.propertyId(), selected.propertyName(), selected.stay(),
                    selected.amount(), "KRW", guest, effectiveAt, syntheticDemo);
            FrozenRequest frozen = frozen(requestId, scope);
            String body = JSON.writeValueAsString(wire(frozen));
            jdbc.sql("""
                    INSERT INTO replacement_requests (request_code,selection_id,idempotency_key,request_hash,status,
                        expected_amount,currency,request_payload,created_at,updated_at,case_id,requested_by)
                    VALUES (:code,:selection,:code,:hash,'PROCESSING',:amount,'KRW',CAST(:body AS jsonb),:now,:now,:caseKey,:actor)
                    """).param("code", requestId).param("selection", selected.id()).param("hash", requestHash)
                    .param("amount", selected.amount()).param("body", body).param("now", now)
                    .param("caseKey", context.caseKey()).param("actor", actor.id()).update();
            Work initial = new Work(PROTOCOL, requestId, scope, payloadHash(frozen), "READY", 0, 0, null, null,
                    now, now.plusSeconds(DEADLINE_SECONDS), false, false, null, null);
            work(context.caseKey(), context.bookingId(), actor.id(), initial, now);
            return new Acceptance(caseId, selectionId, requestId, now);
        }));
    }

    /** API-016 reads saved state only; it cannot claim work, poll a provider or reset a budget. */
    public String view(Principal principal, String requestId) {
        validRequestId(requestId);
        return Objects.requireNonNull(read.execute(transaction -> {
            User actor = user(principal);
            if (!actor.counselor() && !"CUSTOMER".equals(actor.role())) throw forbidden();
            Stored row = find(requestId, false).filter(r -> actor.counselor() || r.customerId() == actor.id())
                    .orElseThrow(ReplacementService::notFound);
            Work state = latest(row);
            Scope scope = state.scope();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("replacementBookingId", row.requestId()); body.put("caseId", row.caseCode());
            body.put("selectionId", row.selectionId()); body.put("offerId", row.offerId()); body.put("quoteId", row.quoteId());
            body.put("status", row.status()); body.put("stay", scope.stay());
            body.put("expectedPrice", price(row.expectedAmount())); body.put("supplierBookingId", row.supplierBookingId());
            body.put("confirmedPrice", "CONFIRMED".equals(row.status()) ? price(row.confirmedAmount()) : null);
            body.put("confirmedAt", row.confirmedAt()); body.put("failureCode", row.failureCode());
            body.put("currentPrice", currentPrice(row)); body.put("manualRequired", state.manualRequired());
            body.put("nextCheckAt", state.nextAttemptAt()); body.put("updatedAt", row.updatedAt());
            body.put("isSynthetic", true); body.put("effectiveAt", scope.effectiveAt());
            body.put("clockMode", scope.syntheticDemo() ? "SYNTHETIC_DEMO" : "REAL_TIME");
            return JSON.writeValueAsString(body);
        }));
    }

    /** Executes one claimed HTTP attempt; stale executors may never publish business effects. */
    public Execution execute(String requestId) {
        noCallerTransaction(); validRequestId(requestId);
        Claim claim = Objects.requireNonNull(write.execute(transaction -> claim(requestId)));
        if (claim.token() == null) return execution(claim.row(), claim.state());
        Outcome outcome = null;
        ReplacementSupplierException problem = null;
        try {
            FrozenRequest request = frozen(requestId, claim.state().scope());
            outcome = claim.post() ? supplier.create(request) : supplier.lookup(request);
        } catch (ReplacementSupplierException failure) {
            problem = failure;
        } catch (RuntimeException unexpected) {
            // A client implementation failure is uncertainty, never proof of no reservation.
            problem = null;
        }
        Outcome received = outcome;
        ReplacementSupplierException failure = problem;
        try {
            return Objects.requireNonNull(write.execute(transaction -> finish(claim, received, failure)));
        } catch (DataIntegrityViolationException incompatibleResult) {
            // A duplicate supplier ID or a failed invariant is not proof of no
            // provider reservation. The entire publication already rolled back.
            return Objects.requireNonNull(write.execute(transaction -> {
                Stored row = lock(claim.row().requestId()); Work current = latest(row);
                if (terminal(row.status()) || !Objects.equals(claim.token(), current.claimToken())) return execution(row, current);
                Work observed = received == null ? current : current.withObservation(received.rawJson());
                return executionOf(manual(row, observed, "RESULT_STORAGE_CONFLICT", now()));
            }));
        }
    }

    public List<Execution> runDue(int limit) {
        noCallerTransaction();
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Replacement drain limit must be 1..100");
        List<String> ids = jdbc.sql("""
                SELECT r.request_code FROM replacement_requests r
                JOIN LATERAL (SELECT a.details FROM case_actions a WHERE a.case_id=r.case_id AND a.action_type=:action
                    AND a.details->>'protocol'=:protocol AND a.details->>'requestId'=r.request_code ORDER BY a.id DESC LIMIT 1) w ON true
                WHERE r.status IN ('PROCESSING','RESULT_UNKNOWN') AND w.details->>'manualRequired'='false'
                    AND ((w.details->>'leaseUntil') IS NULL OR CAST(w.details->>'leaseUntil' AS timestamptz)<=:now)
                    AND ((w.details->>'nextAttemptAt') IS NULL OR CAST(w.details->>'nextAttemptAt' AS timestamptz)<=:now)
                ORDER BY r.updated_at,r.id LIMIT :limit
                """).param("action", WORK_ACTION).param("protocol", PROTOCOL).param("now", now()).param("limit", limit)
                .query(String.class).list();
        List<Execution> result = new ArrayList<>();
        for (String id : ids) result.add(execute(id));
        return List.copyOf(result);
    }

    /** Explicit internal recovery permits only GET of the same BRQ, with a new bounded read budget. */
    public Execution resume(String requestId) {
        noCallerTransaction(); validRequestId(requestId);
        return Objects.requireNonNull(write.execute(transaction -> {
            Stored row = lock(requestId); Work previous = latest(row);
            if (terminal(row.status())) return execution(row, previous);
            OffsetDateTime now = now();
            if (previous.leaseUntil() != null && previous.leaseUntil().isAfter(now)) {
                // Preserve the active fence and its response, while preventing
                // a concurrent 030/404 result from scheduling another POST.
                if (previous.readOnlyRecovery()) return execution(row, previous);
                Work recovery = previous.asReadOnlyRecovery();
                work(row.caseKey(), row.bookingId(), null, recovery, now);
                return execution(row, recovery);
            }
            Work next = new Work(PROTOCOL, requestId, previous.scope(), previous.payloadHash(), "LOOKUP",
                    previous.postAttempts(), 0, null, null, now, now.plusSeconds(DEADLINE_SECONDS), false, true, null, null);
            updateStatus(row, "RESULT_UNKNOWN", now);
            work(row.caseKey(), row.bookingId(), null, next, now);
            return execution(row.withStatus("RESULT_UNKNOWN", now), next);
        }));
    }

    private Claim claim(String requestId) {
        Stored row = lock(requestId); Work previous = latest(row); OffsetDateTime now = now();
        if (terminal(row.status()) || previous.manualRequired()
                || previous.leaseUntil() != null && previous.leaseUntil().isAfter(now)
                || previous.nextAttemptAt() != null && previous.nextAttemptAt().isAfter(now))
            return new Claim(row, previous, null, false);
        if (!validFrozen(row, previous) || !previous.deadlineAt().isAfter(now))
            return manual(row, previous, "RECOVERY_REQUIRED", now);
        boolean post = !previous.readOnlyRecovery() && ("READY".equals(previous.phase()) || "RETRY_POST".equals(previous.phase()));
        if (post && previous.postAttempts() >= MAX_POSTS || !post && previous.getAttempts() >= MAX_GETS)
            return manual(row, previous, "ATTEMPT_BUDGET_EXHAUSTED", now);
        String token = UUID.randomUUID().toString();
        Work claimed = new Work(PROTOCOL, requestId, previous.scope(), previous.payloadHash(), post ? "POST_IN_FLIGHT" : "GET_IN_FLIGHT",
                previous.postAttempts() + (post ? 1 : 0), previous.getAttempts() + (post ? 0 : 1), token,
                now.plusSeconds(LEASE_SECONDS), null, previous.deadlineAt(), false, previous.readOnlyRecovery(), previous.lastError(), null);
        if (!post) { updateStatus(row, "RESULT_UNKNOWN", now); row = row.withStatus("RESULT_UNKNOWN", now); }
        work(row.caseKey(), row.bookingId(), null, claimed, now);
        return new Claim(row, claimed, token, post);
    }

    private Claim manual(Stored row, Work previous, String reason, OffsetDateTime now) {
        Work stopped = new Work(PROTOCOL, row.requestId(), previous.scope(), previous.payloadHash(), "MANUAL",
                previous.postAttempts(), previous.getAttempts(), null, null, null, previous.deadlineAt(), true,
                previous.readOnlyRecovery(), reason, previous.observationJson());
        updateStatus(row, "RESULT_UNKNOWN", now); work(row.caseKey(), row.bookingId(), null, stopped, now);
        return new Claim(row.withStatus("RESULT_UNKNOWN", now), stopped, null, false);
    }

    private Execution finish(Claim claim, Outcome outcome, ReplacementSupplierException failure) {
        Stored row = lock(claim.row().requestId()); Work current = latest(row); OffsetDateTime now = now();
        if (terminal(row.status()) || !Objects.equals(claim.token(), current.claimToken())) return execution(row, current);
        if (!validFrozen(row, current)) return executionOf(manual(row, current, "SNAPSHOT_MISMATCH", now));
        String status = "RESULT_UNKNOWN", error = "SUPPLIER_RESULT_UNVERIFIED";
        boolean known = outcome != null && Set.of("CONFIRMED", "FAILED", "PROCESSING").contains(outcome.status());
        if (known && "CONFIRMED".equals(outcome.status())) {
            known = outcome.confirmedAmount() != null && outcome.confirmedAmount() == row.expectedAmount()
                    && identifier(outcome.supplierBookingId(), 80) && outcome.confirmedAt() != null;
        }
        if (known && "FAILED".equals(outcome.status())) known = identifier(outcome.failureCode(), 60)
                && !"IDEMPOTENCY_KEY_REUSED".equals(outcome.failureCode()) && outcome.supplierBookingId() == null;
        if (known && outcome.rawJson() != null) {
            try { JSON.readTree(outcome.rawJson()); } catch (RuntimeException invalid) { known = false; }
        } else if (known && !"PROCESSING".equals(outcome.status())) known = false;
        if (known) status = outcome.status();
        if (known && terminal(status)) return publish(row, current, outcome, now);
        boolean notFound = !claim.post() && failure != null
                && failure.kind() == ReplacementSupplierException.Kind.REQUEST_NOT_FOUND
                && Integer.valueOf(404).equals(failure.httpStatus()) && "BOOKING_REQUEST_NOT_FOUND".equals(failure.code());
        boolean retryPost = notFound && !current.readOnlyRecovery() && current.postAttempts() < MAX_POSTS;
        if (known) error = null;
        else if (notFound) error = "BOOKING_REQUEST_NOT_FOUND";
        else if (failure != null) error = "SUPPLIER_LOOKUP_UNAVAILABLE";
        boolean exhausted = !current.deadlineAt().isAfter(now) || (!retryPost && current.getAttempts() >= MAX_GETS);
        boolean mismatch = outcome != null && !known || failure != null
                && (failure.kind() == ReplacementSupplierException.Kind.UNKNOWN
                        && (Integer.valueOf(200).equals(failure.httpStatus()) || Integer.valueOf(201).equals(failure.httpStatus()))
                    || Set.of(ReplacementSupplierException.Kind.INVALID_REQUEST, ReplacementSupplierException.Kind.NO_SERVICE_TOKEN,
                            ReplacementSupplierException.Kind.TRANSACTION_ACTIVE, ReplacementSupplierException.Kind.RESPONSE_TOO_LARGE).contains(failure.kind()));
        boolean manual = exhausted || mismatch;
        OffsetDateTime nextAt = manual ? null : earlier(now.plusSeconds(backoff(current.getAttempts())), current.deadlineAt());
        Work next = new Work(PROTOCOL, row.requestId(), current.scope(), current.payloadHash(), manual ? "MANUAL" : retryPost ? "RETRY_POST" : "LOOKUP",
                current.postAttempts(), current.getAttempts(), null, null, nextAt, current.deadlineAt(), manual,
                current.readOnlyRecovery(), error, outcome == null ? null : outcome.rawJson());
        updateStatus(row, status, now); work(row.caseKey(), row.bookingId(), null, next, now);
        return execution(row.withStatus(status, now), next);
    }

    private Execution publish(Stored row, Work current, Outcome outcome, OffsetDateTime now) {
        boolean confirmed = "CONFIRMED".equals(outcome.status());
        jdbc.sql("""
                UPDATE replacement_requests SET status=:status,confirmed_amount=:amount,supplier_booking_id=:supplier,
                    confirmed_at=:confirmedAt,failure_code=:failure,response_payload=CAST(:response AS jsonb),updated_at=:now WHERE id=:id
                """).param("status", outcome.status()).param("amount", confirmed ? outcome.confirmedAmount() : null)
                .param("supplier", confirmed ? outcome.supplierBookingId() : null).param("confirmedAt", confirmed ? outcome.confirmedAt() : null)
                .param("failure", confirmed ? null : outcome.failureCode()).param("response", outcome.rawJson()).param("now", now).param("id", row.id()).update();
        Scope scope = current.scope();
        String message = confirmed ? scope.propertyName() + " 가상 예약이 확정되었습니다. 예약번호 " + outcome.supplierBookingId()
                + " · " + scope.stay().checkInDate() + "~" + scope.stay().checkOutDate()
                + ". 실제 입실 확인과 원예약 후속 처리는 별개이며 사건이 자동 종료되지 않습니다."
                : "예약이 생성되지 않은 실패가 확인되었습니다. 새 견적을 확인하고 고객에게 다시 제안해 주세요.";
        jdbc.sql("INSERT INTO chat_messages (message_code,case_id,sender_type,message_type,body,sent_at) VALUES (:code,:caseKey,'SYSTEM','SYSTEM',:body,:now)")
                .param("code", "MSG-" + UUID.randomUUID()).param("caseKey", row.caseKey()).param("body", message).param("now", now).update();
        if (confirmed) {
            requiredTask(row, "CHECKIN_CONFIRM", true, now);
            requiredTask(row, "ORIGINAL_FOLLOWUP", false, now);
        }
        Work terminal = new Work(PROTOCOL, row.requestId(), scope, current.payloadHash(), "TERMINAL", current.postAttempts(),
                current.getAttempts(), null, null, null, current.deadlineAt(), false, current.readOnlyRecovery(), null, outcome.rawJson());
        work(row.caseKey(), row.bookingId(), null, terminal, now);
        return new Execution(row.requestId(), outcome.status(), false, terminal.phase(), terminal.postAttempts(), terminal.getAttempts(), null);
    }

    private void requiredTask(Stored row, String type, boolean unfinishedOnly, OffsetDateTime now) {
        jdbc.sql("""
                INSERT INTO case_tasks (case_id,task_type,status,is_required,booking_id,created_at,updated_at)
                SELECT :caseKey,:type,'TODO',true,:booking,:now,:now WHERE NOT EXISTS
                    (SELECT 1 FROM case_tasks WHERE case_id=:caseKey AND task_type=:type AND is_required
                        AND (:all OR status IN ('TODO','IN_PROGRESS')))
                """).param("caseKey", row.caseKey()).param("type", type).param("booking", row.bookingId())
                .param("now", now).param("all", !unfinishedOnly).update();
    }

    private Selected selection(Context context, String selectionId, OffsetDateTime effectiveAt, boolean syntheticDemo) {
        String saved = jdbc.sql("""
                SELECT jsonb_build_object('id',s.id,'offerId',o.offer_code,'offerStatus',o.status,'quote',to_jsonb(q),
                    'propertyId',h.hotel_code,'jobParameters',j.parameters,'jobResult',j.result_payload,'jobStatus',j.status,
                    'jobCreatedAt',j.created_at,'mode',a.details->>'coverageMode')::text
                FROM alternative_selections s JOIN offers o ON o.id=s.offer_id AND o.case_id=s.case_id
                JOIN offer_items i ON i.offer_id=s.offer_id AND i.quote_id=s.quote_id AND i.case_id=s.case_id
                JOIN alternative_quotes q ON q.id=s.quote_id AND q.case_id=s.case_id JOIN hotels h ON h.id=q.hotel_id
                JOIN jobs j ON j.id=q.search_job_id AND j.case_id=q.case_id AND j.booking_id=:booking AND j.kind='QUOTE_SEARCH'
                JOIN LATERAL (SELECT details FROM case_actions WHERE case_id=s.case_id AND action_type='OFFER_SENT'
                    AND details->>'protocol'='offer-selection-v1' AND details->>'offerId'=o.offer_code ORDER BY id DESC LIMIT 1) a ON true
                WHERE s.case_id=:caseKey AND s.selection_code=:code AND s.superseded_at IS NULL
                """).param("caseKey", context.caseKey()).param("booking", context.bookingId()).param("code", selectionId)
                .query(String.class).optional().orElseThrow(() -> conflict("CURRENT_SELECTION_CHANGED"));
        try {
            JsonNode value = JSON.readTree(saved), q = value.path("quote"), raw = q.path("source_payload"), p = raw.path("price");
            JsonNode parameters = value.path("jobParameters"), jobScope = parameters.path("scope");
            if (!"ACTIVE".equals(text(value, "offerStatus"))) throw conflict("CURRENT_SELECTION_CHANGED");
            Stay stay = new Stay(LocalDate.parse(text(q, "check_in_date")), LocalDate.parse(text(q, "check_out_date")),
                    Math.toIntExact(number(q, "adults")), Math.toIntExact(number(q, "rooms")));
            require("COMPLETED".equals(text(value, "jobStatus")) && "quote-search-v1".equals(text(parameters, "protocol"))
                    && parameters.path("syntheticDemo").isBoolean() && parameters.path("syntheticDemo").booleanValue() == syntheticDemo
                    && context.stay().equals(stay(jobScope.path("stay"))) && number(jobScope, "bookingId") == context.bookingId()
                    && number(jobScope, "caseId") == context.caseKey() && stay.adults() == context.stay().adults()
                    && stay.rooms() == context.stay().rooms() && "KRW".equals(context.currency())
                    && "SIMULATED_SUPPLIER".equals(text(q, "source_code")) && "KRW".equals(text(q, "currency"))
                    && text(q, "quote_code").equals(text(raw, "quoteId")) && text(value, "propertyId").equals(text(raw, "propertyId"))
                    && text(q, "property_name").equals(text(raw, "propertyName"))
                    && text(q, "room_type_code").equals(text(raw, "roomTypeId")) && text(q, "rate_plan_code").equals(text(raw, "ratePlanId"))
                    && text(q, "source_code").equals(text(raw, "source")) && text(q, "currency").equals(text(p, "currency"))
                    && number(q, "base_amount") == number(p, "baseAmount") && number(q, "mandatory_fees_amount") == number(p, "mandatoryFeesAmount")
                    && number(q, "total_amount") == number(p, "totalAmount") && number(q, "total_amount") >= 0
                    && number(q, "available_room_count") == number(raw, "availableRoomCount") && number(q, "available_room_count") >= stay.rooms()
                    && number(q, "max_adults") == number(raw, "maxAdults") && number(q, "max_adults") * stay.rooms() >= stay.adults()
                    && time(q, "checked_at").toInstant().equals(time(raw, "checkedAt").toInstant())
                    && time(q, "expires_at").toInstant().equals(time(raw, "expiresAt").toInstant())
                    && !time(q, "checked_at").isAfter(effectiveAt));
            if (!time(q, "expires_at").isAfter(effectiveAt)) throw conflict("QUOTE_EXPIRED");
            String mode = text(value, "mode");
            if ("FULL_STAY".equals(mode)) require(context.stay().equals(stay));
            else validatePartial(context, stay, mode);
            boolean sourceMatches = false;
            for (JsonNode group : value.path("jobResult").path("groups")) {
                if (!stay(group.path("search").path("stay")).equals(stay)) continue;
                JsonNode response = JSON.readTree(text(group.path("search"), "responseJson"));
                if (!mode.equals(text(group, "mode")) || !stay(response.path("stay")).equals(stay)) continue;
                for (JsonNode item : response.path("quotes")) if (item.equals(raw)) sourceMatches = true;
            }
            require(sourceMatches);
            boolean staleFailure = jdbc.sql("""
                    SELECT EXISTS(SELECT 1 FROM replacement_requests r JOIN alternative_selections s ON s.id=r.selection_id AND s.case_id=r.case_id
                    JOIN offers o ON o.id=s.offer_id JOIN alternative_quotes q ON q.id=s.quote_id
                    WHERE r.case_id=:caseKey AND r.status='FAILED' AND
                        (o.offer_code=:offer OR q.search_job_id>=:job OR r.updated_at>=CAST(:created AS timestamptz)))
                    """).param("caseKey", context.caseKey()).param("offer", text(value, "offerId"))
                    .param("job", number(q, "search_job_id")).param("created", text(value, "jobCreatedAt")).query(Boolean.class).single();
            require(!staleFailure);
            return new Selected(number(value, "id"), text(value, "offerId"), text(q, "quote_code"), text(value, "propertyId"),
                    text(q, "property_name"), stay, number(q, "total_amount"));
        } catch (ResponseStatusException expected) { throw expected; }
        catch (RuntimeException invalid) { throw conflict("QUOTE_INVALID"); }
    }

    private void validatePartial(Context context, Stay stay, String mode) {
        require("VERIFIED_FAILURE_ONLY".equals(mode) && !stay.checkInDate().isBefore(context.stay().checkInDate())
                && !stay.checkOutDate().isAfter(context.stay().checkOutDate()));
        List<LocalDate> dates = jdbc.sql("SELECT stay_date FROM booking_nights WHERE booking_id=:booking AND fact_status='VERIFIED' AND fulfillment_status='UNAVAILABLE' ORDER BY stay_date")
                .param("booking", context.bookingId()).query(LocalDate.class).list();
        require(stay.checkInDate().datesUntil(stay.checkOutDate()).allMatch(dates::contains)
                && !dates.contains(stay.checkInDate().minusDays(1)) && !dates.contains(stay.checkOutDate()));
    }

    private User user(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        return jdbc.sql("SELECT id,role FROM users WHERE is_active AND subject_key=:subject").param("subject", principal.getName())
                .query((rs, index) -> new User(rs.getLong("id"), rs.getString("role"))).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED"));
    }

    private Context lockCase(String caseCode) {
        long booking = jdbc.sql("SELECT booking_id FROM cases WHERE case_code=:code").param("code", caseCode)
                .query(Long.class).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND"));
        Context context = jdbc.sql("""
                SELECT b.customer_id,b.check_in_date,b.check_out_date,b.adults,b.rooms,b.currency FROM bookings b WHERE b.id=:id FOR UPDATE
                """).param("id", booking).query((rs, index) -> new Context(0, caseCode, booking, rs.getLong("customer_id"), null,
                        new Stay(rs.getObject("check_in_date", LocalDate.class), rs.getObject("check_out_date", LocalDate.class),
                                rs.getInt("adults"), rs.getInt("rooms")), rs.getString("currency"))).single();
        return jdbc.sql("SELECT id,status FROM cases WHERE case_code=:code AND booking_id=:booking FOR UPDATE")
                .param("code", caseCode).param("booking", booking).query((rs, index) -> new Context(rs.getLong("id"), caseCode,
                        booking, context.customerId(), rs.getString("status"), context.stay(), context.currency())).single();
    }

    private Stored lock(String requestId) {
        Stored observed = find(requestId, false).orElseThrow(ReplacementService::notFound);
        lockCase(observed.caseCode());
        return find(requestId, true).orElseThrow(ReplacementService::notFound);
    }

    private Optional<Stored> find(String requestId, boolean lock) {
        return jdbc.sql(ROW + " WHERE r.request_code=:code" + (lock ? " FOR UPDATE OF r" : ""))
                .param("code", requestId).query(this::stored).optional();
    }

    private Stored stored(ResultSet rs, int index) throws SQLException {
        return new Stored(rs.getLong("id"), rs.getString("request_code"), rs.getLong("case_id"), rs.getLong("booking_id"),
                rs.getString("case_code"), rs.getLong("customer_id"), rs.getString("selection_code"), rs.getString("offer_code"),
                rs.getString("quote_code"), rs.getString("request_hash"), rs.getString("status"), rs.getLong("expected_amount"),
                rs.getObject("confirmed_amount", Long.class), rs.getString("currency"), rs.getString("supplier_booking_id"),
                rs.getObject("confirmed_at", OffsetDateTime.class), rs.getString("failure_code"), rs.getString("request_payload"),
                rs.getString("response_payload"), rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private Work latest(Stored row) {
        String json = jdbc.sql("SELECT details::text FROM case_actions WHERE case_id=:caseKey AND action_type=:action AND details->>'protocol'=:protocol AND details->>'requestId'=:code ORDER BY id DESC LIMIT 1")
                .param("caseKey", row.caseKey()).param("action", WORK_ACTION).param("protocol", PROTOCOL).param("code", row.requestId())
                .query(String.class).optional().orElseThrow(() -> new IllegalStateException("Replacement processing record missing"));
        return JSON.readValue(json, Work.class);
    }

    private boolean validFrozen(Stored row, Work state) {
        try {
            Scope s = state.scope(); FrozenRequest request = frozen(row.requestId(), s);
            return PROTOCOL.equals(state.protocol()) && row.requestId().equals(state.requestId())
                    && s.caseKey() == row.caseKey() && s.bookingId() == row.bookingId() && s.caseId().equals(row.caseCode())
                    && s.selectionId().equals(row.selectionId()) && s.offerId().equals(row.offerId()) && s.quoteId().equals(row.quoteId())
                    && s.amount() == row.expectedAmount() && s.currency().equals(row.currency())
                    && publicHash(s.selectionId()).equals(row.requestHash()) && payloadHash(request).equals(state.payloadHash())
                    && JSON.readTree(row.requestJson()).equals(JSON.readTree(JSON.writeValueAsString(wire(request))));
        } catch (RuntimeException invalid) { return false; }
    }

    private void work(long caseKey, long booking, Long actor, Work state, OffsetDateTime at) {
        jdbc.sql("INSERT INTO case_actions (case_id,booking_id,actor_id,action_type,details,acted_at) VALUES (:caseKey,:booking,:actor,:action,CAST(:details AS jsonb),:now)")
                .param("caseKey", caseKey).param("booking", booking).param("actor", actor).param("action", WORK_ACTION)
                .param("details", JSON.writeValueAsString(state)).param("now", at).update();
    }

    private void updateStatus(Stored row, String status, OffsetDateTime at) {
        jdbc.sql("UPDATE replacement_requests SET status=:status,updated_at=:now WHERE id=:id")
                .param("status", status).param("now", at).param("id", row.id()).update();
    }

    private static Object currentPrice(Stored row) {
        if (!"FAILED".equals(row.status()) || !"PRICE_CHANGED".equals(row.failureCode()) || row.responseJson() == null) return null;
        JsonNode result = JSON.readTree(row.responseJson());
        JsonNode amount = result.path("currentTotalAmount");
        return amount.isIntegralNumber() && amount.canConvertToLong() && amount.longValue() >= 0 && "KRW".equals(result.path("currency").asString(""))
                ? price(amount.longValue()) : null;
    }
    private static FrozenRequest frozen(String id, Scope scope) { return new FrozenRequest(id, scope.quoteId(), scope.propertyId(), scope.stay(), scope.amount(), scope.currency(), scope.guestReference()); }
    private static Map<String, Object> wire(FrozenRequest request) { return Map.of("quoteId", request.quoteId(), "expectedTotalAmount", request.expectedAmount(), "currency", request.currency(), "guestReference", request.guestReference()); }
    private static Map<String, Object> price(long amount) { return Map.of("totalAmount", amount, "currency", "KRW"); }
    private static String payloadHash(FrozenRequest r) { return digest(JSON.writeValueAsString(List.of(r.quoteId(), r.expectedAmount(), r.currency(), r.guestReference()))); }
    private static String publicHash(String selectionId) { return digest(JSON.writeValueAsString(Map.of("selectionId", selectionId))); }
    private static Acceptance acceptance(Stored row) { return new Acceptance(row.caseCode(), row.selectionId(), row.requestId(), row.createdAt()); }
    private static Execution execution(Stored row, Work state) { return new Execution(row.requestId(), row.status(), state.manualRequired(), state.phase(), state.postAttempts(), state.getAttempts(), state.nextAttemptAt()); }
    private static Execution executionOf(Claim claim) { return execution(claim.row(), claim.state()); }
    private static boolean terminal(String status) { return "CONFIRMED".equals(status) || "FAILED".equals(status); }
    private static long backoff(int gets) { return Math.min(30, 2L << Math.min(Math.max(gets - 1, 0), 4)); }
    private static OffsetDateTime earlier(OffsetDateTime a, OffsetDateTime b) { return a.isBefore(b) ? a : b; }
    private OffsetDateTime now() { return clock.instant().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC); }
    private static String text(JsonNode value, String field) { JsonNode item = value.path(field); require(item.isString() && !item.stringValue().isBlank()); return item.stringValue(); }
    private static long number(JsonNode value, String field) { JsonNode item = value.path(field); require(item.isIntegralNumber() && item.canConvertToLong()); return item.longValue(); }
    private static OffsetDateTime time(JsonNode value, String field) { return OffsetDateTime.parse(text(value, field)); }
    private static Stay stay(JsonNode value) { return new Stay(LocalDate.parse(text(value, "checkInDate")), LocalDate.parse(text(value, "checkOutDate")), Math.toIntExact(number(value, "adults")), Math.toIntExact(number(value, "rooms"))); }
    private static void require(boolean valid) { if (!valid) throw conflict("QUOTE_INVALID"); }
    private static boolean identifier(String id, int max) { return id != null && !id.isBlank() && id.length() <= max && id.chars().noneMatch(Character::isISOControl); }
    private static void validCase(String id) { if (id == null || !id.matches("C-[0-9]{3,38}")) throw bad("INVALID_CASE_ID"); }
    private static void validRequestId(String id) { if (id == null || !id.matches("BRQ-[0-9a-f]{64}")) throw bad("INVALID_REPLACEMENT_ID"); }
    private static ResponseStatusException bad(String code) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, code); }
    private static ResponseStatusException conflict(String code) { return new ResponseStatusException(HttpStatus.CONFLICT, code); }
    private static ResponseStatusException forbidden() { return new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE"); }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "REPLACEMENT_BOOKING_NOT_FOUND"); }
    private static String digest(String text) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
    private static void noCallerTransaction() { if (TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isSynchronizationActive()) throw new IllegalStateException("Replacement provider work requires no caller transaction"); }

    public static final class SelectionAlreadyRequested extends ResponseStatusException {
        private final String replacementBookingId;
        public SelectionAlreadyRequested(String id) { super(HttpStatus.CONFLICT, "SELECTION_ALREADY_REQUESTED"); replacementBookingId = id; }
        public String replacementBookingId() { return replacementBookingId; }
    }
    public record Acceptance(String caseId, String selectionId, String replacementBookingId, OffsetDateTime acceptedAt) {}
    public record Execution(String requestId, String status, boolean manualRequired, String phase, int postAttempts, int getAttempts, OffsetDateTime nextCheckAt) {}
    private record User(long id, String role) { boolean counselor() { return "COUNSELOR".equals(role); } }
    private record Context(long caseKey, String caseCode, long bookingId, long customerId, String status, Stay stay, String currency) {}
    private record Selected(long id, String offerId, String quoteId, String propertyId, String propertyName, Stay stay, long amount) {}
    private record Scope(String caseId, long caseKey, long bookingId, String selectionId, String offerId, String quoteId,
                         String propertyId, String propertyName, Stay stay, long amount, String currency, String guestReference,
                         OffsetDateTime effectiveAt, boolean syntheticDemo) {}
    private record Work(String protocol, String requestId, Scope scope, String payloadHash, String phase, int postAttempts, int getAttempts,
                        String claimToken, OffsetDateTime leaseUntil, OffsetDateTime nextAttemptAt, OffsetDateTime deadlineAt,
                        boolean manualRequired, boolean readOnlyRecovery, String lastError, String observationJson) {
        Work withObservation(String raw) { return new Work(protocol, requestId, scope, payloadHash, phase, postAttempts, getAttempts,
                claimToken, leaseUntil, nextAttemptAt, deadlineAt, manualRequired, readOnlyRecovery, lastError, raw); }
        Work asReadOnlyRecovery() { return new Work(protocol, requestId, scope, payloadHash, phase, postAttempts, getAttempts,
                claimToken, leaseUntil, nextAttemptAt, deadlineAt, manualRequired, true, lastError, observationJson); }
    }
    private record Claim(Stored row, Work state, String token, boolean post) {}
    private record Stored(long id, String requestId, long caseKey, long bookingId, String caseCode, long customerId, String selectionId,
                          String offerId, String quoteId, String requestHash, String status, long expectedAmount, Long confirmedAmount,
                          String currency, String supplierBookingId, OffsetDateTime confirmedAt, String failureCode, String requestJson,
                          String responseJson, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        Stored withStatus(String next, OffsetDateTime at) { return new Stored(id, requestId, caseKey, bookingId, caseCode, customerId, selectionId,
                offerId, quoteId, requestHash, next, expectedAmount, confirmedAmount, currency, supplierBookingId, confirmedAt, failureCode,
                requestJson, responseJson, createdAt, at); }
    }
}
