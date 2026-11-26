package com.csro.backend.assist;

import com.csro.backend.cases.CaseQueries;
import java.security.Principal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 상담사가 요청했을 때만 다음 조치·고객 안내 초안 또는 종료 요약을 제안한다.
 * 사건·업무·숙박일·예약은 바꾸지 않으며 유일한 쓰기는 성공 뒤의 감사 기록 한 건이다.
 */
@Service
public class AssistService {
    private final CaseQueries cases;
    private final AssistStore store;
    private final AssistAgentClient agent;

    AssistService(CaseQueries cases, AssistStore store, AssistAgentClient agent) {
        this.cases = cases;
        this.store = store;
        this.agent = agent;
    }

    public AssistDraft draft(Principal principal, String caseId, String kind) {
        String subject = cases.authorize(principal);
        if (caseId == null || !caseId.matches("C-[0-9]{3,38}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CASE_ID");
        var facts = store.facts(caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND"));
        // 읽기는 끝났고 열린 트랜잭션이 없다. 실패하면 아무것도 저장하지 않고 503을 그대로 올린다.
        var draft = agent.generate(caseId, kind, facts.json());
        Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
        store.recordGenerated(facts, subject, kind, draft.source(), draft.model(), at.atOffset(ZoneOffset.UTC));
        return new AssistDraft(caseId, kind, draft.source(), draft.model(), at.toString(), draft.summary(),
                draft.nextActions(), draft.customerMessageDraft(), draft.warnings());
    }

    public record AssistDraft(String caseId, String kind, String source, String model, String generatedAt,
            String summary, List<String> nextActions, String customerMessageDraft, List<String> warnings) {}
}
