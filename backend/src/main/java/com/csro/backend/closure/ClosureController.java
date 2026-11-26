package com.csro.backend.closure;

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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/** API-017 check-in, API-008 task completion and API-018 closure under the approved minimal contract. */
@RestController
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class ClosureController {
    private final ClosureService closures;

    ClosureController(ClosureService closures) {
        this.closures = closures;
    }

    @PostMapping(value = "/replacement-bookings/{replacementBookingId}/checkin-confirmations", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ClosureService.Checkin> checkin(HttpServletRequest request, @PathVariable String replacementBookingId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode body) {
        if (body == null || !body.isObject() || !body.isEmpty()) throw bad("INVALID_CHECKIN_REQUEST");
        return created(closures.checkin(request.getUserPrincipal(), replacementBookingId, key));
    }

    @PatchMapping(value = "/cases/{caseId}/tasks/{taskId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ClosureService.TaskCompletion> completeTask(HttpServletRequest request, @PathVariable String caseId,
            @PathVariable String taskId, @RequestBody JsonNode body) {
        if (body == null || !body.isObject() || !Set.copyOf(body.propertyNames()).equals(Set.of("status", "completionNote"))
                || !"DONE".equals(body.get("status").asString(null)) || !body.get("completionNote").isString())
            throw bad("INVALID_TASK_UPDATE");
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(closures.completeTask(
                request.getUserPrincipal(), caseId, taskId, body.get("completionNote").stringValue()));
    }

    @PostMapping(value = "/cases/{caseId}/closures", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ClosureService.Closure> close(HttpServletRequest request, @PathVariable String caseId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode body) {
        if (body == null || !body.isObject() || !body.isEmpty()) throw bad("INVALID_CLOSURE_REQUEST");
        return created(closures.close(request.getUserPrincipal(), caseId, key));
    }

    private static <T> ResponseEntity<T> created(T body) {
        return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control", "private, no-store").body(body);
    }

    private static ResponseStatusException bad(String code) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
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
            code = "INVALID_REQUEST_BODY";
        } else if (failure instanceof HttpMediaTypeNotSupportedException) {
            status = 415;
            code = "UNSUPPORTED_MEDIA_TYPE";
        }
        ProblemDetail body = ProblemDetail.forStatus(status);
        body.setDetail(switch (code) {
            case "INVALID_CHECKIN_REQUEST" -> "빈 요청 본문과 Idempotency-Key를 확인해 주세요.";
            case "INVALID_TASK_UPDATE" -> "완료 상태와 1~1000자의 처리 내용을 입력해 주세요.";
            case "INVALID_CLOSURE_REQUEST" -> "빈 요청 본문과 Idempotency-Key를 확인해 주세요.";
            case "REPLACEMENT_NOT_CONFIRMED" -> "가상 예약이 확정된 뒤에 입실을 확인할 수 있습니다.";
            case "CHECKIN_ALREADY_CONFIRMED" -> "이미 입실 확인이 기록되었습니다.";
            case "TASK_ALREADY_COMPLETED" -> "이미 다른 내용으로 완료된 업무입니다.";
            case "CHECKIN_REQUIRED" -> "입실 확인 업무는 고객의 입실 확인으로만 완료됩니다.";
            case "CASE_CLOSED", "CASE_ALREADY_CLOSED" -> "이미 종료된 사건입니다.";
            case "CASE_CLOSE_BLOCKED" -> "종료 조건을 아직 충족하지 않았습니다. 남은 항목을 확인해 주세요.";
            case "IDEMPOTENCY_KEY_REUSED" -> "같은 요청 키를 다른 요청에 사용할 수 없습니다.";
            case null, default -> body.getTitle();
        });
        if (failure instanceof ClosureService.CloseBlocked blocked) body.setProperty("blockers", blocked.blockers());
        body.setProperty("code", code);
        body.setProperty("traceId", UUID.randomUUID().toString());
        var response = ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header("Cache-Control", "private, no-store");
        if (status == 401) response.header("WWW-Authenticate", "Bearer");
        return response.body(body);
    }
}
