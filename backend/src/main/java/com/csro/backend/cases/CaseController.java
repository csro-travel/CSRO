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
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping(value="/api/v1/cases", produces=MediaType.APPLICATION_JSON_VALUE)
public class CaseController {
    private final CaseQueries queries;
    private final CaseMessageCommands messageCommands;
    private final Environment environment;

    CaseController(CaseQueries queries, CaseMessageCommands messageCommands, Environment environment) {
        this.queries = queries;
        this.messageCommands = messageCommands;
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
            @RequestParam(defaultValue="20") String size, @RequestParam(required=false) String q) {
        return ok(queries.list(principal(request), status, priority, assignee, page, size, q));
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

    /** API-012 사건별 상담 채팅 메시지 전송. */
    @PostMapping("/{caseId}/messages")
    public ResponseEntity<String> sendMessage(HttpServletRequest request, @PathVariable String caseId,
            @RequestBody SendMessageRequest payload) {
        String message = messageCommands.send(request.getUserPrincipal(), caseId, payload.body());
        return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control", "private, no-store").body(message);
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
        } else if (error instanceof HttpMessageNotReadableException) {
            status = 400;
            code = "INVALID_MESSAGE_BODY";
        } else if (error instanceof HttpMediaTypeNotSupportedException) {
            status = 415;
            code = "UNSUPPORTED_MEDIA_TYPE";
        }
        var body = ProblemDetail.forStatus(status);
        body.setDetail(switch (code) {
            case "INVALID_MESSAGE_CURSOR" -> "이 사건에서 사용할 수 없는 이전 메시지 기준입니다.";
            case "INVALID_MESSAGE_BODY" -> "비어 있지 않은 문자열 body 한 개를 입력해 주세요.";
            case "CASE_CLOSED" -> "종료된 사건에는 메시지를 보낼 수 없습니다.";
            case "UNSUPPORTED_MEDIA_TYPE" -> "JSON 형식으로 메시지를 보내 주세요.";
            case null, default -> body.getTitle();
        });
        body.setProperty("code",code);
        body.setProperty("traceId",UUID.randomUUID().toString());
        var response = ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header("Cache-Control","private, no-store");
        if (status == 401) response.header("WWW-Authenticate","Bearer");
        return response.body(body);
    }
}
