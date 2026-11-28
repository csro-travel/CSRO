package com.csro.backend.searching;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AlternativeSearchResultRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public AlternativeSearchResultRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<CaseStay> findCaseStay(String caseCode) {
        return jdbc.query("""
                SELECT b.booking_code, b.check_in_date, b.check_out_date, b.adults, c.status
                FROM cases c JOIN bookings b ON b.id = c.booking_id
                WHERE c.case_code = :caseCode
                """, Map.of("caseCode", caseCode), (rs, row) -> new CaseStay(
                rs.getString("booking_code"), rs.getObject("check_in_date", LocalDate.class),
                rs.getObject("check_out_date", LocalDate.class), rs.getInt("adults"), rs.getString("status")))
                .stream().findFirst();
    }

    Optional<String> findSnapshot(String caseCode, String jobCode) {
        return jdbc.query("""
                SELECT jsonb_build_object('searchId', substring(j.job_code FROM 11),
                    'finishedAt', j.finished_at, 'result', j.result_payload,
                    'selectedListingIds', COALESCE(selection.listing_ids, guides.last_selection, '[]'::jsonb),
                    'sentSelections', COALESCE(guides.selections, '[]'::jsonb))::text AS snapshot
                FROM jobs j JOIN cases c ON c.id = j.case_id
                JOIN bookings b ON b.id = c.booking_id AND b.id = j.booking_id
                LEFT JOIN LATERAL (
                    SELECT a.details -> 'listingIds' AS listing_ids FROM case_actions a
                    WHERE a.case_id = c.id AND a.action_type = 'AIRBNB_SELECTION_DRAFT'
                      AND a.details ->> 'searchId' = substring(j.job_code FROM 11)
                    ORDER BY a.acted_at DESC, a.id DESC LIMIT 1
                ) selection ON TRUE
                LEFT JOIN LATERAL (
                    SELECT jsonb_path_query_first(jsonb_agg(a.details -> 'listingIds'
                               ORDER BY a.acted_at DESC, a.id DESC), '$[0]') AS last_selection,
                           jsonb_agg(a.details -> 'listingIds') AS selections
                    FROM case_actions a
                    WHERE a.case_id = c.id AND a.action_type = 'AIRBNB_GUIDE_SENT'
                      AND a.details ->> 'searchId' = substring(j.job_code FROM 11)
                ) guides ON TRUE
                WHERE c.case_code = :caseCode AND j.kind = 'QUOTE_SEARCH'
                  AND j.trigger_type = 'LOCAL_DEMO' AND j.status = 'COMPLETED'
                  AND j.job_code LIKE 'LOCAL-ALT-%'
                  AND (:jobCode = '' OR j.job_code = :jobCode)
                  AND j.result_payload ->> 'case_id' = c.case_code
                  AND j.result_payload ->> 'booking_id' = b.booking_code
                  AND j.result_payload #>> '{policy,check_in_date}' = b.check_in_date::text
                  AND j.result_payload #>> '{policy,check_out_date}' = b.check_out_date::text
                  AND j.result_payload #>> '{policy,adults}' = b.adults::text
                  AND j.result_payload #>> '{policy,nights}' = (b.check_out_date - b.check_in_date)::text
                ORDER BY j.finished_at DESC NULLS LAST, j.id DESC LIMIT 1
                """, Map.of("caseCode", caseCode, "jobCode", jobCode),
                (rs, row) -> rs.getString("snapshot")).stream().findFirst();
    }

    record CaseStay(String bookingCode, LocalDate checkInDate, LocalDate checkOutDate, int adults, String status) {}

    int insertCompleted(String jobCode, String caseCode, String bookingCode, String parameters, String result) {
        return jdbc.update("""
                INSERT INTO jobs (job_code, booking_id, case_id, kind, trigger_type, status,
                    parameters, result_payload, finished_at)
                SELECT :jobCode, b.id, c.id, 'QUOTE_SEARCH', 'LOCAL_DEMO', 'COMPLETED',
                    CAST(:parameters AS jsonb), CAST(:result AS jsonb), now()
                FROM cases c JOIN bookings b ON b.id = c.booking_id
                WHERE c.case_code = :caseCode AND b.booking_code = :bookingCode
                  AND c.status IN ('OPEN', 'IN_PROGRESS', 'WAITING')
                ON CONFLICT (job_code) DO NOTHING
                """, Map.of("jobCode", jobCode, "caseCode", caseCode, "bookingCode", bookingCode,
                "parameters", parameters, "result", result));
    }

    boolean matchesCompleted(String jobCode, String parameters, String result) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM jobs WHERE job_code = :jobCode
                  AND kind = 'QUOTE_SEARCH' AND trigger_type = 'LOCAL_DEMO' AND status = 'COMPLETED'
                  AND parameters = CAST(:parameters AS jsonb)
                  AND result_payload = CAST(:result AS jsonb))
                """, Map.of("jobCode", jobCode, "parameters", parameters, "result", result), Boolean.class));
    }

    void insertDemoRanking(String jobCode, String rankingVersion, String analyzedAt) {
        jdbc.update("""
                INSERT INTO quote_rankings (search_job_id, case_id, ranking_kind, ranking_version,
                    input_payload, result_payload, validation_status, analyzed_at)
                SELECT id, case_id, 'AIRBNB_DEMO', :rankingVersion,
                    jsonb_build_object('request', parameters,
                        'policy', result_payload -> 'policy',
                        'fallback_policy', result_payload -> 'fallback_policy',
                        'candidates', result_payload -> 'ranking_input'),
                    jsonb_build_object('top_recommendations', result_payload -> 'top_recommendations',
                        'summary', result_payload -> 'summary',
                        'search_notes', result_payload -> 'search_notes',
                        'disclaimer', result_payload -> 'disclaimer'),
                    'DEMO_ONLY', CAST(:analyzedAt AS timestamptz)
                FROM jobs
                WHERE job_code = :jobCode
                ON CONFLICT (search_job_id) WHERE ranking_kind = 'AIRBNB_DEMO' DO NOTHING
                """, Map.of("jobCode", jobCode, "rankingVersion", rankingVersion, "analyzedAt", analyzedAt));
    }
}
