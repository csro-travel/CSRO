package com.csro.backend.cases;

import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class CaseAccess {
    private final JdbcClient jdbc;

    CaseAccess(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    ActiveUser activeUser(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw unauthenticated();
        }
        return jdbc.sql("SELECT id, role FROM users WHERE is_active AND subject_key = :subject")
                .param("subject", principal.getName())
                .query((rs, row) -> new ActiveUser(rs.getLong("id"), rs.getString("role")))
                .optional().orElseThrow(CaseAccess::unauthenticated);
    }

    ActiveUser chatUser(Principal principal, String caseId) {
        ActiveUser user = activeUser(principal);
        if (!user.isCounselor() && !"CUSTOMER".equals(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
        }
        if (!caseId.matches("C-[0-9]{3,38}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CASE_ID");
        }
        return user;
    }

    ChatCase chatCase(ActiveUser user, String caseId, boolean lockForSend) {
        // 전송 트랜잭션이 끝날 때까지 사건 상태 변경을 막는다.
        String lock = lockForSend ? "FOR SHARE OF c" : "";
        return jdbc.sql("""
                SELECT c.id, c.status FROM cases c JOIN bookings b ON b.id = c.booking_id
                WHERE c.case_code = :caseId AND (:counselor OR b.customer_id = :userId)
                """ + lock)
                .param("caseId", caseId)
                .param("counselor", user.isCounselor())
                .param("userId", user.id())
                .query((rs, row) -> new ChatCase(rs.getLong("id"), rs.getString("status")))
                .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND"));
    }

    private static ResponseStatusException unauthenticated() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    record ActiveUser(long id, String role) {
        boolean isCounselor() {
            return "COUNSELOR".equals(role);
        }
    }

    record ChatCase(long id, String status) {}
}
