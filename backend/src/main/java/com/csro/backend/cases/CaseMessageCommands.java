package com.csro.backend.cases;

import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CaseMessageCommands {
    private final JdbcClient jdbc;
    private final CaseAccess access;

    CaseMessageCommands(JdbcClient jdbc, CaseAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    /** API-012 인증된 사건 참여자의 일반 메시지를 저장한다. */
    @Transactional
    public String send(Principal principal, String caseId, String body) {
        CaseAccess.ActiveUser sender = access.chatUser(principal, caseId);
        CaseAccess.ChatCase chatCase = access.chatCase(sender, caseId, true);
        if ("CLOSED".equals(chatCase.status())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CASE_CLOSED");
        }
        if (body.isBlank() || body.indexOf(0) >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE_BODY");
        }

        return jdbc.sql("""
                INSERT INTO chat_messages (message_code, case_id, sender_user_id, sender_type,
                                           message_type, body, sent_at)
                VALUES (:messageId, :caseKey, :senderId, :senderType, 'TEXT', :body, clock_timestamp())
                RETURNING jsonb_build_object('messageId', message_code, 'senderType', sender_type,
                    'messageType', message_type, 'body', body, 'sentAt', sent_at, 'offer', NULL::jsonb)::text
                """)
                .param("messageId", "MSG-" + UUID.randomUUID())
                .param("caseKey", chatCase.id())
                .param("senderId", sender.id())
                .param("senderType", sender.role())
                .param("body", body)
                .query(String.class).single();
    }
}
