package com.csro.backend.incidents;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import com.csro.backend.risk.RiskRecheckJobs;

import tools.jackson.databind.json.JsonMapper;

/**
 * API-002 durable reconfirmation work, committed with the unverified customer report.
 * Claims and retries use short transactions. HTTP holds no locks; validated evidence
 * and fenced job completion commit together. Customer reports never manufacture facts.
 */
@Service
public class IncidentRecheckJobs {

    private static final String PROTOCOL = "incident-recheck-v1";
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
    private final RiskRecheckJobs riskJobs;

    @Autowired
    public IncidentRecheckJobs(BookingReconfirmationEvidenceService evidence, JdbcClient jdbc,
                              PlatformTransactionManager manager, RiskRecheckJobs riskJobs) {
        this(evidence, jdbc, manager, Clock.systemUTC(), RetryPolicy.defaults(), riskJobs);
    }

    public IncidentRecheckJobs(BookingReconfirmationEvidenceService evidence, JdbcClient jdbc,
                              PlatformTransactionManager manager, Clock clock, RetryPolicy retryPolicy) {
        this(evidence, jdbc, manager, clock, retryPolicy, null);
    }

    IncidentRecheckJobs(BookingReconfirmationEvidenceService evidence, JdbcClient jdbc,
                               PlatformTransactionManager manager, Clock clock, RetryPolicy retryPolicy,
                               RiskRecheckJobs riskJobs) {
        this.evidence = Objects.requireNonNull(evidence);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
        this.retryPolicy = Objects.requireNonNull(retryPolicy);
        this.riskJobs = riskJobs;
        this.write = new TransactionTemplate(Objects.requireNonNull(manager));
        this.write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Caller holds the booking lock; receipt and pending work commit together, without HTTP. */
    public String enqueue(long bookingId, long caseId, long reportId, String bookingCode) {
        requireWriteTransaction();
        if (bookingId < 1 || caseId < 1 || reportId < 1 || bookingCode == null || bookingCode.isBlank())
            throw new IllegalArgumentException("An incident receipt and its booking are required");
        boolean receiptMatches = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM incident_reports r JOIN bookings b ON b.id=r.booking_id
                    WHERE r.id=:report AND r.booking_id=:booking AND r.case_id=:case
                    AND b.booking_code=:code)
                """).param("report", reportId).param("booking", bookingId).param("case", caseId)
                .param("code", bookingCode).query(Boolean.class).single();
        if (!receiptMatches) throw new IllegalArgumentException("Incident receipt does not match booking and case");
        var active = jdbc.sql("""
                SELECT job_code FROM jobs WHERE booking_id=:booking AND kind='RECHECK'
                    AND status IN ('PENDING','RUNNING','WAITING') FOR UPDATE
                """).param("booking", bookingId).query(String.class).optional();
        if (active.isPresent()) return active.orElseThrow(); // Foreign ownership and request body remain intact.
        Frozen frozen = freeze(bookingId, bookingCode);
        String code = "INC-" + reportId + "-" + bookingId;
        Parameters parameters = new Parameters(PROTOCOL, code, reportId, bookingCode,
                frozen.scope(), frozen.unsupportedReason(), retryPolicy);
        OffsetDateTime now = now();
        jdbc.sql("""
                INSERT INTO jobs (job_code,booking_id,case_id,kind,trigger_type,status,parameters,
                    attempt_count,deadline_at,next_attempt_at,result_payload)
                VALUES (:code,:booking,:case,'RECHECK','CUSTOMER_REPORT','PENDING',CAST(:parameters AS jsonb),
                    0,:deadline,:now,CAST(:result AS jsonb))
                """).param("code",code).param("booking",bookingId).param("case",caseId)
                .param("parameters",json(parameters)).param("deadline",now.plusSeconds(retryPolicy.deadlineSeconds()))
                .param("now",now).param("result",json(JobResult.empty())).update();
        return code;
    }

    private Frozen freeze(long bookingId, String bookingCode) {
        var booking = jdbc.sql("""
                SELECT b.supplier_code,b.supplier_booking_id,b.supplier_status,b.check_in_date,b.check_out_date,
                    b.adults,b.rooms,h.hotel_code,h.data_source FROM bookings b JOIN hotels h ON h.id=b.hotel_id
                WHERE b.id=:id AND b.booking_code=:code
                """).param("id",bookingId).param("code",bookingCode).query((rs,row) -> new Booking(
                        rs.getString("supplier_code"),rs.getString("supplier_booking_id"),rs.getString("supplier_status"),
                        rs.getObject("check_in_date",LocalDate.class),rs.getObject("check_out_date",LocalDate.class),
                        rs.getInt("adults"),rs.getInt("rooms"),rs.getString("hotel_code"),rs.getString("data_source")))
                .single();
        if (!"MOCK_SUPPLIER".equals(booking.supplier()) || !"SIMULATED".equals(booking.source())
                || !"CONFIRMED".equals(booking.status())) return new Frozen(null,"UNSUPPORTED_BOOKING_SOURCE");
        List<LocalDate> nights = jdbc.sql("SELECT stay_date FROM booking_nights WHERE booking_id=:id ORDER BY stay_date")
                .param("id",bookingId).query(LocalDate.class).list();
        try {
            return new Frozen(new ReconfirmationScope(bookingCode,booking.supplierId(),booking.property(),
                    booking.status(),booking.checkIn(),booking.checkOut(),booking.adults(),booking.rooms(),nights),null);
        } catch (IllegalArgumentException invalid) {
            return new Frozen(null,"INVALID_BOOKING_NIGHTS");
        }
    }

    /** Executes at most one attempt. Terminal, leased, or not-yet-due jobs do no HTTP. */
    public Execution execute(String jobCode) {
        requireNoCallerTransaction();
        requireJobCode(jobCode);
        StoredJob observed = findOwned(jobCode, false)
                .orElseThrow(() -> new IllegalArgumentException("Owned incident RECHECK job not found"));
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
            var outcome = evidence.reconfirmAndApply(job.parameters().bookingCode(),
                    job.parameters().requestId(), job.parameters().scope(), job.id(),
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
        catch (RuntimeException unexpected) {
            // Preserve the receipt and retry a transient local failure without logging customer/provider bodies.
            result = AttemptResult.failure("WORKER", "LOCAL_PROCESSING_ERROR", null, true);
        }
        // Pending and failed attempts have no evidence to commit. Record their retry state separately.
        AttemptResult completed = result;
        return Objects.requireNonNull(write.execute(status -> finish(jobCode, claim.token(),
                job.retryCount(), completed)), "Job finalization returned no result");
    }

    /** Bounded own-job drain; linked ML jobs keep the original ML worker's ownership and fencing. */
    public List<Execution> runDue(int limit) {
        requireNoCallerTransaction();
        if (limit < 1 || limit > MAX_BATCH) throw new IllegalArgumentException("Job batch limit must be between 1 and 100");
        List<Due> due = jdbc.sql("""
                SELECT j.job_code,j.parameters->>'protocol' AS owner FROM jobs j
                WHERE j.kind='RECHECK' AND j.status IN ('PENDING','WAITING','RUNNING')
                  AND (j.parameters->>'protocol'=:owner OR (j.parameters->>'protocol'='risk-recheck-v1'
                    AND EXISTS(SELECT 1 FROM case_actions a WHERE a.booking_id=j.booking_id
                        AND a.action_type='CUSTOMER_REPORT_RECEIVED'
                        AND a.details->>'recheckJobCode'=j.job_code)))
                  AND (j.next_attempt_at IS NULL OR j.next_attempt_at<=:now
                    OR (j.status<>'RUNNING' AND j.deadline_at<=:now))
                ORDER BY COALESCE(j.next_attempt_at,j.created_at),j.id LIMIT :limit
                """).param("owner",PROTOCOL).param("now",now()).param("limit",limit)
                .query((rs,row)->new Due(rs.getString("job_code"),rs.getString("owner"))).list();
        List<Execution> results = new ArrayList<>();
        for (Due item : due) {
            if (PROTOCOL.equals(item.owner())) results.add(execute(item.code()));
            else if (riskJobs != null) {
                var result = riskJobs.execute(item.code());
                results.add(new Execution(result.jobCode(),result.bookingCode(),result.requestId(),
                        State.valueOf(result.state().name()),result.errorCode(),result.caseCode()));
            }
        }
        return List.copyOf(results);
    }

    private Claim claim(String code) {
        StoredJob observed = findOwned(code,false).orElseThrow();
        lockBooking(observed.bookingId());
        lockActiveCase(observed.bookingId());
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
        if (job.parameters().unsupportedReason() != null) {
            return new Claim(failBeforeAttempt(job,job.parameters().unsupportedReason(),now),null);
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
        StoredJob observed = findOwned(code,false).orElseThrow();
        lockBooking(observed.bookingId());
        lockActiveCase(observed.bookingId());
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
        if (next == State.FAILED) ensureManualTask(current.bookingId(),now);
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
        ensureManualTask(job.bookingId(),now);
        return job.with(State.FAILED, job.retryCount(), null, payload);
    }

    private void lockBooking(long bookingId) {
        jdbc.sql("SELECT id FROM bookings WHERE id=:id FOR UPDATE").param("id",bookingId).query(Long.class).single();
    }

    private Optional<Long> lockActiveCase(long bookingId) {
        return jdbc.sql("""
                SELECT id FROM cases WHERE booking_id=:booking AND status IN ('OPEN','IN_PROGRESS','WAITING')
                FOR UPDATE
                """).param("booking",bookingId).query(Long.class).optional();
    }

    /** Booking→case→job order is established by claim/finalization before this insert. */
    private void ensureManualTask(long bookingId, OffsetDateTime now) {
        var activeCase = lockActiveCase(bookingId);
        if (activeCase.isEmpty()) return;
        jdbc.sql("""
                INSERT INTO case_tasks (booking_id,case_id,task_type,status,is_required,due_at)
                SELECT :booking,:case,'VERIFY_HOTEL','TODO',true,:now
                WHERE NOT EXISTS(SELECT 1 FROM case_tasks WHERE case_id=:case AND task_type='VERIFY_HOTEL'
                    AND status IN ('TODO','IN_PROGRESS') AND is_required=true)
                """).param("booking",bookingId).param("case",activeCase.orElseThrow()).param("now",now).update();
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
                .orElseThrow(() -> new IllegalStateException("Claimed incident RECHECK job disappeared"));
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

    private static void validateOwned(StoredJob job) {
        Parameters p=job.parameters();
        if (p==null || !PROTOCOL.equals(p.protocol()) || p.reportId()<1 || p.bookingCode()==null
                || p.bookingCode().isBlank() || p.retryPolicy()==null || job.result()==null
                || !PROTOCOL.equals(job.result().protocol()) || job.result().history()==null
                || job.result().history().size()>10 || job.riskId()!=null
                || !job.jobCode().equals(p.requestId())
                || !job.jobCode().equals("INC-"+p.reportId()+"-"+job.bookingId())
                || job.retryCount()<0 || job.retryCount()>=p.retryPolicy().maxAttempts()
                || (p.scope()==null)==(p.unsupportedReason()==null)
                || (p.scope()!=null && !p.bookingCode().equals(p.scope().bookingCode())))
            throw new IllegalStateException("Invalid owned incident RECHECK snapshot");
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
        return new Execution(job.jobCode(), job.parameters().bookingCode(), job.parameters().requestId(),
                job.state(), last == null ? null : last.errorCode(), last == null ? null : last.caseCode());
    }

    private OffsetDateTime now() { return clock.instant().atOffset(ZoneOffset.UTC); }
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
            throw new IllegalStateException("Incident RECHECK enqueue requires the receipt write transaction");
        }
    }

    private static void requireJobCode(String code) {
        if (code == null || !code.matches("INC-[1-9][0-9]*-[1-9][0-9]*") || code.length() > 60) {
            throw new IllegalArgumentException("Invalid incident RECHECK job code");
        }
    }

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

    private record Parameters(String protocol,String requestId,long reportId,String bookingCode,
                              ReconfirmationScope scope,String unsupportedReason,RetryPolicy retryPolicy) {}
    private record Frozen(ReconfirmationScope scope,String unsupportedReason) {}
    private record Booking(String supplier,String supplierId,String status,LocalDate checkIn,LocalDate checkOut,
                           int adults,int rooms,String property,String source) {}
    private record Due(String code,String owner) {}
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
