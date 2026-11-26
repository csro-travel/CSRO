package com.csro.backend.assist;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/** 상담사 안내 초안 요청. 상담사 전용이며 결과는 제안일 뿐 전송·상태 변경을 하지 않는다. */
@RestController
@RequestMapping(value = "/api/v1/cases", produces = MediaType.APPLICATION_JSON_VALUE)
public class AssistController {
    private static final Set<String> KINDS = Set.of("GUIDANCE", "CLOSURE_SUMMARY");
    private final AssistService assist;

    AssistController(AssistService assist) {
        this.assist = assist;
    }

    @PostMapping(value = "/{caseId}/assist-drafts", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AssistService.AssistDraft> draft(HttpServletRequest request, @PathVariable String caseId,
            @RequestBody JsonNode body) {
        if (body == null || !body.isObject() || !Set.copyOf(body.propertyNames()).equals(Set.of("kind"))
                || !body.get("kind").isString() || !KINDS.contains(body.get("kind").stringValue()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_ASSIST_REQUEST");
        return ResponseEntity.ok().header("Cache-Control", "private, no-store")
                .body(assist.draft(request.getUserPrincipal(), caseId, body.get("kind").stringValue()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> error(Exception failure) {
        int status = 500;
        String code = "INTERNAL_ERROR";
        if (failure instanceof ResponseStatusException typed) {
            status = typed.getStatusCode().value();
            code = typed.getReason();
        } else if (failure instanceof HttpMessageNotReadableException) {
            status = 400;
            code = "INVALID_ASSIST_REQUEST";
        } else if (failure instanceof HttpMediaTypeNotSupportedException) {
            status = 415;
            code = "UNSUPPORTED_MEDIA_TYPE";
        }
        ProblemDetail body = ProblemDetail.forStatus(status);
        body.setDetail(switch (code) {
            case "INVALID_ASSIST_REQUEST" -> "kind에 GUIDANCE 또는 CLOSURE_SUMMARY 하나만 보내 주세요.";
            case "ASSIST_UNAVAILABLE" -> "안내 초안을 지금 만들 수 없습니다. 화면의 기본 안내를 사용해 주세요.";
            case null, default -> body.getTitle();
        });
        body.setProperty("code", code);
        body.setProperty("traceId", UUID.randomUUID().toString());
        var response = ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header("Cache-Control", "private, no-store");
        if (status == 401) response.header("WWW-Authenticate", "Bearer");
        return response.body(body);
    }
}
