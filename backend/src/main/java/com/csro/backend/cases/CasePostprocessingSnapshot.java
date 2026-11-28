package com.csro.backend.cases;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
class CasePostprocessingSnapshot {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    CasePostprocessingSnapshot(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    ObjectNode read(long caseKey, long counselorId) {
        String source = jdbc.sql("""
            SELECT jsonb_build_object(
                'case_id', c.case_code, 'as_of', now(), 'case_status', c.status,
                'counselor', jsonb_build_object('id', '상담사', 'name', '담당 상담사'),
                'customer', jsonb_build_object('name', '고객', 'guests', b.adults, 'channel', '사건 채팅'),
                'booking', jsonb_build_object('id', b.booking_code, 'property', h.name,
                    'check_in', b.check_in_date, 'check_out', b.check_out_date, 'paid_krw', b.original_total_amount,
                    'supplier_status', b.supplier_status),
                'search_criteria', (
                    SELECT jsonb_build_object('radius_km', j.result_payload->'searched_radius_km',
                        'source', j.job_code, 'completed_at', j.finished_at)
                    FROM jobs j WHERE j.case_id=c.id AND j.kind='QUOTE_SEARCH' AND j.status='COMPLETED'
                    ORDER BY j.finished_at DESC NULLS LAST, j.id DESC LIMIT 1
                ),
                'evidence', coalesce((
                    SELECT jsonb_agg(item ORDER BY occurred_at) FROM (
                        SELECT jsonb_build_object('id', m.message_code, 'sender', m.sender_type,
                            'time', m.sent_at, 'detail', left(m.body, 3000), 'offer', o.offer_code) item,
                            m.sent_at occurred_at
                        FROM chat_messages m LEFT JOIN offers o ON o.id=m.offer_id WHERE m.case_id=c.id
                        UNION ALL
                        SELECT jsonb_build_object('id', 'EV-' || e.id, 'type', e.source_type,
                            'time', e.received_at, 'source_authenticated', e.source_authenticated,
                            'stay_dates', (SELECT jsonb_agg(jsonb_build_object('date', n.stay_date,
                                'reported', a.reported_status, 'review', a.review_status,
                                'accepted', n.accepted_assertion_id=a.id))
                                FROM evidence_nights a JOIN booking_nights n ON n.id=a.booking_night_id
                                WHERE a.evidence_id=e.id)), e.received_at
                        FROM evidence_records e WHERE e.booking_id=b.id
                        UNION ALL
                        SELECT jsonb_build_object('id', r.request_code, 'status', r.status,
                            'confirmed_at', r.confirmed_at), r.updated_at
                        FROM replacement_requests r WHERE r.case_id=c.id
                        UNION ALL
                        SELECT jsonb_build_object('id', 'CHECKIN-' || i.id, 'status', i.confirmation_status,
                            'checkin_at', i.checkin_at, 'simulation', coalesce(i.idempotency_key LIKE 'AIRBNB-DEMO-CHECKIN-%', false)), i.created_at
                        FROM checkin_confirmations i WHERE i.case_id=c.id
                    ) evidence
                ), '[]'::jsonb),
                'offered_stays', coalesce((
                    SELECT jsonb_agg(jsonb_build_object('id', q.quote_code, 'name', q.property_name,
                        'offer_id', o.offer_code, 'offer_status', o.status, 'sent_at', o.sent_at,
                        'simulation', o.offer_code LIKE 'ABSIM-%',
                        'total_krw', q.total_amount, 'distance_km', q.distance_meters / 1000.0,
                        'check_in', q.check_in_date, 'check_out', q.check_out_date,
                        'comparable_original_krw', CASE WHEN q.currency='KRW'
                            THEN comparison.original_amount END,
                        'conditions', q.room_name || ' / ' || q.cancellation_policy) ORDER BY o.sent_at, i.display_order)
                    FROM offers o JOIN offer_items i ON i.offer_id=o.id
                    JOIN alternative_quotes q ON q.id=i.quote_id
                    LEFT JOIN LATERAL (
                        SELECT CASE WHEN jsonb_typeof(a.details -> 'originalAmount') = 'number'
                            AND (a.details ->> 'originalAmount') ~ '^[0-9]{1,19}$'
                            THEN CASE WHEN (a.details ->> 'originalAmount')::numeric <= 9223372036854775807
                                THEN (a.details ->> 'originalAmount')::bigint END END AS original_amount
                        FROM case_actions a
                        WHERE a.case_id=o.case_id AND a.action_type='OFFER_SENT'
                            AND a.details ->> 'protocol'='offer-selection-v1'
                            AND a.details ->> 'offerId'=o.offer_code
                            AND a.details ->> 'coverageMode'='FULL_STAY'
                        ORDER BY a.id LIMIT 1
                    ) comparison ON true
                    WHERE o.case_id=c.id
                ), '[]'::jsonb),
                'replacement', (
                    SELECT jsonb_build_object('selected_id', q.quote_code, 'consent_at', s.selected_at,
                        'request_id', r.request_code, 'confirmation_id', r.supplier_booking_id,
                        'simulation', (SELECT o.offer_code LIKE 'ABSIM-%' FROM offers o WHERE o.id=s.offer_id),
                        'booking_status', r.status, 'checkin_status', (SELECT i.confirmation_status
                            FROM checkin_confirmations i WHERE i.replacement_request_id=r.id AND i.case_id=c.id
                            ORDER BY i.created_at DESC, i.id DESC LIMIT 1))
                    FROM alternative_selections s JOIN alternative_quotes q ON q.id=s.quote_id
                    LEFT JOIN replacement_requests r ON r.selection_id=s.id
                    WHERE s.case_id=c.id AND s.superseded_at IS NULL
                    ORDER BY s.selected_at DESC, s.id DESC LIMIT 1
                ),
                'existing_tasks', coalesce((
                    SELECT jsonb_agg(jsonb_build_object('id', 'TASK-' || t.id, 'type', t.task_type,
                        'status', t.status, 'due_at', t.due_at, 'required', t.is_required,
                        'completion_evidence', CASE WHEN t.completion_evidence_id IS NOT NULL
                            THEN 'EV-' || t.completion_evidence_id END) ORDER BY t.id)
                    FROM case_tasks t WHERE t.case_id=c.id
                ), '[]'::jsonb))::text
            FROM cases c JOIN bookings b ON b.id=c.booking_id JOIN hotels h ON h.id=b.hotel_id
            WHERE c.id=:caseKey
            """).param("caseKey", caseKey).query(String.class).single();
        ObjectNode result = (ObjectNode) json.readTree(source);
        String customerName = jdbc.sql("""
            SELECT u.display_name FROM cases c JOIN bookings b ON b.id=c.booking_id
            JOIN users u ON u.id=b.customer_id WHERE c.id=:id
            """).param("id", caseKey).query(String.class).single();
        for (var record : result.path("evidence")) {
            if (!record.path("detail").isString()) continue;
            String detail = record.path("detail").asText();
            if (!customerName.isBlank()) detail = detail.replace(customerName, "고객");
            ((ObjectNode) record).put("detail", detail
                    .replaceAll("[\\w.+-]+@[\\w.-]+\\.[\\p{L}]{2,}", "[이메일]")
                    .replaceAll("(?<!\\d)(?:\\d[ -]?){9,19}(?!\\d)", "[연락처 또는 번호]"));
        }
        String counselorName = jdbc.sql("SELECT display_name FROM users WHERE id=:id")
                .param("id", counselorId).query(String.class).single();
        ((ObjectNode) result.path("counselor")).put("name", counselorName);
        var policies = result.putArray("policies");
        policies.addObject().put("id", "OPS-ALT-004 v1.0 §§2–4").put("detail",
                "필요한 숙박일 전체, 투숙 인원 및 필수조건을 확인한다. 프로젝트 기준 반경 10km, 후보 부족 시 20km, 원예약 해당 숙박분 대비 120% 이내가 기본이다. 실제 검색 조건은 별도 근거가 필요하다.");
        policies.addObject().put("id", "OPS-CMP-005 v1.0 §§2–5").put("detail",
                "미제공 숙박분의 실결제액을 확인해 환불을 검토한다. 대체 차액과 직접 이동 실비는 분리하고 교통비는 증빙과 이동 목적을 확인해 예약당 20,000원까지 검토한다. 포인트는 대기 구간과 중복 지급을 확인하며 비용 지급에는 권한자의 처리가 필요하다.");
        policies.addObject().put("id", "OPS-HO-007 v1.0 §§2–6").put("detail",
                "고객 동의, 대체 예약 확정과 실제 입실을 구분한다. 미완료 업무에 담당자와 기한을 남긴다.");
        return result;
    }
}
