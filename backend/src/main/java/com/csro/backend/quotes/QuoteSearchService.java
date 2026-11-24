package com.csro.backend.quotes;

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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.csro.backend.quotes.QuoteContracts.Quote;
import com.csro.backend.quotes.QuoteContracts.RankInput;
import com.csro.backend.quotes.QuoteContracts.Ranking;
import com.csro.backend.quotes.QuoteContracts.SearchInput;
import com.csro.backend.quotes.QuoteContracts.SearchResult;
import com.csro.backend.quotes.QuoteContracts.Stay;

/**
 * API-009/010 command/query core. Search and ranking HTTP calls run outside short
 * booking -> case -> job transactions. A saved quote is never changed by a retry.
 * This service does not send offers, reserve rooms or change night/case/task facts.
 */
@Service
public class QuoteSearchService {
    private static final String PROTOCOL = "quote-search-v1";
    private static final long LEASE_SECONDS = 45;
    private static final long DEADLINE_SECONDS = 900;
    private static final String JOB_COLUMNS = """
            id, job_code, booking_id, case_id, status, parameters::text AS parameters_json,
            result_payload::text AS result_json, deadline_at, next_attempt_at
            """;

    private final QuoteSupplierClient supplier;
    private final QuoteRankingClient ranking;
    private final JdbcClient jdbc;
    private final TransactionTemplate write;
    private final TransactionTemplate read;
    private final Clock clock;

    @Autowired
    public QuoteSearchService(QuoteSupplierClient supplier, QuoteRankingClient ranking,
                              JdbcClient jdbc, PlatformTransactionManager manager) {
        this(supplier, ranking, jdbc, manager, Clock.systemUTC());
    }

    public QuoteSearchService(QuoteSupplierClient supplier, QuoteRankingClient ranking,
                              JdbcClient jdbc, PlatformTransactionManager manager, Clock processingClock) {
        this.supplier = Objects.requireNonNull(supplier);
        this.ranking = Objects.requireNonNull(ranking);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(processingClock);
        this.write = new TransactionTemplate(Objects.requireNonNull(manager));
        this.write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.read = new TransactionTemplate(manager);
        this.read.setReadOnly(true);
        this.read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public Acceptance request(Principal principal, String caseId, SearchOptions options,
                              String idempotencyKey, OffsetDateTime effectiveAt) {
        return request(principal, caseId, options, idempotencyKey, effectiveAt, false);
    }

    /** syntheticDemo is supplied by the guarded server configuration, never by a request body. */
    public Acceptance request(Principal principal, String caseId, SearchOptions options,
                              String idempotencyKey, OffsetDateTime effectiveAt, boolean syntheticDemo) {
        validateCaseId(caseId);
        validateOptions(options, idempotencyKey, effectiveAt);
        String code = "QS-" + digest(caseId + "\u0000" + idempotencyKey).substring(0, 56);
        String requestHash = digest(json(options));
        return Objects.requireNonNull(write.execute(transaction -> {
            authorize(principal);
            long bookingId = bookingId(caseId);
            lockBooking(bookingId);
            CaseRow caseRow = lockCase(caseId, bookingId);
            Optional<StoredJob> previous = findJob(code, true);
            if (previous.isPresent()) {
                StoredJob existing = previous.orElseThrow();
                if (existing.caseId() != caseRow.id() || !existing.parameters().requestHash().equals(requestHash))
                    throw conflict("IDEMPOTENCY_KEY_REUSED");
                return new Acceptance(caseId, code, "PENDING");
            }
            if ("CLOSED".equals(caseRow.status())) throw conflict("CASE_CLOSED");
            boolean active = jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM jobs WHERE case_id = :caseId AND kind = 'QUOTE_SEARCH'
                        AND status IN ('PENDING','RUNNING','WAITING'))
                    """).param("caseId", caseRow.id()).query(Boolean.class).single();
            if (active) throw conflict("QUOTE_SEARCH_IN_PROGRESS");
            Scope scope = scope(caseId, caseRow.id(), bookingId);
            if (!"MOCK_SUPPLIER".equals(scope.supplierCode()) || !"KRW".equals(scope.currency()))
                throw conflict("QUOTE_SCOPE_UNSUPPORTED");
            Parameters parameters = new Parameters(PROTOCOL, requestHash, options, scope,
                    effectiveAt, syntheticDemo);
            OffsetDateTime now = now();
            jdbc.sql("""
                    INSERT INTO jobs (job_code, booking_id, case_id, kind, trigger_type, status,
                        parameters, attempt_count, deadline_at, next_attempt_at, result_payload)
                    VALUES (:code, :booking, :caseId, 'QUOTE_SEARCH', 'COUNSELOR', 'PENDING',
                        CAST(:parameters AS jsonb), 0, :deadline, :now, CAST(:result AS jsonb))
                    """).param("code", code).param("booking", bookingId).param("caseId", caseRow.id())
                    .param("parameters", json(parameters)).param("deadline", now.plusSeconds(DEADLINE_SECONDS))
                    .param("now", now).param("result", json(JobResult.empty())).update();
            return new Acceptance(caseId, code, "PENDING");
        }));
    }

    /** A repeatable, read-only snapshot. It never creates a search or contacts either Python service. */
    public String view(Principal principal, String caseId) {
        validateCaseId(caseId);
        return Objects.requireNonNull(read.execute(transaction -> {
            authorize(principal);
            long caseKey = jdbc.sql("SELECT id FROM cases WHERE case_code = :caseId")
                    .param("caseId", caseId).query(Long.class).optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND"));
            Optional<StoredJob> latest = jdbc.sql("SELECT " + JOB_COLUMNS + """
                    FROM jobs WHERE case_id = :caseId AND kind = 'QUOTE_SEARCH'
                        AND parameters ->> 'protocol' = :protocol ORDER BY id DESC LIMIT 1
                    """).param("caseId", caseKey).param("protocol", PROTOCOL).query(this::storedJob).optional();
            Map<String, Object> body = new LinkedHashMap<>();
            OffsetDateTime effective = latest.map(this::effectiveNow).orElse(null);
            body.put("caseId", caseId);
            body.put("isSynthetic", true);
            body.put("effectiveAt", effective == null ? null : timestamp(effective));
            body.put("clockMode", latest.map(job -> job.parameters().syntheticDemo()
                    ? "SYNTHETIC_DEMO" : "REAL_TIME").orElse("REAL_TIME"));
            body.put("searchJob", latest.map(job -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("searchJobId", job.code()); item.put("status", job.status());
                item.put("errorCode", job.result().errorCode());
                return item;
            }).orElse(null));
            body.put("groups", latest.map(job -> job.result().groups().stream()
                    .map(group -> groupView(job, group, effective)).toList()).orElse(List.of()));
            return json(body);
        }));
    }

    public Execution execute(String jobCode) {
        requireNoCallerTransaction();
        if (jobCode == null || !jobCode.matches("QS-[0-9a-f]{56}"))
            throw new IllegalArgumentException("Invalid quote search job code");
        StoredJob observed = findJob(jobCode, false)
                .orElseThrow(() -> new IllegalArgumentException("Quote search job not found"));
        Claim claim = Objects.requireNonNull(write.execute(transaction -> claim(observed)));
        if (claim.token() == null) return execution(claim.job());
        try {
            StoredJob job = claim.job();
            List<Group> groups = job.result().groups();
            if (!"QUOTES_SAVED".equals(job.result().stage())) {
                groups = search(job);
                List<Group> fetched = groups;
                write.executeWithoutResult(transaction -> saveQuotes(job, claim.token(), fetched));
            }
            // All candidates have committed before they become API-025 inputs.
            List<Group> ranked = new ArrayList<>();
            for (Group group : groups) ranked.add(rank(job, group));
            List<Group> completed = List.copyOf(ranked);
            return Objects.requireNonNull(write.execute(transaction -> finish(job, claim.token(), completed)));
        } catch (StaleClaim stale) {
            return execution(findJob(jobCode, false).orElseThrow());
        } catch (WorkflowFailure failure) {
            return fail(observed, claim.token(), failure.code);
        } catch (QuoteIntegrationException failure) {
            return fail(observed, claim.token(), failure.step().name() + "_" + failure.kind().name());
        } catch (RuntimeException failure) {
            return fail(observed, claim.token(), "INTERNAL_ERROR");
        }
    }

    /** Explicit bounded drain; scheduling is owned by the caller. Foreign jobs are never claimed. */
    public List<Execution> runDue(int limit) {
        requireNoCallerTransaction();
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Quote search limit must be 1..100");
        List<String> codes = jdbc.sql("""
                SELECT job_code FROM jobs WHERE kind = 'QUOTE_SEARCH'
                    AND parameters ->> 'protocol' = :protocol AND status IN ('PENDING','RUNNING')
                    AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
                ORDER BY created_at, id LIMIT :limit
                """).param("protocol", PROTOCOL).param("now", now()).param("limit", limit)
                .query(String.class).list();
        return codes.stream().map(this::execute).toList();
    }

    private Claim claim(StoredJob observed) {
        StoredJob job = lock(observed);
        OffsetDateTime now = now();
        if (terminal(job.status()) || ("RUNNING".equals(job.status()) && job.nextAt() != null
                && job.nextAt().isAfter(now))) return new Claim(job, null);
        if (job.deadline() == null || !job.deadline().isAfter(now)) {
            JobResult result = new JobResult(job.result().stage(), null, job.result().groups(), "DEADLINE_EXCEEDED");
            update(job, "FAILED", result, null, now);
            return new Claim(job.with("FAILED", result), null);
        }
        String token = UUID.randomUUID().toString();
        JobResult result = new JobResult(job.result().stage(), token, job.result().groups(), null);
        jdbc.sql("""
                UPDATE jobs SET status = 'RUNNING', attempt_count = attempt_count + 1,
                    next_attempt_at = :lease, updated_at = :now, result_payload = CAST(:result AS jsonb)
                WHERE id = :id
                """).param("lease", now.plusSeconds(LEASE_SECONDS)).param("now", now())
                .param("result", json(result)).param("id", job.id()).update();
        return new Claim(job.with("RUNNING", result), token);
    }

    private List<Group> search(StoredJob job) {
        requireNoCallerTransaction();
        Scope scope = job.parameters().scope();
        SearchResult full = supplier.search(input(job, scope.stay(), 0));
        Group whole = group("FULL_STAY", full, scope);
        validateEffectiveAt(job, full);
        if (!full.quotes().isEmpty()) return List.of(whole);
        List<Stay> failed = failureStays(scope);
        if (failed.isEmpty()) return List.of(whole);
        List<Group> groups = new ArrayList<>();
        for (int index = 0; index < failed.size(); index++) {
            SearchResult partial = supplier.search(input(job, failed.get(index), index + 1));
            validateEffectiveAt(job, partial);
            groups.add(group("VERIFIED_FAILURE_ONLY", partial, scope));
        }
        return List.copyOf(groups);
    }

    private SearchInput input(StoredJob job, Stay stay, int index) {
        SearchOptions options = job.parameters().options();
        return new SearchInput(job.code() + "-" + index, job.parameters().scope().hotelCode(), stay,
                options.radiusMeters(), options.maxAverageNightlyAmount());
    }

    private static Group group(String mode, SearchResult result, Scope scope) {
        List<LocalDate> unresolved = scope.nights().stream().filter(night -> !"VERIFIED".equals(night.factStatus())
                || "UNKNOWN".equals(night.fulfillmentStatus())).map(Night::stayDate).toList();
        return new Group(mode, result, unresolved, null, null);
    }

    private static List<Stay> failureStays(Scope scope) {
        List<LocalDate> failures = scope.nights().stream().filter(night -> "VERIFIED".equals(night.factStatus())
                && "UNAVAILABLE".equals(night.fulfillmentStatus())).map(Night::stayDate).sorted().toList();
        List<Stay> result = new ArrayList<>();
        for (int index = 0; index < failures.size();) {
            LocalDate first = failures.get(index++), last = first;
            while (index < failures.size() && failures.get(index).equals(last.plusDays(1))) last = failures.get(index++);
            result.add(new Stay(first, last.plusDays(1), scope.stay().adults(), scope.stay().rooms()));
        }
        return List.copyOf(result);
    }

    private void validateEffectiveAt(StoredJob job, SearchResult result) {
        OffsetDateTime effective = effectiveNow(job);
        if (result.checkedAt().isAfter(effective)) throw new WorkflowFailure("QUOTE_FROM_FUTURE");
        for (Quote quote : result.quotes()) {
            if (quote.checkedAt().isAfter(effective)) throw new WorkflowFailure("QUOTE_FROM_FUTURE");
            if (!quote.expiresAt().isAfter(effective)) throw new WorkflowFailure("QUOTE_EXPIRED");
        }
    }

    private void saveQuotes(StoredJob observed, String token, List<Group> groups) {
        StoredJob job = lockOwned(observed, token);
        validateScope(job);
        for (Group group : groups) {
            for (Quote quote : group.search().quotes()) {
                long hotel = hotelId(quote);
                int inserted = jdbc.sql("""
                        INSERT INTO alternative_quotes (quote_code, source_code, search_job_id, hotel_id,
                            room_type_code, rate_plan_code, check_in_date, check_out_date, adults, rooms,
                            base_amount, mandatory_fees_amount, total_amount, currency, distance_meters,
                            available_room_count, max_adults, breakfast_included, cancellation_policy,
                            checked_at, expires_at, source_payload, case_id, property_name, room_name)
                        VALUES (:code, :source, :job, :hotel, :roomType, :ratePlan, :checkIn, :checkOut,
                            :adults, :rooms, :base, :fees, :total, :currency, :distance, :available, :maxAdults,
                            :breakfast, :cancellation, :checked, :expires, CAST(:raw AS jsonb), :caseId,
                            :propertyName, :roomName) ON CONFLICT (quote_code) DO NOTHING
                        """).param("code", quote.quoteId()).param("source", quote.source()).param("job", job.id())
                        .param("hotel", hotel).param("roomType", quote.roomTypeId()).param("ratePlan", quote.ratePlanId())
                        .param("checkIn", group.search().stay().checkInDate()).param("checkOut", group.search().stay().checkOutDate())
                        .param("adults", group.search().stay().adults()).param("rooms", group.search().stay().rooms())
                        .param("base", quote.baseAmount()).param("fees", quote.mandatoryFeesAmount())
                        .param("total", quote.totalAmount()).param("currency", quote.currency()).param("distance", quote.distanceMeters())
                        .param("available", quote.availableRoomCount()).param("maxAdults", quote.maxAdults())
                        .param("breakfast", quote.breakfastIncluded()).param("cancellation", quote.cancellationPolicy())
                        .param("checked", quote.checkedAt()).param("expires", quote.expiresAt()).param("raw", quote.sourceJson())
                        .param("caseId", job.caseId()).param("propertyName", quote.propertyName()).param("roomName", quote.roomName()).update();
                if (inserted == 0 && !jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM alternative_quotes WHERE quote_code = :code
                            AND search_job_id = :job AND case_id = :caseId AND source_payload = CAST(:raw AS jsonb))
                        """).param("code", quote.quoteId()).param("job", job.id()).param("caseId", job.caseId())
                        .param("raw", quote.sourceJson()).query(Boolean.class).single())
                    throw new WorkflowFailure("QUOTE_ID_CONFLICT");
            }
        }
        JobResult result = new JobResult("QUOTES_SAVED", token, groups, null);
        update(job, "RUNNING", result, now().plusSeconds(LEASE_SECONDS), null);
    }

    private long hotelId(Quote quote) {
        jdbc.sql("""
                INSERT INTO hotels (hotel_code, name, data_source) VALUES (:code, :name, 'SIMULATED')
                ON CONFLICT (hotel_code) DO NOTHING
                """).param("code", quote.propertyId()).param("name", quote.propertyName()).update();
        return jdbc.sql("SELECT id FROM hotels WHERE hotel_code = :code AND data_source = 'SIMULATED'")
                .param("code", quote.propertyId()).query(Long.class).optional()
                .orElseThrow(() -> new WorkflowFailure("QUOTE_PROPERTY_CONFLICT"));
    }

    private Group rank(StoredJob job, Group group) {
        requireNoCallerTransaction();
        // Read committed immutable facts even when this attempt resumed from the
        // JSON checkpoint. The checkpoint identifies a group; it is not a second
        // authority for the price/product sent to the Agent.
        List<Quote> snapshots = storedQuotes(job, group.search().stay());
        if (snapshots.size() != group.search().quotes().size()
                || !snapshots.stream().map(Quote::quoteId).collect(java.util.stream.Collectors.toSet())
                        .equals(group.search().quotes().stream().map(Quote::quoteId)
                                .collect(java.util.stream.Collectors.toSet())))
            throw new WorkflowFailure("QUOTE_SNAPSHOT_MISMATCH");
        SearchResult saved = new SearchResult(group.search().searchRequestId(), group.search().stay(),
                group.search().checkedAt(), group.search().currency(), snapshots,
                group.search().requestJson(), group.search().responseJson());
        group = new Group(group.mode(), saved, group.unconfirmed(), null, null);
        if (group.search().quotes().isEmpty()) return group;
        if (group.search().quotes().size() > 20) return group.with(null, "RANKING_LIMIT_EXCEEDED");
        try {
            Long original = "FULL_STAY".equals(group.mode()) ? job.parameters().scope().originalAmount() : null;
            Ranking result = ranking.rank(new RankInput(job.parameters().scope().caseCode(),
                    job.parameters().scope().bookingCode(), group.search().stay(), original, group.search().quotes()));
            return group.with(result, null);
        } catch (QuoteIntegrationException failure) {
            return group.with(null, "API_025_" + failure.kind().name());
        } catch (RuntimeException failure) {
            return group.with(null, "RANKING_INTERNAL_ERROR");
        }
    }

    private Execution finish(StoredJob observed, String token, List<Group> groups) {
        StoredJob job = lockOwned(observed, token);
        validateScope(job);
        for (Group group : groups) {
            Ranking result = group.ranking();
            if (result == null) continue;
            jdbc.sql("""
                    INSERT INTO quote_rankings (search_job_id, case_id, ranking_version, input_payload,
                        result_payload, validation_status, analyzed_at)
                    VALUES (:job, :caseId, :version, CAST(:input AS jsonb), CAST(:result AS jsonb), 'VALID', :now)
                    """).param("job", job.id()).param("caseId", job.caseId()).param("version", result.rankingVersion())
                    .param("input", result.requestJson()).param("result", result.responseJson()).param("now", now()).update();
        }
        JobResult result = new JobResult("COMPLETED", null, groups, null);
        update(job, "COMPLETED", result, null, now());
        return execution(job.with("COMPLETED", result));
    }

    private Execution fail(StoredJob observed, String token, String errorCode) {
        return Objects.requireNonNull(write.execute(transaction -> {
            StoredJob job = lock(observed);
            if (!"RUNNING".equals(job.status()) || !Objects.equals(job.result().claimToken(), token)) return execution(job);
            JobResult result = new JobResult(job.result().stage(), null, job.result().groups(), errorCode);
            update(job, "FAILED", result, null, now());
            return execution(job.with("FAILED", result));
        }));
    }

    private Map<String, Object> groupView(StoredJob job, Group group, OffsetDateTime effective) {
        List<Quote> stored = storedQuotes(job, group.search().stay());
        Ranking analysis = group.ranking();
        if (analysis != null) {
            Map<String, Integer> positions = new LinkedHashMap<>();
            for (int index = 0; index < analysis.rankedQuoteIds().size(); index++) positions.put(analysis.rankedQuoteIds().get(index), index);
            stored = stored.stream().sorted(Comparator.comparingInt(quote -> positions.getOrDefault(quote.quoteId(), Integer.MAX_VALUE))).toList();
        }
        Map<String, String> reasons = new LinkedHashMap<>();
        if (analysis != null) analysis.reasons().forEach(reason -> reasons.put(reason.quoteId(), reason.summary()));
        List<Map<String, Object>> quotes = new ArrayList<>();
        for (Quote quote : stored) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("quoteId", quote.quoteId()); item.put("source", quote.source()); item.put("propertyId", quote.propertyId());
            item.put("propertyName", quote.propertyName()); item.put("roomName", quote.roomName());
            item.put("roomTypeId", quote.roomTypeId()); item.put("ratePlanId", quote.ratePlanId());
            item.put("distanceMeters", quote.distanceMeters()); item.put("totalAmount", quote.totalAmount());
            item.put("baseAmount", quote.baseAmount()); item.put("mandatoryFeesAmount", quote.mandatoryFeesAmount());
            item.put("currency", quote.currency()); item.put("breakfastIncluded", quote.breakfastIncluded());
            item.put("cancellationPolicy", quote.cancellationPolicy()); item.put("availableRoomCount", quote.availableRoomCount());
            item.put("maxAdults", quote.maxAdults());
            item.put("priceDifference", "FULL_STAY".equals(group.mode()) ? quote.totalAmount() - job.parameters().scope().originalAmount() : null);
            item.put("checkedAt", timestamp(quote.checkedAt())); item.put("expiresAt", timestamp(quote.expiresAt()));
            item.put("expired", !quote.expiresAt().isAfter(effective));
            item.put("reason", reasons.get(quote.quoteId())); quotes.add(item);
        }
        List<String> warnings = new ArrayList<>();
        warnings.add("견적 조회는 예약 확정이 아닙니다.");
        if (!"FULL_STAY".equals(group.mode())) warnings.add("확인된 장애 숙박일만 대체 검색했으며 나머지 숙박일은 별도 확인이 필요합니다.");
        if (group.rankingError() != null) warnings.add("견적 비교를 사용할 수 없어 검증된 공급사 원본 순서로 표시합니다.");
        if (analysis != null) warnings.addAll(analysis.warnings());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("coverageMode", group.mode()); result.put("stay", group.search().stay());
        result.put("remainingUnconfirmedStayDates", group.unconfirmed());
        result.put("rankingAvailable", analysis != null); result.put("rankingErrorCode", group.rankingError());
        result.put("quotes", quotes); result.put("warnings", warnings);
        return result;
    }

    private List<Quote> storedQuotes(StoredJob job, Stay stay) {
        return jdbc.sql("""
                SELECT q.*, h.hotel_code, q.source_payload::text AS source_json FROM alternative_quotes q
                JOIN hotels h ON h.id = q.hotel_id WHERE q.search_job_id = :job AND q.case_id = :caseId
                    AND q.check_in_date = :checkIn AND q.check_out_date = :checkOut
                    AND q.adults = :adults AND q.rooms = :rooms ORDER BY q.id
                """).param("job", job.id()).param("caseId", job.caseId()).param("checkIn", stay.checkInDate())
                .param("checkOut", stay.checkOutDate()).param("adults", stay.adults()).param("rooms", stay.rooms())
                .query((rs, index) -> new Quote(rs.getString("quote_code"), rs.getString("source_code"),
                        rs.getString("hotel_code"), rs.getString("property_name"), rs.getInt("distance_meters"),
                        rs.getString("room_type_code"), rs.getString("room_name"), rs.getString("rate_plan_code"),
                        rs.getInt("max_adults"), rs.getBoolean("breakfast_included"), rs.getInt("available_room_count"),
                        rs.getLong("base_amount"), rs.getLong("mandatory_fees_amount"), rs.getLong("total_amount"),
                        rs.getString("currency"), rs.getString("cancellation_policy"),
                        rs.getObject("checked_at", OffsetDateTime.class), rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getString("source_json"))).list();
    }

    private Scope scope(String caseCode, long caseId, long bookingId) {
        BookingRow booking = jdbc.sql("""
                SELECT b.booking_code, b.hotel_id, h.hotel_code, b.supplier_code, b.supplier_status,
                    b.check_in_date, b.check_out_date, b.adults, b.rooms, b.original_total_amount, b.currency, b.updated_at
                FROM bookings b JOIN hotels h ON h.id = b.hotel_id WHERE b.id = :booking
                """).param("booking", bookingId).query((rs, index) -> new BookingRow(rs.getString("booking_code"),
                        rs.getLong("hotel_id"), rs.getString("hotel_code"), rs.getString("supplier_code"),
                        rs.getString("supplier_status"), new Stay(rs.getObject("check_in_date", LocalDate.class),
                        rs.getObject("check_out_date", LocalDate.class), rs.getInt("adults"), rs.getInt("rooms")),
                        rs.getLong("original_total_amount"), rs.getString("currency"),
                        rs.getObject("updated_at", OffsetDateTime.class))).single();
        List<Night> nights = jdbc.sql("""
                SELECT stay_date, fulfillment_status, fact_status, accepted_assertion_id, updated_at
                FROM booking_nights WHERE booking_id = :booking ORDER BY stay_date
                """).param("booking", bookingId).query((rs, index) -> new Night(rs.getObject("stay_date", LocalDate.class),
                        rs.getString("fulfillment_status"), rs.getString("fact_status"),
                        rs.getObject("accepted_assertion_id", Long.class), rs.getObject("updated_at", OffsetDateTime.class))).list();
        if (!booking.stay().checkInDate().datesUntil(booking.stay().checkOutDate()).toList()
                .equals(nights.stream().map(Night::stayDate).toList())) throw new WorkflowFailure("QUOTE_SCOPE_INCOMPLETE");
        return new Scope(caseId, caseCode, bookingId, booking.bookingCode(), booking.hotelId(), booking.hotelCode(),
                booking.supplierCode(), booking.supplierStatus(), booking.stay(), booking.originalAmount(),
                booking.currency(), booking.updatedAt(), nights);
    }

    private void validateScope(StoredJob job) {
        Scope expected = job.parameters().scope();
        CaseRow current = lockCase(expected.caseCode(), expected.bookingId());
        if ("CLOSED".equals(current.status())) throw new WorkflowFailure("CASE_CLOSED");
        if (!expected.equals(scope(expected.caseCode(), expected.caseId(), expected.bookingId())))
            throw new WorkflowFailure("QUOTE_SCOPE_CHANGED");
    }

    private long bookingId(String caseId) {
        return jdbc.sql("SELECT booking_id FROM cases WHERE case_code = :caseId").param("caseId", caseId)
                .query(Long.class).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND"));
    }

    private void lockBooking(long bookingId) {
        jdbc.sql("SELECT id FROM bookings WHERE id = :booking FOR UPDATE").param("booking", bookingId).query(Long.class).single();
    }

    private CaseRow lockCase(String caseCode, long bookingId) {
        return jdbc.sql("SELECT id, status FROM cases WHERE case_code = :caseCode AND booking_id = :booking FOR UPDATE")
                .param("caseCode", caseCode).param("booking", bookingId)
                .query((rs, index) -> new CaseRow(rs.getLong("id"), rs.getString("status"))).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND"));
    }

    private StoredJob lock(StoredJob observed) {
        lockBooking(observed.bookingId());
        lockCase(observed.parameters().scope().caseCode(), observed.bookingId());
        return findJob(observed.code(), true).orElseThrow(() -> new IllegalStateException("Quote job disappeared"));
    }

    private StoredJob lockOwned(StoredJob observed, String token) {
        StoredJob current = lock(observed);
        if (!"RUNNING".equals(current.status()) || !Objects.equals(current.result().claimToken(), token)) throw new StaleClaim();
        return current;
    }

    private Optional<StoredJob> findJob(String code, boolean lock) {
        return jdbc.sql("SELECT " + JOB_COLUMNS + " FROM jobs WHERE job_code = :code AND kind = 'QUOTE_SEARCH'"
                + " AND parameters ->> 'protocol' = :protocol" + (lock ? " FOR UPDATE" : ""))
                .param("code", code).param("protocol", PROTOCOL).query(this::storedJob).optional();
    }

    private StoredJob storedJob(ResultSet rs, int index) throws SQLException {
        Parameters parameters = QuoteContracts.JSON.readValue(rs.getString("parameters_json"), Parameters.class);
        JobResult result = QuoteContracts.JSON.readValue(rs.getString("result_json"), JobResult.class);
        if (!PROTOCOL.equals(parameters.protocol())) throw new IllegalStateException("Foreign quote search protocol");
        return new StoredJob(rs.getLong("id"), rs.getString("job_code"), rs.getLong("booking_id"),
                rs.getLong("case_id"), rs.getString("status"), parameters, result,
                rs.getObject("deadline_at", OffsetDateTime.class), rs.getObject("next_attempt_at", OffsetDateTime.class));
    }

    private void update(StoredJob job, String status, JobResult result, OffsetDateTime next, OffsetDateTime finished) {
        jdbc.sql("""
                UPDATE jobs SET status = :status, result_payload = CAST(:result AS jsonb), next_attempt_at = :next,
                    finished_at = :finished, updated_at = :now WHERE id = :id
                """).param("status", status).param("result", json(result)).param("next", next)
                .param("finished", finished).param("now", now()).param("id", job.id()).update();
    }

    private void authorize(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        String role = jdbc.sql("SELECT role FROM users WHERE subject_key = :subject AND is_active")
                .param("subject", principal.getName()).query(String.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED"));
        if (!"COUNSELOR".equals(role)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
    }

    private static void validateCaseId(String caseId) {
        if (caseId == null || !caseId.matches("C-[0-9]{3,38}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CASE_ID");
    }

    private static void validateOptions(SearchOptions options, String key, OffsetDateTime effectiveAt) {
        if (options == null || options.radiusMeters() < 1 || options.radiusMeters() > 20000
                || options.maxAverageNightlyAmount() != null && options.maxAverageNightlyAmount() < 1
                || key == null || key.isBlank() || key.length() > 100 || key.chars().anyMatch(Character::isISOControl)
                || effectiveAt == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QUOTE_SEARCH_REQUEST");
    }

    private static ResponseStatusException conflict(String code) { return new ResponseStatusException(HttpStatus.CONFLICT, code); }
    private static boolean terminal(String state) { return "COMPLETED".equals(state) || "FAILED".equals(state); }
    private static Execution execution(StoredJob job) { return new Execution(job.code(), job.status(), job.result().errorCode()); }
    private OffsetDateTime now() { return clock.instant().atOffset(ZoneOffset.UTC); }
    private OffsetDateTime effectiveNow(StoredJob job) {
        return job.parameters().syntheticDemo() ? job.parameters().effectiveAt() : now();
    }
    private static String timestamp(OffsetDateTime value) { return value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME); }
    private static String json(Object value) { return QuoteContracts.JSON.writeValueAsString(value); }
    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static void requireNoCallerTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Quote search HTTP work requires no caller transaction");
    }

    public record SearchOptions(int radiusMeters, Long maxAverageNightlyAmount) {}
    public record Acceptance(String caseId, String searchJobId, String status) {}
    public record Execution(String searchJobId, String status, String errorCode) {}
    private record Parameters(String protocol, String requestHash, SearchOptions options, Scope scope,
                              OffsetDateTime effectiveAt, boolean syntheticDemo) {}
    private record Scope(long caseId, String caseCode, long bookingId, String bookingCode, long hotelId,
                         String hotelCode, String supplierCode, String supplierStatus, Stay stay,
                         long originalAmount, String currency, OffsetDateTime updatedAt, List<Night> nights) {
        Scope { nights = List.copyOf(nights); }
    }
    private record Night(LocalDate stayDate, String fulfillmentStatus, String factStatus,
                         Long acceptedAssertionId, OffsetDateTime updatedAt) {}
    private record BookingRow(String bookingCode, long hotelId, String hotelCode, String supplierCode,
                              String supplierStatus, Stay stay, long originalAmount, String currency, OffsetDateTime updatedAt) {}
    private record CaseRow(long id, String status) {}
    private record Group(String mode, SearchResult search, List<LocalDate> unconfirmed, Ranking ranking, String rankingError) {
        Group { unconfirmed = List.copyOf(unconfirmed); }
        Group with(Ranking value, String error) { return new Group(mode, search, unconfirmed, value, error); }
    }
    private record JobResult(String stage, String claimToken, List<Group> groups, String errorCode) {
        JobResult { groups = List.copyOf(groups); }
        static JobResult empty() { return new JobResult("INIT", null, List.of(), null); }
    }
    private record StoredJob(long id, String code, long bookingId, long caseId, String status,
                             Parameters parameters, JobResult result, OffsetDateTime deadline, OffsetDateTime nextAt) {
        StoredJob with(String nextStatus, JobResult next) {
            return new StoredJob(id, code, bookingId, caseId, nextStatus, parameters, next, deadline, nextAt);
        }
    }
    private record Claim(StoredJob job, String token) {}
    private static final class WorkflowFailure extends RuntimeException {
        private final String code;
        WorkflowFailure(String code) { super(code); this.code = code; }
    }
    private static final class StaleClaim extends RuntimeException {}
}
