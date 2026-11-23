package com.csro.backend.risk;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.csro.backend.reconfirmation.BookingReconfirmationEvidenceService;
import com.csro.backend.reconfirmation.BookingReconfirmationException;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.HotelPending;
import com.csro.backend.reconfirmation.ReconfirmationScope;

import tools.jackson.databind.json.JsonMapper;

/**
 * Durable, explicitly drained RECHECK jobs for selected synthetic ML assessments.
 * A pending job commits with its assessment; claiming and finishing use short,
 * separate transactions. After HTTP, successful finalization commits atomically with evidence.
 * This service neither schedules itself nor represents failed ML inference as a score.
 */
@Service
public class RiskRecheckJobs {

    private static final String PROTOCOL = "risk-recheck-v1";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String COLUMNS = """
            id, job_code, booking_id, risk_assessment_id, status, attempt_count,
            deadline_at, next_attempt_at, parameters::text AS parameters_json,
            result_payload::text AS result_json, parameters ->> 'protocol' AS owner
            """;
    private static final int MAX_BATCH = 100;

    private final BookingReconfirmationEvidenceService evidence;
    private final JdbcClient jdbc;
    private final TransactionTemplate write;
    private final Clock clock;
    private final RetryPolicy retryPolicy;

    @Autowired
    public RiskRecheckJobs(BookingReconfirmationEvidenceService evidence, JdbcClient jdbc,
                           PlatformTransactionManager manager) {
        this(evidence, jdbc, manager, Clock.systemUTC(), RetryPolicy.defaults());
    }

    public RiskRecheckJobs(BookingReconfirmationEvidenceService evidence, JdbcClient jdbc,
                           PlatformTransactionManager manager, Clock clock, RetryPolicy retryPolicy) {
        this.evidence = Objects.requireNonNull(evidence);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
        this.retryPolicy = Objects.requireNonNull(retryPolicy);
        this.write = new TransactionTemplate(Objects.requireNonNull(manager));
        this.write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Must participate in the assessment's existing write transaction. An active
     * job belonging to another workflow, or with a different frozen booking,
     * rejects this whole assessment instead of silently hijacking its work.
     */
    public List<JobRef> enqueueSelected(long assessmentId, RiskRequest request,
                                        RiskSelectionPolicy policy, double rawScore, String modelVersion, List<Scope> scopes) {
        requireWriteTransaction();
        if (assessmentId <= 0 || request == null || policy == null || scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("A selected assessment and booking scopes are required");
        }
        request.validate();
        if (!policy.selects(rawScore) || scopes.size() != request.features().targetConfirmedBookingCount()) {
            throw new IllegalArgumentException("Selected booking scopes do not match the assessment");
        }
        var bookingIds = new HashSet<Long>();
        var bookingCodes = new HashSet<String>();
        for (Scope scope : scopes) {
            validateScope(scope);
            if (!scope.hotelCode().equals(request.hotelId())
                    || !scope.checkInDate().equals(request.targetCheckInDate())
                    || !bookingIds.add(scope.bookingId()) || !bookingCodes.add(scope.bookingCode())) {
                throw new IllegalArgumentException("Invalid or duplicate selected booking scope");
            }
        }

        OffsetDateTime now = now();
        List<JobRef> refs = new ArrayList<>();
        // The caller locks bookings in this same order. Never acquire booking
        // locks while holding a worker's job lock.
        for (Scope scope : scopes.stream().sorted(Comparator.comparingLong(Scope::bookingId)).toList()) {
            Optional<StoredJob> active = activeJob(scope.bookingId());
            if (active.isPresent()) {
                refs.add(reusable(active.orElseThrow(), scope, assessmentId));
                continue;
            }
            String requestId = "RISK-" + assessmentId + "-" + scope.bookingId();
            Parameters parameters = new Parameters(PROTOCOL, requestId, scope,
                    new Selection(assessmentId, request, policy, rawScore, policy.threshold(),
                            modelVersion), retryPolicy);
            Optional<Long> inserted = jdbc.sql("""
                    INSERT INTO jobs (job_code, booking_id, risk_assessment_id, kind, trigger_type,
                        status, parameters, attempt_count, deadline_at, next_attempt_at, result_payload)
                    VALUES (:code, :booking, :assessment, 'RECHECK', 'SCHEDULED', 'PENDING',
                        CAST(:parameters AS jsonb), 0, :deadline, :now, CAST(:result AS jsonb))
                    ON CONFLICT DO NOTHING RETURNING id
                    """).param("code", requestId).param("booking", scope.bookingId())
                    .param("assessment", assessmentId).param("parameters", json(parameters))
                    .param("deadline", now.plusSeconds(retryPolicy.deadlineSeconds())).param("now", now)
                    .param("result", json(JobResult.empty())).query(Long.class).optional();
            if (inserted.isPresent()) {
                refs.add(new JobRef(requestId, scope.bookingCode(), requestId));
            } else {
                // ON CONFLICT is not an aborted transaction. Under READ COMMITTED
                // this statement can see the row that won the unique-index race.
                StoredJob winner = findOwned(requestId, false).or(() -> activeJob(scope.bookingId()))
                        .orElseThrow(() -> new IllegalStateException("RECHECK enqueue conflict could not be resolved"));
                refs.add(reusable(winner, scope, assessmentId));
            }
        }
        return List.copyOf(refs);
    }

    /** Executes at most one attempt. Terminal, leased, or not-yet-due jobs do no HTTP. */
    public Execution execute(String jobCode) {
        requireNoCallerTransaction();
        requireJobCode(jobCode);
        StoredJob observed = findOwned(jobCode, false)
                .orElseThrow(() -> new IllegalArgumentException("Owned risk RECHECK job not found"));
        Claim claim = Objects.requireNonNull(write.execute(status -> claim(jobCode)), "Job claim returned no result");
        if (claim.job() == null) {
            // Another short claim/finalization transaction holds this row. This
            // is a nonblocking observation, not authority to make an HTTP call.
            return view(observed);
        }
        StoredJob job = claim.job();
        if (claim.token() == null) {
            return view(job);
        }

        // No transaction or job/booking lock survives the claim transaction.
        requireNoCallerTransaction();
        AttemptResult result;
        try {
            var outcome = evidence.reconfirmAndApply(job.parameters().scope().bookingCode(),
                    job.parameters().requestId(), expectedScope(job.parameters().scope()), job.id(),
                    applied -> finish(jobCode, claim.token(), job.retryCount(), new AttemptResult(false, false,
                            null, null, null, applied.activeCaseCode(), applied.supplierEvidenceId(),
                            applied.hotelEvidenceId(), applied.replayed())));
            if (outcome == null || outcome.result() == null || outcome.result().hotel() == null) {
                throw new IllegalStateException("Reconfirmation returned no validated outcome");
            }
            if (!(outcome.result().hotel() instanceof HotelPending)) {
                return view(findOwned(jobCode, false).orElseThrow());
            }
            result = AttemptResult.failure("API_027", "HOTEL_PENDING", 202, true);
        } catch (StaleClaimException stale) {
            // The evidence transaction rolled back. Return the current owner's state.
            return view(findOwned(jobCode, false).orElseThrow());
        } catch (BookingReconfirmationException failure) {
            result = AttemptResult.failure(failure.step().name(), failure.kind().name(),
                    safeHttpStatus(failure.httpStatus()), retryable(failure));
        } catch (BookingReconfirmationEvidenceService.RequestBodyConflictException failure) {
            result = AttemptResult.failure("EVIDENCE", "REQUEST_BODY_CONFLICT", null, false);
        }
        // Pending and failed attempts have no evidence to commit. Record their retry state separately.
        AttemptResult completed = result;
        return Objects.requireNonNull(write.execute(status -> finish(jobCode, claim.token(),
                job.retryCount(), completed)), "Job finalization returned no result");
    }

    /** Manual bounded drain only: no polling loop, sleeps, cron, or foreign jobs. */
    public List<Execution> runDue(int limit) {
        requireNoCallerTransaction();
        if (limit < 1 || limit > MAX_BATCH) {
            throw new IllegalArgumentException("Job batch limit must be between 1 and 100");
        }
        OffsetDateTime now = now();
        List<String> codes = jdbc.sql("""
                SELECT job_code FROM jobs
                WHERE kind = 'RECHECK' AND parameters ->> 'protocol' = :owner
                    AND status IN ('PENDING','WAITING','RUNNING')
                    AND (next_attempt_at IS NULL OR next_attempt_at <= :now
                         OR (status <> 'RUNNING' AND deadline_at <= :now))
                ORDER BY COALESCE(next_attempt_at, created_at), id LIMIT :limit
                """).param("owner", PROTOCOL).param("now", now).param("limit", limit)
                .query(String.class).list();
        List<Execution> result = new ArrayList<>();
        for (String code : codes) {
            result.add(execute(code));
        }
        return List.copyOf(result);
    }

    private Claim claim(String code) {
        Optional<StoredJob> candidate = findOwned(code, true);
        if (candidate.isEmpty()) {
            return new Claim(null, null);
        }
        StoredJob job = candidate.orElseThrow();
        validateOwned(job);
        OffsetDateTime now = now();
        if (terminal(job.state()) || (job.state() == State.RUNNING && future(job.nextAttemptAt(), now))) {
            return new Claim(job, null);
        }
        if (job.deadlineAt() == null) {
            throw new IllegalStateException("Owned RECHECK job has no processing deadline");
        }
        if (!job.deadlineAt().isAfter(now)) {
            return new Claim(failBeforeAttempt(job, "DEADLINE_EXCEEDED", now), null);
        }
        if (job.state() != State.RUNNING && future(job.nextAttemptAt(), now)) {
            return new Claim(job, null);
        }
        int nextRetry = job.state() == State.PENDING ? job.retryCount() : job.retryCount() + 1;
        if (nextRetry >= job.parameters().retryPolicy().maxAttempts()) {
            return new Claim(failBeforeAttempt(job, "ATTEMPTS_EXHAUSTED", now), null);
        }
        List<Attempt> history = new ArrayList<>(job.result().history());
        if (job.state() == State.RUNNING) {
            history.add(new Attempt(job.retryCount() + 1, now, "WORKER", "LEASE_EXPIRED", null));
        }
        String token = UUID.randomUUID().toString();
        JobResult claiming = new JobResult(PROTOCOL, token, bounded(history), job.result().last(), false);
        jdbc.sql("""
                UPDATE jobs SET status = 'RUNNING', attempt_count = :retry,
                    next_attempt_at = :lease, finished_at = NULL, result_payload = CAST(:result AS jsonb),
                    updated_at = :now WHERE id = :id
                """).param("retry", nextRetry).param("lease", now.plusSeconds(job.parameters().retryPolicy().leaseSeconds()))
                .param("result", json(claiming)).param("now", now).param("id", job.id()).update();
        return new Claim(job.with(State.RUNNING, nextRetry, now.plusSeconds(job.parameters().retryPolicy().leaseSeconds()), claiming), token);
    }

    private Execution finish(String code, String token, int retryCount, AttemptResult result) {
        StoredJob current = findOwnedBlocking(code);
        validateOwned(current);
        if (current.state() != State.RUNNING || current.retryCount() != retryCount
                || !token.equals(current.result().claimToken())) {
            if (!result.failure()) throw new StaleClaimException();
            return view(current); // A later claimant owns finalization; never overwrite it.
        }
        OffsetDateTime now = now();
        boolean exhausted = retryCount + 1 >= current.parameters().retryPolicy().maxAttempts();
        boolean expired = !current.deadlineAt().isAfter(now);
        State next = !result.failure() ? State.COMPLETED
                : result.retryable() && !exhausted && !expired ? State.WAITING : State.FAILED;
        String finalError = next == State.FAILED && result.retryable()
                ? expired ? "DEADLINE_EXCEEDED" : "ATTEMPTS_EXHAUSTED" : result.errorCode();
        List<Attempt> history = new ArrayList<>(current.result().history());
        if (result.failure()) {
            history.add(new Attempt(retryCount + 1, now, result.step(), result.errorCode(), result.httpStatus()));
        }
        LastOutcome last = new LastOutcome(finalError, result.step(), result.httpStatus(), result.caseCode(),
                result.supplierEvidenceId(), result.hotelEvidenceId(), result.replayed());
        JobResult payload = new JobResult(PROTOCOL, null, bounded(history), last, next == State.FAILED);
        OffsetDateTime nextAt = next == State.WAITING
                ? earlier(now.plusSeconds(current.parameters().retryPolicy().backoffSeconds()), current.deadlineAt()) : null;
        jdbc.sql("""
                UPDATE jobs SET status = :status, case_id = CASE WHEN CAST(:caseCode AS text) IS NULL THEN case_id
                        ELSE (SELECT id FROM cases WHERE case_code = :caseCode AND booking_id = :booking) END,
                    next_attempt_at = :next, finished_at = :finished, updated_at = :now,
                    result_payload = CAST(:result AS jsonb)
                WHERE id = :id AND status = 'RUNNING' AND attempt_count = :retry
                    AND result_payload ->> 'claimToken' = :token
                """).param("status", next.name()).param("caseCode", result.caseCode()).param("booking", current.bookingId())
                .param("next", nextAt).param("finished", terminal(next) ? now : null).param("now", now)
                .param("result", json(payload)).param("id", current.id()).param("retry", retryCount).param("token", token)
                .update();
        return view(current.with(next, retryCount, nextAt, payload));
    }

    private StoredJob failBeforeAttempt(StoredJob job, String error, OffsetDateTime now) {
        List<Attempt> history = new ArrayList<>(job.result().history());
        if (job.state() == State.RUNNING) {
            history.add(new Attempt(job.retryCount() + 1, now, "WORKER", "LEASE_EXPIRED", null));
        }
        JobResult payload = new JobResult(PROTOCOL, null, bounded(history),
                new LastOutcome(error, "WORKER", null, job.result().last() == null ? null : job.result().last().caseCode(),
                        null, null, false), true);
        jdbc.sql("""
                UPDATE jobs SET status = 'FAILED', next_attempt_at = NULL, finished_at = :now,
                    updated_at = :now, result_payload = CAST(:result AS jsonb) WHERE id = :id
                """).param("now", now).param("result", json(payload)).param("id", job.id()).update();
        return job.with(State.FAILED, job.retryCount(), null, payload);
    }

    private Optional<StoredJob> activeJob(long bookingId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM jobs WHERE booking_id = :booking AND kind = 'RECHECK'
                    AND status IN ('PENDING','RUNNING','WAITING') FOR UPDATE
                """).param("booking", bookingId).query(this::map).optional();
    }

    private Optional<StoredJob> findOwned(String code, boolean skipLocked) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM jobs WHERE job_code = :code AND kind = 'RECHECK'
                    AND parameters ->> 'protocol' = :owner
                """ + (skipLocked ? " FOR UPDATE SKIP LOCKED" : ""))
                .param("code", code).param("owner", PROTOCOL).query(this::map).optional();
    }

    private StoredJob findOwnedBlocking(String code) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM jobs WHERE job_code = :code AND kind = 'RECHECK'
                    AND parameters ->> 'protocol' = :owner FOR UPDATE
                """).param("code", code).param("owner", PROTOCOL).query(this::map).optional()
                .orElseThrow(() -> new IllegalStateException("Claimed risk RECHECK job disappeared"));
    }

    private StoredJob map(ResultSet rs, int row) throws SQLException {
        boolean owned = PROTOCOL.equals(rs.getString("owner"));
        // Never parse or interpret another team's payload when checking the
        // existing active-RECHECK unique constraint.
        Parameters parameters = owned ? JSON.readValue(rs.getString("parameters_json"), Parameters.class) : null;
        String result = rs.getString("result_json");
        JobResult payload = owned && result != null ? JSON.readValue(result, JobResult.class) : null;
        return new StoredJob(rs.getLong("id"), rs.getString("job_code"), rs.getLong("booking_id"),
                rs.getObject("risk_assessment_id", Long.class), State.valueOf(rs.getString("status")),
                rs.getInt("attempt_count"), rs.getObject("deadline_at", OffsetDateTime.class),
                rs.getObject("next_attempt_at", OffsetDateTime.class), parameters, payload);
    }

    private JobRef reusable(StoredJob job, Scope scope, long assessmentId) {
        if (job.parameters() == null) {
            throw new IllegalStateException("Booking already has another workflow's active RECHECK job");
        }
        validateOwned(job);
        if (job.riskId() != assessmentId || !scope.equals(job.parameters().scope())) {
            throw new IllegalStateException("Active RECHECK booking scope differs from the selected assessment");
        }
        return new JobRef(job.jobCode(), scope.bookingCode(), job.parameters().requestId());
    }

    private static void validateOwned(StoredJob job) {
        Parameters p = job.parameters();
        if (p == null || !PROTOCOL.equals(p.protocol()) || p.selection() == null || p.retryPolicy() == null
                || job.result() == null || !PROTOCOL.equals(job.result().protocol())
                || job.result().history() == null || job.result().history().size() > 10
                || job.riskId() == null || p.selection().assessmentId() != job.riskId()
                || p.scope() == null || p.scope().bookingId() != job.bookingId()
                || !job.jobCode().equals(p.requestId())
                || !job.jobCode().equals("RISK-" + job.riskId() + "-" + job.bookingId())
                || job.retryCount() < 0 || job.retryCount() >= p.retryPolicy().maxAttempts()) {
            throw new IllegalStateException("Invalid owned RECHECK job snapshot");
        }
        validateScope(p.scope());
        p.selection().request().validate();
        if (!p.scope().hotelCode().equals(p.selection().request().hotelId())
                || !p.scope().checkInDate().equals(p.selection().request().targetCheckInDate())
                || p.selection().policy() == null || !p.selection().policy().selects(p.selection().rawScore())
                || Double.compare(p.selection().threshold(), p.selection().policy().threshold()) != 0
                || blank(p.selection().modelVersion())) {
            throw new IllegalStateException("Invalid owned RECHECK selection snapshot");
        }
    }

    private static void validateScope(Scope scope) {
        if (scope == null || scope.bookingId() <= 0 || blank(scope.bookingCode()) || blank(scope.hotelCode())
                || !"MOCK_SUPPLIER".equals(scope.supplierCode()) || blank(scope.supplierBookingId())
                || scope.checkInDate() == null || scope.checkOutDate() == null
                || !scope.checkOutDate().isAfter(scope.checkInDate()) || scope.adults() < 1 || scope.rooms() < 1) {
            throw new IllegalArgumentException("Invalid simulated booking scope");
        }
    }

    private static ReconfirmationScope expectedScope(Scope scope) {
        return new ReconfirmationScope(scope.bookingCode(), scope.supplierBookingId(), scope.hotelCode(),
                "CONFIRMED", scope.checkInDate(), scope.checkOutDate(), scope.adults(), scope.rooms(),
                scope.checkInDate().datesUntil(scope.checkOutDate()).toList());
    }

    static boolean retryable(BookingReconfirmationException failure) {
        return switch (failure.kind()) {
            case TIMEOUT, CONNECTION_FAILURE, UNAVAILABLE -> true;
            case HTTP_ERROR -> failure.httpStatus() != null
                    && (failure.httpStatus() == 429 || failure.httpStatus() >= 500 && failure.httpStatus() <= 599);
            default -> false;
        };
    }

    private static Integer safeHttpStatus(Integer status) {
        return status != null && status >= 100 && status <= 599 ? status : null;
    }

    private static Execution view(StoredJob job) {
        validateOwned(job);
        LastOutcome last = job.result().last();
        return new Execution(job.jobCode(), job.parameters().scope().bookingCode(), job.parameters().requestId(),
                job.state(), last == null ? null : last.errorCode(), last == null ? null : last.caseCode());
    }

    private OffsetDateTime now() { return clock.instant().atOffset(ZoneOffset.UTC); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean terminal(State state) { return state == State.COMPLETED || state == State.FAILED; }
    private static boolean future(OffsetDateTime value, OffsetDateTime now) { return value != null && value.isAfter(now); }
    private static OffsetDateTime earlier(OffsetDateTime left, OffsetDateTime right) { return left.isBefore(right) ? left : right; }
    private static List<Attempt> bounded(List<Attempt> history) {
        return List.copyOf(history.subList(Math.max(0, history.size() - 10), history.size()));
    }
    private static String json(Object value) { return JSON.writeValueAsString(value); }

    private static void requireNoCallerTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("RECHECK worker must run outside a caller transaction");
        }
    }

    private static void requireWriteTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("RECHECK enqueue requires the assessment write transaction");
        }
    }

    private static void requireJobCode(String code) {
        if (code == null || !code.matches("RISK-[1-9][0-9]*-[1-9][0-9]*") || code.length() > 60) {
            throw new IllegalArgumentException("Invalid risk RECHECK job code");
        }
    }

    public record Scope(long bookingId, String bookingCode, String hotelCode, String supplierCode,
                        String supplierBookingId, LocalDate checkInDate, LocalDate checkOutDate, int adults, int rooms) {}
    public record JobRef(String jobCode, String bookingCode, String requestId) {}
    public enum State { PENDING, RUNNING, WAITING, COMPLETED, FAILED }
    public record Execution(String jobCode, String bookingCode, String requestId,
                            State state, String errorCode, String caseCode) {}

    /** Local prototype processing bounds, frozen into each job; not a shared service SLA. */
    public record RetryPolicy(int maxAttempts, long backoffSeconds, long leaseSeconds, long deadlineSeconds) {
        public RetryPolicy {
            if (maxAttempts < 1 || maxAttempts > 10 || backoffSeconds < 1 || backoffSeconds > 3600
                    || leaseSeconds < 1 || leaseSeconds > 3600 || deadlineSeconds < leaseSeconds
                    || deadlineSeconds > 86400) {
                throw new IllegalArgumentException("Invalid bounded RECHECK retry policy");
            }
        }
        public static RetryPolicy defaults() { return new RetryPolicy(3, 30, 60, 900); }
    }

    private record Selection(long assessmentId, RiskRequest request, RiskSelectionPolicy policy,
                             double rawScore, double threshold, String modelVersion) {}
    private record Parameters(String protocol, String requestId, Scope scope, Selection selection, RetryPolicy retryPolicy) {}
    private record Attempt(int executionNumber, OffsetDateTime at, String step, String errorCode, Integer httpStatus) {}
    private record LastOutcome(String errorCode, String step, Integer httpStatus, String caseCode,
                               Long supplierEvidenceId, Long hotelEvidenceId, boolean replayed) {}
    private record JobResult(String protocol, String claimToken, List<Attempt> history,
                             LastOutcome last, boolean manualRequired) {
        static JobResult empty() { return new JobResult(PROTOCOL, null, List.of(), null, false); }
    }
    private record StoredJob(long id, String jobCode, long bookingId, Long riskId, State state,
                             int retryCount, OffsetDateTime deadlineAt, OffsetDateTime nextAttemptAt,
                             Parameters parameters, JobResult result) {
        StoredJob with(State next, int retry, OffsetDateTime nextAt, JobResult payload) {
            return new StoredJob(id, jobCode, bookingId, riskId, next, retry, deadlineAt, nextAt, parameters, payload);
        }
    }
    private static final class StaleClaimException extends RuntimeException {}

    private record Claim(StoredJob job, String token) {}
    private record AttemptResult(boolean failure, boolean retryable, String step, String errorCode,
                                 Integer httpStatus, String caseCode, Long supplierEvidenceId,
                                 Long hotelEvidenceId, boolean replayed) {
        static AttemptResult failure(String step, String error, Integer httpStatus, boolean retryable) {
            return new AttemptResult(true, retryable, step, error, httpStatus, null, null, null, false);
        }
    }
}
