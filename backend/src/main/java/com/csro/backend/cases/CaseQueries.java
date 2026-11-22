package com.csro.backend.cases;

import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Repository
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CaseQueries {
    private final JdbcClient jdbc;
    private final CaseAccess access;

    CaseQueries(JdbcClient jdbc, CaseAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    private static final String FROM = """
        FROM cases c JOIN bookings b ON b.id=c.booking_id JOIN hotels h ON h.id=b.hotel_id
        LEFT JOIN users u ON u.id=c.assignee_id
        LEFT JOIN LATERAL (
            SELECT t.*, jsonb_build_object('taskId','TASK-' || t.id,'type',t.task_type,
                'status',t.status,'dueAt',t.due_at) AS action
            FROM case_tasks t WHERE t.case_id=c.id AND t.status IN ('TODO','IN_PROGRESS')
            ORDER BY t.due_at ASC NULLS LAST,t.created_at,t.id LIMIT 1
        ) next ON true
        """;

    private static final String CASE_FIELDS = """
        jsonb_build_object('caseId',c.case_code,'status',c.status,'priority',c.priority,
            'assigneeId',u.subject_key,'updatedAt',c.updated_at,'version',c.version,'nextAction',next.action)
        """;

    private String authorize(Principal principal) {
        if (!access.activeUser(principal).isCounselor()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
        }
        return principal.getName();
    }

    public String list(Principal principal, List<String> status, String priority, String assignee, String pageValue, String sizeValue) {
        String subject = authorize(principal);
        int page;
        int size;
        try {
            page = Integer.parseInt(pageValue);
            size = Integer.parseInt(sizeValue);
        } catch (NumberFormatException invalidPage) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_PAGE_SIZE");
        }
        var active = List.of("OPEN","IN_PROGRESS","WAITING");
        if (page < 0 || size < 1 || size > 50)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_PAGE_SIZE");
        if ((status != null && (status.isEmpty() || !active.containsAll(status)))
                || (priority != null && !List.of("URGENT","NORMAL").contains(priority))
                || (assignee != null && !"me".equals(assignee)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_FILTER");
        String query = """
            WITH filtered AS (SELECT c.*,b.booking_code,b.check_in_date,b.check_out_date,h.name,
                next.due_at,
            """ + CASE_FIELDS + " AS common " + FROM + """
            WHERE c.status IN (:statuses) AND (:priority='' OR c.priority=:priority)
                AND (:mine=false OR u.subject_key=:subject)),
            sliced AS (SELECT * FROM filtered ORDER BY priority DESC,due_at ASC NULLS LAST,
                updated_at DESC,case_code LIMIT :size OFFSET :offset),
            items AS (SELECT s.*, common || jsonb_build_object('bookingId',booking_code,'hotelName',name,
                'checkInDate',check_in_date,'checkOutDate',check_out_date,'summary',
                CASE WHEN jsonb_array_length(n.unavailable)>0 THEN '객실 제공 불가 확인: ' || (n.unavailable->>0)
                     WHEN jsonb_array_length(n.conflict)>0 THEN '상반된 근거 확인 필요' ELSE '숙박일 상태 확인 필요' END,
                'unavailableStayDates',n.unavailable,'unknownStayDates',n.unknown,'conflictStayDates',n.conflict) AS item
                FROM sliced s CROSS JOIN LATERAL (SELECT
                    coalesce(jsonb_agg(stay_date ORDER BY stay_date) FILTER (WHERE fact_status='VERIFIED' AND fulfillment_status='UNAVAILABLE'),'[]') unavailable,
                    coalesce(jsonb_agg(stay_date ORDER BY stay_date) FILTER (WHERE fact_status='UNCONFIRMED'),'[]') unknown,
                    coalesce(jsonb_agg(stay_date ORDER BY stay_date) FILTER (WHERE fact_status='CONFLICT'),'[]') conflict
                    FROM booking_nights WHERE booking_id=s.booking_id) n)
            SELECT jsonb_build_object('items',coalesce((SELECT jsonb_agg(item ORDER BY priority DESC,due_at ASC NULLS LAST,updated_at DESC,case_code) FROM items),'[]'),
               'pagination',jsonb_build_object('page',CAST(:page AS integer),'size',CAST(:size AS integer),'totalElements',count(*),'hasNext',count(*)>:end))::text FROM filtered
            """;
        long offset = (long) page * size;
        return jdbc.sql(query).param("statuses",status == null ? active : status).param("priority",priority == null ? "" : priority)
                .param("mine",assignee != null).param("subject",subject).param("page",page).param("size",size)
                .param("offset",offset).param("end",offset+size).query(String.class).single();
    }

    public String detail(Principal principal, String caseId) {
        authorize(principal);
        if (!caseId.matches("C-[0-9]{3,}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_CASE_ID");
        return jdbc.sql("SELECT (" + CASE_FIELDS + """
            || jsonb_build_object('booking',jsonb_build_object('bookingId',b.booking_code,
                'hotel',jsonb_build_object('hotelId',h.hotel_code,'name',h.name),'supplierStatus',b.supplier_status,
                'checkInDate',b.check_in_date,'checkOutDate',b.check_out_date,'adults',b.adults),
            'nights',coalesce((SELECT jsonb_agg(jsonb_build_object('stayDate',n.stay_date,
                'fulfillmentStatus',n.fulfillment_status,'factStatus',n.fact_status,'evidenceId','EV-' || a.evidence_id) ORDER BY n.stay_date)
                FROM booking_nights n LEFT JOIN evidence_nights a ON a.id=n.accepted_assertion_id WHERE n.booking_id=b.id),'[]'),
            'evidence',coalesce((SELECT jsonb_agg(jsonb_build_object('evidenceId','EV-' || e.id,'sourceType',e.source_type,
                'sourceAuthenticated',e.source_authenticated,'contentFormat',CASE WHEN e.raw_payload IS NULL THEN 'TEXT' ELSE 'STRUCTURED' END,
                'originalContent',coalesce(e.raw_text, CASE WHEN e.source_type='HOTEL_API' AND jsonb_exists(e.raw_payload,'requestJson')
                    THEN (e.raw_payload->'response')::text ELSE e.raw_payload::text END),'summary',null,
                'occurredAt',e.occurred_at,'receivedAt',e.received_at,'assertions',
                coalesce((SELECT jsonb_agg(jsonb_build_object('stayDate',n.stay_date,'reportedProvisionStatus',a.reported_status,
                    'reviewStatus',a.review_status) ORDER BY n.stay_date) FROM evidence_nights a JOIN booking_nights n ON n.id=a.booking_night_id
                    WHERE a.evidence_id=e.id),'[]')) ORDER BY e.received_at,e.id) FROM evidence_records e WHERE e.booking_id=b.id),'[]'),
            'tasks',coalesce((SELECT jsonb_agg(jsonb_build_object('taskId','TASK-' || t.id,'type',t.task_type,'status',t.status,
                'assigneeId',owner.subject_key,'dueAt',t.due_at) ORDER BY t.due_at ASC NULLS LAST,t.created_at,t.id)
                FROM case_tasks t LEFT JOIN users owner ON owner.id=t.assignee_id WHERE t.case_id=c.id AND t.status IN ('TODO','IN_PROGRESS')),'[]'),
            'customerReports',coalesce((SELECT jsonb_agg(jsonb_build_object('reportId',r.report_code,'issueType',r.issue_type,
                'atHotel',r.at_hotel,'receivedAt',r.received_at) ORDER BY r.received_at,r.id) FROM incident_reports r
                WHERE r.case_id=c.id AND r.booking_id=b.id),'[]')))::text
            """ + FROM + " WHERE c.case_code=:code").param("code",caseId).query(String.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"CASE_NOT_FOUND"));
    }

    /** API-011 사건별 대화 이력을 공개 메시지 번호로 페이지 조회한다. */
    public String messages(Principal principal, String caseId, String limitValue, String beforeMessageId) {
        CaseAccess.ActiveUser user = access.chatUser(principal, caseId);
        int limit = messageLimit(limitValue);
        long caseKey = access.chatCase(user, caseId, false).id();

        MessageCursor cursor = beforeMessageId == null ? null : findCursor(caseKey, beforeMessageId);
        String cursorPredicate = cursor == null ? "" : "AND (m.sent_at, m.id) < (:cursorTime, :cursorId)";
        var statement = jdbc.sql(MESSAGE_PAGE_SQL.formatted(cursorPredicate))
                .param("caseId", caseId)
                .param("caseKey", caseKey)
                .param("limit", limit)
                .param("lookaheadLimit", limit + 1);
        if (cursor != null) {
            statement.param("cursorTime", cursor.sentAt()).param("cursorId", cursor.id());
        }
        return statement.query(String.class).single();
    }

    private static int messageLimit(String value) {
        if (value == null) return 30;
        try {
            int limit = Integer.parseInt(value);
            if (limit >= 1 && limit <= 50) return limit;
        } catch (NumberFormatException invalidLimit) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PAGE_SIZE");
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PAGE_SIZE");
    }

    private MessageCursor findCursor(long caseKey, String messageId) {
        if (messageId.isBlank() || messageId.indexOf(0) >= 0 || messageId.codePointCount(0, messageId.length()) > 40) {
            throw invalidMessageCursor();
        }
        return jdbc.sql("""
                SELECT id, sent_at FROM chat_messages
                WHERE case_id = :caseKey AND message_code = :messageId
                """)
                .param("caseKey", caseKey)
                .param("messageId", messageId)
                .query((rs, row) -> new MessageCursor(rs.getLong("id"), rs.getObject("sent_at", OffsetDateTime.class)))
                .optional().orElseThrow(CaseQueries::invalidMessageCursor);
    }

    private static ResponseStatusException invalidMessageCursor() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE_CURSOR");
    }

    // 발송 당시 비교 기준액을 복원하는 계약은 API-013에서 정한다. V1의 현재 예약 금액은 사용하지 않는다.
    private static final String MESSAGE_PAGE_SQL = """
            WITH candidates AS (
                SELECT m.id, m.case_id, m.message_code, m.sender_type, m.message_type, m.body, m.sent_at, m.offer_id
                FROM chat_messages m
                WHERE m.case_id = :caseKey %s
                ORDER BY m.sent_at DESC, m.id DESC LIMIT :lookaheadLimit
            ), page AS (
                SELECT * FROM candidates ORDER BY sent_at DESC, id DESC LIMIT :limit
            ), bounds AS (
                SELECT count(*) > :limit AS has_more FROM candidates
            )
            SELECT jsonb_build_object(
                'caseId', CAST(:caseId AS text),
                'items', coalesce((
                    SELECT jsonb_agg(jsonb_build_object(
                        'messageId', m.message_code, 'senderType', m.sender_type,
                        'messageType', m.message_type, 'body', m.body, 'sentAt', m.sent_at,
                        'offer', (
                            SELECT jsonb_build_object('offerId', o.offer_code, 'status', o.status, 'items', coalesce((
                                SELECT jsonb_agg(jsonb_build_object(
                                    'quoteId', q.quote_code, 'displayOrder', i.display_order,
                                    'propertyName', q.property_name, 'distanceMeters', q.distance_meters,
                                    'checkInDate', q.check_in_date, 'checkOutDate', q.check_out_date,
                                    'adults', q.adults, 'rooms', q.rooms, 'totalAmount', q.total_amount,
                                    'currency', q.currency, 'priceDifference', NULL::bigint,
                                    'checkedAt', q.checked_at, 'expiresAt', q.expires_at
                                ) ORDER BY i.display_order)
                                FROM offer_items i JOIN alternative_quotes q ON q.id = i.quote_id AND q.case_id = i.case_id
                                WHERE i.offer_id = o.id AND i.case_id = o.case_id
                            ), '[]'::jsonb))
                            FROM offers o WHERE o.id = m.offer_id AND o.case_id = m.case_id
                        )
                    ) ORDER BY m.sent_at, m.id) FROM page m
                ), '[]'::jsonb),
                'pagination', jsonb_build_object('hasMoreBefore', has_more, 'nextBeforeMessageId', CASE WHEN has_more THEN (
                    SELECT message_code FROM page ORDER BY sent_at, id LIMIT 1
                ) END)
            )::text FROM bounds
            """;

    private record MessageCursor(long id, OffsetDateTime sentAt) {}
}
