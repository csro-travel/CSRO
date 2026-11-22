package com.csro.backend.cases;

import java.security.Principal;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.Arrays;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping(value="/api/v1/cases", produces=MediaType.APPLICATION_JSON_VALUE)
public class CaseController {
    private final CaseQueries queries;
    private final Environment environment;

    CaseController(CaseQueries queries, Environment environment) {
        this.queries = queries;
        this.environment = environment;
    }

    private Principal principal(HttpServletRequest request) {
        if (!Arrays.equals(environment.getActiveProfiles(), new String[]{"local-demo"}))
            return request.getUserPrincipal();
        String token = environment.getProperty("CSRO_DEMO_TOKEN", "");
        String subject = environment.getProperty("CSRO_DEMO_SUBJECT", "");
        String header = request.getHeader("Authorization");
        if (!"127.0.0.1".equals(environment.getProperty("server.address"))
                || !"127.0.0.1".equals(request.getLocalAddr()) || !"127.0.0.1".equals(request.getRemoteAddr())
                || token.isBlank() || subject.isBlank() || header == null
                || !MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8)))
            return null;
        return () -> subject;
    }

    @GetMapping
    public ResponseEntity<String> list(HttpServletRequest request,
            @RequestParam(required=false) List<String> status, @RequestParam(required=false) String priority,
            @RequestParam(required=false) String assignee, @RequestParam(defaultValue="0") String page,
            @RequestParam(defaultValue="20") String size) {
        return ok(queries.list(principal(request), status, priority, assignee, page, size));
    }

    @GetMapping("/{caseId}")
    public ResponseEntity<String> detail(HttpServletRequest request, @PathVariable String caseId) {
        return ok(queries.detail(principal(request), caseId));
    }

    /** API-011 사건별 상담 채팅 메시지 목록 조회. */
    @GetMapping("/{caseId}/messages")
    public ResponseEntity<String> messages(HttpServletRequest request, @PathVariable String caseId,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String beforeMessageId) {
        return ok(queries.messages(principal(request), caseId, limit, beforeMessageId));
    }

    private ResponseEntity<String> ok(String json) {
        return ResponseEntity.ok().header("Cache-Control","private, no-store").body(json);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> error(Exception error) {
        int status = 500;
        String code = "INTERNAL_ERROR";
        if (error instanceof ResponseStatusException ex) {
            status = ex.getStatusCode().value();
            code = ex.getReason();
        }
        var body = ProblemDetail.forStatus(status);
        body.setDetail("INVALID_MESSAGE_CURSOR".equals(code)
                ? "이 사건에서 사용할 수 없는 이전 메시지 기준입니다."
                : body.getTitle());
        body.setProperty("code",code);
        body.setProperty("traceId",UUID.randomUUID().toString());
        var response = ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header("Cache-Control","private, no-store");
        if (status == 401) response.header("WWW-Authenticate","Bearer");
        return response.body(body);
    }
}
