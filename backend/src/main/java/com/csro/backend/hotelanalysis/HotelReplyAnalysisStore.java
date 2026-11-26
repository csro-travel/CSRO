package com.csro.backend.hotelanalysis;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import com.csro.backend.hotelanalysis.HotelReplyAnalysisContracts.Analysis;
import com.csro.backend.hotelanalysis.HotelReplyAnalysisContracts.Input;
import com.csro.backend.hotelanalysis.HotelReplyAnalysisService.Outcome;
import com.csro.backend.hotelanalysis.HotelReplyAnalysisService.State;

/** Existing V1 storage only. Source identity is frozen before HTTP and checked again under locks. */
@Component
public class HotelReplyAnalysisStore {
    private static final Set<String> HOTEL_SOURCES = Set.of("HOTEL_API", "HOTEL_EVENT", "MANUAL_HOTEL");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SOURCE_SQL = """
            SELECT e.id,e.booking_id,e.source_type,e.source_authenticated,e.provider_code,e.provider_event_id,
                e.raw_text,e.raw_payload::text AS raw_payload,e.occurred_at,e.received_at,e.payload_hash,
                b.booking_code,b.hotel_id,b.check_in_date,b.check_out_date,h.hotel_code,h.time_zone
            FROM evidence_records e JOIN bookings b ON b.id=e.booking_id JOIN hotels h ON h.id=b.hotel_id
            WHERE e.id=:evidence
            """;
    private final JdbcClient jdbc;
    private final TransactionTemplate read;
    private final TransactionTemplate write;
    private final Clock clock;

    @Autowired
    public HotelReplyAnalysisStore(JdbcClient jdbc, PlatformTransactionManager manager) {
        this(jdbc, manager, Clock.systemUTC());
    }

    public HotelReplyAnalysisStore(JdbcClient jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    Prepared prepare(long evidenceId) {
        if (evidenceId < 1) throw new HotelReplyAnalysisException("INVALID_ANALYSIS_SOURCE");
        return database(() -> Objects.requireNonNull(read.execute(status -> load(evidenceId, false))));
    }

    Outcome save(Prepared expected, Analysis analysis) {
        return database(() -> Objects.requireNonNull(write.execute(status -> {
            Long activeCase = lockContext(expected);
            // This additionally checks that the stored raw request/result match the typed response.
            HotelReplyAnalysisClient.validateAnalysis(expected.input(), analysis);
            var existing = jdbc.sql("""
                    SELECT id FROM agent_analyses WHERE evidence_id=:evidence AND model_version=:model
                        AND analysis_status=:status AND input_payload=CAST(:input AS jsonb)
                        AND result_payload=CAST(:result AS jsonb) ORDER BY id LIMIT 1
                    """).param("evidence", expected.source().id()).param("model", analysis.modelVersion())
                    .param("status", analysis.analysisStatus()).param("input", analysis.requestJson())
                    .param("result", analysis.responseJson()).query(Long.class).optional();
            OffsetDateTime now = now();
            boolean reused = existing.isPresent();
            long id = reused ? existing.orElseThrow() : jdbc.sql("""
                    INSERT INTO agent_analyses(evidence_id,analysis_status,model_version,input_payload,result_payload,analyzed_at)
                    VALUES (:evidence,:status,:model,CAST(:input AS jsonb),CAST(:result AS jsonb),:now) RETURNING id
                    """).param("evidence", expected.source().id()).param("status", analysis.analysisStatus())
                    .param("model", analysis.modelVersion()).param("input", analysis.requestJson())
                    .param("result", analysis.responseJson()).param("now", now).query(Long.class).single();
            Long task = ensureReviewTask(expected.source().bookingId(), activeCase, now);
            return new Outcome(reused ? State.REUSED : State.ANALYZED, id, expected.input().evidenceId(),
                    analysis.analysisStatus(), analysis.modelVersion(), task, null, null, true);
        })));
    }

    Outcome manual(Prepared expected, String errorCode, String traceId) {
        String code = safeCode(errorCode);
        String trace = safeTrace(traceId);
        return database(() -> Objects.requireNonNull(write.execute(status -> {
            Long activeCase = lockContext(expected);
            OffsetDateTime now = now();
            Long task = ensureReviewTask(expected.source().bookingId(), activeCase, now);
            if (activeCase != null) {
                var details = new LinkedHashMap<String, Object>();
                details.put("evidenceId", expected.input().evidenceId());
                details.put("errorCode", code);
                if (trace != null) details.put("traceId", trace);
                jdbc.sql("""
                        INSERT INTO case_actions(booking_id,case_id,evidence_id,action_type,details,acted_at)
                        VALUES (:booking,:case,:evidence,'HOTEL_ANALYSIS_MANUAL_REQUIRED',CAST(:details AS jsonb),:now)
                        """).param("booking", expected.source().bookingId()).param("case", activeCase)
                        .param("evidence", expected.source().id()).param("details", JSON.writeValueAsString(details))
                        .param("now", now).update();
            }
            return new Outcome(State.MANUAL_REQUIRED, null, expected.input().evidenceId(), null, null,
                    task, code, trace, true);
        })));
    }

    private Long lockContext(Prepared expected) {
        boolean exists = jdbc.sql("SELECT id FROM bookings WHERE id=:id FOR UPDATE")
                .param("id", expected.source().bookingId()).query(Long.class).optional().isPresent();
        if (!exists) throw contextChanged();
        Long activeCase = jdbc.sql("""
                SELECT id FROM cases WHERE booking_id=:booking AND status IN ('OPEN','IN_PROGRESS','WAITING') FOR UPDATE
                """).param("booking", expected.source().bookingId()).query(Long.class).optional().orElse(null);
        Prepared current = load(expected.source().id(), true);
        if (!expected.equals(current)) throw contextChanged();
        return activeCase;
    }

    private Prepared load(long evidenceId, boolean lock) {
        Source source = jdbc.sql(SOURCE_SQL + (lock ? " FOR UPDATE OF e" : ""))
                .param("evidence", evidenceId).query((rs, row) -> new Source(rs.getLong("id"),
                        rs.getLong("booking_id"), rs.getString("source_type"), rs.getBoolean("source_authenticated"),
                        rs.getString("provider_code"), rs.getString("provider_event_id"), rs.getString("raw_text"),
                        rs.getString("raw_payload"), rs.getObject("occurred_at", OffsetDateTime.class),
                        rs.getObject("received_at", OffsetDateTime.class), rs.getString("payload_hash"),
                        rs.getString("booking_code"), rs.getLong("hotel_id"), rs.getObject("check_in_date", LocalDate.class),
                        rs.getObject("check_out_date", LocalDate.class), rs.getString("hotel_code"), rs.getString("time_zone")))
                .optional().orElseThrow(() -> new HotelReplyAnalysisException(
                        lock ? "ANALYSIS_CONTEXT_CHANGED" : "INVALID_ANALYSIS_SOURCE"));
        if (!HOTEL_SOURCES.contains(source.sourceType()) || source.rawPayload() != null
                || source.rawText() == null || source.rawText().isBlank()) {
            throw new HotelReplyAnalysisException(lock ? "ANALYSIS_CONTEXT_CHANGED" : "INVALID_ANALYSIS_SOURCE");
        }
        List<LocalDate> dates = jdbc.sql("SELECT stay_date FROM booking_nights WHERE booking_id=:booking ORDER BY stay_date")
                .param("booking", source.bookingId()).query(LocalDate.class).list();
        LocalDate next = source.checkIn();
        for (LocalDate date : dates) {
            if (!date.equals(next) || !date.isBefore(source.checkOut())) {
                throw new HotelReplyAnalysisException(lock ? "ANALYSIS_CONTEXT_CHANGED" : "INVALID_ANALYSIS_SOURCE");
            }
            next = next.plusDays(1);
        }
        if (dates.isEmpty() || !next.equals(source.checkOut())) {
            throw new HotelReplyAnalysisException(lock ? "ANALYSIS_CONTEXT_CHANGED" : "INVALID_ANALYSIS_SOURCE");
        }
        Input input = new Input("EV-" + source.id(), source.bookingCode(), source.hotelCode(), List.copyOf(dates),
                source.rawText(), "ko-KR", source.occurredAt(), source.timeZone());
        HotelReplyAnalysisClient.requestJson(input); // Validate the exact caller request before any HTTP.
        return new Prepared(source, input);
    }

    private Long ensureReviewTask(long bookingId, Long caseId, OffsetDateTime now) {
        if (caseId == null) return null;
        var existing = jdbc.sql("""
                SELECT id FROM case_tasks WHERE case_id=:case AND booking_id=:booking AND task_type='VERIFY_HOTEL'
                    AND is_required AND status IN ('TODO','IN_PROGRESS') ORDER BY id LIMIT 1 FOR UPDATE
                """).param("case", caseId).param("booking", bookingId).query(Long.class).optional();
        if (existing.isPresent()) return existing.orElseThrow();
        return jdbc.sql("""
                INSERT INTO case_tasks(booking_id,case_id,task_type,status,is_required,due_at,created_at,updated_at)
                VALUES (:booking,:case,'VERIFY_HOTEL','TODO',true,:now,:now,:now) RETURNING id
                """).param("booking", bookingId).param("case", caseId).param("now", now).query(Long.class).single();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static String safeCode(String code) {
        return code != null && code.matches("[A-Z][A-Z0-9_]{0,79}") ? code : "ANALYSIS_CLIENT_FAILED";
    }

    private static String safeTrace(String trace) {
        return trace != null && trace.matches("(?:req-[0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})")
                ? trace : null;
    }

    private static HotelReplyAnalysisException contextChanged() {
        return new HotelReplyAnalysisException("ANALYSIS_CONTEXT_CHANGED");
    }

    private static <T> T database(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (HotelReplyAnalysisException expected) {
            throw expected;
        } catch (RuntimeException failure) {
            // No SQL, original input, driver message or exception cause escapes this boundary.
            throw new HotelReplyAnalysisException("ANALYSIS_STORAGE_FAILED");
        }
    }

    record Prepared(Source source, Input input) {}
    record Source(long id, long bookingId, String sourceType, boolean sourceAuthenticated, String provider,
                  String eventId, String rawText, String rawPayload, OffsetDateTime occurredAt,
                  OffsetDateTime receivedAt, String payloadHash, String bookingCode, long hotelId,
                  LocalDate checkIn, LocalDate checkOut, String hotelCode, String timeZone) {}
}
