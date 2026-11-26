package com.csro.backend.assist;

import com.csro.backend.cases.CaseQueries;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 초안 입력 사실 조회와 감사 기록. 각각 단일 문장이며 Agent 호출 동안 트랜잭션·행 잠금을 잡지 않는다. */
@Repository
class AssistStore {
    // 고객 신고 원문·채팅 본문·호텔 회신 원문은 읽지 않는다. 숙박일은 저장된 상태 값만 보낸다.
    private static final String FACTS_SQL = """
            SELECT c.id, c.booking_id, jsonb_build_object(
                'hotelName',h.name,'bookingId',b.booking_code,'checkInDate',b.check_in_date,'checkOutDate',b.check_out_date,
                'supplierStatus',b.supplier_status,'caseStatus',c.status,'origin',
            """ + CaseQueries.ORIGIN_TYPE + """
                ,
                'nights',coalesce((SELECT jsonb_agg(jsonb_build_object('stayDate',n.stay_date,
                    'fulfillmentStatus',n.fulfillment_status,'factStatus',n.fact_status) ORDER BY n.stay_date)
                    FROM booking_nights n WHERE n.booking_id=b.id),'[]'),
                'openTasks',coalesce((SELECT jsonb_agg(t.task_type ORDER BY t.due_at ASC NULLS LAST,t.created_at,t.id)
                    FROM case_tasks t WHERE t.case_id=c.id AND t.status IN ('TODO','IN_PROGRESS')),'[]'),
                'completedTasks',coalesce((SELECT jsonb_agg(jsonb_build_object('type',t.task_type,
                    'note',left(t.completion_note,500)) ORDER BY t.completed_at,t.id)
                    FROM case_tasks t WHERE t.case_id=c.id AND t.status='DONE'),'[]'),
                'customerReportCount',(SELECT count(*) FROM incident_reports r WHERE r.case_id=c.id),
                'replacement',(SELECT jsonb_build_object('status',r.status,'propertyName',q.property_name)
                    FROM replacement_requests r
                    JOIN alternative_selections s ON s.id=r.selection_id AND s.case_id=r.case_id
                    JOIN alternative_quotes q ON q.id=s.quote_id AND q.case_id=s.case_id
                    WHERE r.case_id=c.id ORDER BY r.id DESC LIMIT 1),
                'checkinConfirmed',EXISTS(SELECT 1 FROM checkin_confirmations k
                    WHERE k.case_id=c.id AND k.confirmation_status<>'PENDING'),
                'closeReason',c.close_reason)::text AS facts
            FROM cases c JOIN bookings b ON b.id=c.booking_id JOIN hotels h ON h.id=b.hotel_id
            WHERE c.case_code=:code
            """;
    private final JdbcClient jdbc;

    AssistStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<CaseFacts> facts(String caseId) {
        return jdbc.sql(FACTS_SQL).param("code", caseId)
                .query((rs, row) -> new CaseFacts(rs.getLong("id"), rs.getLong("booking_id"), rs.getString("facts")))
                .optional();
    }

    /** 누가 언제 어떤 종류의 초안을 받았는지만 남긴다. 생성된 문장은 저장하지 않는다. */
    void recordGenerated(CaseFacts target, String subject, String kind, String source, String model, OffsetDateTime at) {
        var details = new LinkedHashMap<String, Object>();
        details.put("kind", kind);
        details.put("source", source);
        details.put("model", model);
        jdbc.sql("""
                INSERT INTO case_actions (case_id,booking_id,actor_id,action_type,details,acted_at)
                SELECT :caseKey,:booking,u.id,'ASSIST_DRAFT_GENERATED',CAST(:details AS jsonb),:at
                FROM users u WHERE u.is_active AND u.subject_key=:subject
                """).param("caseKey", target.caseKey()).param("booking", target.bookingKey()).param("subject", subject)
                .param("details", AssistAgentClient.JSON.writeValueAsString(details)).param("at", at).update();
    }

    record CaseFacts(long caseKey, long bookingKey, String json) {}
}
