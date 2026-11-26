package com.csro.backend.console;

import com.csro.backend.cases.CaseQueries;
import java.security.Principal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 상담사 콘솔의 읽기 전용 조회. 저장된 행만 읽고 최신순 최대 200건을 반환한다. */
@Repository
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ConsoleQueries {
    private final JdbcClient jdbc;
    private final CaseQueries cases;

    ConsoleQueries(JdbcClient jdbc, CaseQueries cases) {
        this.jdbc = jdbc;
        this.cases = cases;
    }

    // 예약 행의 별칭 b를 참조한다.
    private static final String NIGHTS = """
        coalesce((SELECT jsonb_agg(jsonb_build_object('stayDate',n.stay_date,'fulfillmentStatus',n.fulfillment_status,
            'factStatus',n.fact_status) ORDER BY n.stay_date) FROM booking_nights n WHERE n.booking_id=b.id),'[]')
        """;

    private static final String OVERVIEW = """
        SELECT jsonb_build_object(
            'cases',(SELECT jsonb_build_object('open',count(*) FILTER (WHERE status='OPEN'),
                'inProgress',count(*) FILTER (WHERE status='IN_PROGRESS'),'waiting',count(*) FILTER (WHERE status='WAITING'),
                'closed',count(*) FILTER (WHERE status='CLOSED'),
                'urgentActive',count(*) FILTER (WHERE priority='URGENT' AND status<>'CLOSED')) FROM cases),
            'autoChecks',(SELECT jsonb_build_object('recheckJobs',count(*),
                'completed',count(*) FILTER (WHERE status='COMPLETED'),'failed',count(*) FILTER (WHERE status='FAILED'),
                'pending',count(*) FILTER (WHERE status IN ('PENDING','RUNNING','WAITING')),
                'bookingsChecked',count(DISTINCT booking_id)) FROM jobs WHERE kind='RECHECK')
                || (SELECT jsonb_build_object('bookingsAllAvailable',count(*) FILTER (WHERE n.all_available),
                    'bookingsWithUnavailable',count(*) FILTER (WHERE n.any_unavailable))
                    FROM (SELECT DISTINCT booking_id FROM jobs WHERE kind='RECHECK') checked
                    CROSS JOIN LATERAL (SELECT
                        coalesce(bool_and(fact_status='VERIFIED' AND fulfillment_status='AVAILABLE'),false) AS all_available,
                        coalesce(bool_or(fact_status='VERIFIED' AND fulfillment_status='UNAVAILABLE'),false) AS any_unavailable
                        FROM booking_nights WHERE booking_id=checked.booking_id) n)
                || jsonb_build_object('casesOpenedByRecheck',(SELECT count(*) FROM cases c WHERE (
        """ + CaseQueries.ORIGIN_TYPE + """
                    )='RISK_RECHECK')),
            'risk',jsonb_build_object('assessments',(SELECT count(*) FROM risk_assessments),
                'selectedHotelDates',(SELECT count(DISTINCT risk_assessment_id) FROM jobs WHERE risk_assessment_id IS NOT NULL)),
            'analyses',jsonb_build_object('hotelReply',(SELECT count(*) FROM agent_analyses),
                'quoteRanking',(SELECT count(*) FROM quote_rankings)),
            'bookings',jsonb_build_object('total',(SELECT count(*) FROM bookings)))::text
        """;

    private static final String RECHECK_JOBS = """
        SELECT jsonb_build_object('items',coalesce(jsonb_agg(item ORDER BY created_at DESC,id DESC),'[]'))::text FROM (
            SELECT j.created_at,j.id,jsonb_build_object('jobCode',j.job_code,'trigger',j.trigger_type,'status',j.status,
                'bookingId',b.booking_code,'hotelName',h.name,'checkInDate',b.check_in_date,'checkOutDate',b.check_out_date,
                'caseId',c.case_code,'riskScore',j.parameters->'selection'->'rawScore',
                'threshold',j.parameters->'selection'->'threshold','policy',j.parameters->'selection'->'policy',
                'failedAttempts',CASE WHEN jsonb_typeof(j.result_payload->'history')='array'
                    THEN jsonb_array_length(j.result_payload->'history') ELSE 0 END,
                'errorCode',j.result_payload->'last'->'errorCode','manualRequired',j.result_payload->'manualRequired',
                'createdAt',j.created_at,'finishedAt',j.finished_at,'nights',
        """ + NIGHTS + """
                ) AS item
            FROM jobs j JOIN bookings b ON b.id=j.booking_id JOIN hotels h ON h.id=b.hotel_id
            LEFT JOIN cases c ON c.id=j.case_id
            WHERE j.kind='RECHECK' ORDER BY j.created_at DESC,j.id DESC LIMIT 200) page
        """;

    private static final String RISK_ASSESSMENTS = """
        SELECT jsonb_build_object('items',coalesce(jsonb_agg(item ORDER BY created_at DESC,id DESC),'[]'))::text FROM (
            SELECT ra.created_at,ra.id,jsonb_build_object('assessmentId',ra.id,'hotelId',h.hotel_code,'hotelName',h.name,
                'targetCheckInDate',ra.target_check_in_date,'asOf',ra.as_of,'score',ra.risk_score,
                'modelVersion',ra.model_version,'featureSetVersion',ra.feature_set_version,'features',ra.feature_snapshot,
                'assessedAt',ra.created_at,'selected',linked.jobs>0,'selections',linked.selections,
                'recheckJobs',linked.rechecks,'completedRechecks',linked.completed,'casesLinked',linked.cases) AS item
            FROM risk_assessments ra JOIN hotels h ON h.id=ra.hotel_id
            CROSS JOIN LATERAL (SELECT count(*) AS jobs,count(*) FILTER (WHERE j.kind='RECHECK') AS rechecks,
                count(*) FILTER (WHERE j.kind='RECHECK' AND j.status='COMPLETED') AS completed,
                count(DISTINCT j.case_id) AS cases,
                coalesce((SELECT jsonb_agg(s.selection) FROM (SELECT DISTINCT jsonb_build_object(
                    'policy',sj.parameters->'selection'->'policy','threshold',sj.parameters->'selection'->'threshold',
                    'rawScore',sj.parameters->'selection'->'rawScore') AS selection
                    FROM jobs sj WHERE sj.risk_assessment_id=ra.id
                        AND jsonb_typeof(sj.parameters->'selection')='object') s),'[]') AS selections
                FROM jobs j WHERE j.risk_assessment_id=ra.id) linked
            ORDER BY ra.created_at DESC,ra.id DESC LIMIT 200) page
        """;

    // contentFormat·originalContent·assertions는 CaseQueries 상세의 evidence와 같은 규칙이다.
    private static final String EVIDENCE = """
        SELECT jsonb_build_object('items',coalesce(jsonb_agg(item ORDER BY received_at DESC,id DESC),'[]'))::text FROM (
            SELECT e.received_at,e.id,jsonb_build_object('evidenceId','EV-' || e.id,'bookingId',b.booking_code,
                'hotelName',h.name,'sourceType',e.source_type,'sourceAuthenticated',e.source_authenticated,
                'contentFormat',CASE WHEN e.raw_payload IS NULL THEN 'TEXT' ELSE 'STRUCTURED' END,
                'originalContent',coalesce(e.raw_text, CASE WHEN e.source_type='HOTEL_API' AND jsonb_exists(e.raw_payload,'requestJson')
                    THEN (e.raw_payload->'response')::text ELSE e.raw_payload::text END),
                'occurredAt',e.occurred_at,'receivedAt',e.received_at,'jobCode',j.job_code,'trigger',j.trigger_type,
                'assertions',coalesce((SELECT jsonb_agg(jsonb_build_object('stayDate',n.stay_date,
                    'reportedProvisionStatus',a.reported_status,'reviewStatus',a.review_status) ORDER BY n.stay_date)
                    FROM evidence_nights a JOIN booking_nights n ON n.id=a.booking_night_id
                    WHERE a.evidence_id=e.id),'[]')) AS item
            FROM evidence_records e JOIN bookings b ON b.id=e.booking_id JOIN hotels h ON h.id=b.hotel_id
            LEFT JOIN jobs j ON j.id=e.job_id
            ORDER BY e.received_at DESC,e.id DESC LIMIT 200) page
        """;

    private static final String HOTEL_REPLY_ANALYSES = """
        SELECT jsonb_build_object('items',coalesce(jsonb_agg(item ORDER BY analyzed_at DESC,id DESC),'[]'))::text FROM (
            SELECT aa.analyzed_at,aa.id,jsonb_build_object('analysisId',aa.id,'evidenceId','EV-' || e.id,
                'bookingId',b.booking_code,'hotelName',h.name,'status',aa.analysis_status,'modelVersion',aa.model_version,
                'analyzedAt',aa.analyzed_at,'originalMessage',e.raw_text,
                'claims',coalesce(aa.result_payload->'claims','[]'::jsonb),
                'unresolvedStayDates',coalesce(aa.result_payload->'unresolvedStayDates','[]'::jsonb),
                'warnings',coalesce(aa.result_payload->'warnings','[]'::jsonb)) AS item
            FROM agent_analyses aa JOIN evidence_records e ON e.id=aa.evidence_id
            JOIN bookings b ON b.id=e.booking_id JOIN hotels h ON h.id=b.hotel_id
            ORDER BY aa.analyzed_at DESC,aa.id DESC LIMIT 200) page
        """;

    private static final String BOOKINGS = """
        SELECT jsonb_build_object('items',coalesce(jsonb_agg(item ORDER BY created_at DESC,id DESC),'[]'))::text FROM (
            SELECT b.created_at,b.id,jsonb_build_object('bookingId',b.booking_code,'hotelName',h.name,
                'customerName',u.display_name,'checkInDate',b.check_in_date,'checkOutDate',b.check_out_date,
                'adults',b.adults,'rooms',b.rooms,'supplierStatus',b.supplier_status,
                'originalPrice',jsonb_build_object('amount',b.original_total_amount,'currency',b.currency),
                'activeCaseId',(SELECT c.case_code FROM cases c
                    WHERE c.booking_id=b.id AND c.status IN ('OPEN','IN_PROGRESS','WAITING')),
                'latestCaseId',(SELECT c.case_code FROM cases c WHERE c.booking_id=b.id
                    ORDER BY c.created_at DESC,c.id DESC LIMIT 1),
                'recheckJobs',(SELECT count(*) FROM jobs j WHERE j.booking_id=b.id AND j.kind='RECHECK'),'nights',
        """ + NIGHTS + """
                ) AS item
            FROM bookings b JOIN hotels h ON h.id=b.hotel_id JOIN users u ON u.id=b.customer_id
            ORDER BY b.created_at DESC,b.id DESC LIMIT 200) page
        """;

    public String overview(Principal principal) {
        return read(principal, OVERVIEW);
    }

    public String recheckJobs(Principal principal) {
        return read(principal, RECHECK_JOBS);
    }

    public String riskAssessments(Principal principal) {
        return read(principal, RISK_ASSESSMENTS);
    }

    public String evidence(Principal principal) {
        return read(principal, EVIDENCE);
    }

    public String hotelReplyAnalyses(Principal principal) {
        return read(principal, HOTEL_REPLY_ANALYSES);
    }

    public String bookings(Principal principal) {
        return read(principal, BOOKINGS);
    }

    private String read(Principal principal, String sql) {
        cases.authorize(principal);
        return jdbc.sql(sql).query(String.class).single();
    }
}
