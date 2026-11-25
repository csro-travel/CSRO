package com.csro.backend.resolution;

import com.csro.backend.quotes.QuoteSearchRuntime;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
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
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** API-017 direct customer check-in, API-008 task completion, API-018 explicit case closure. */
@RestController
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class CaseResolutionController {
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final CaseResolutionService resolutions;
    private final QuoteSearchRuntime runtime;

    CaseResolutionController(CaseResolutionService resolutions, QuoteSearchRuntime runtime) {
        this.resolutions = resolutions;
        this.runtime = runtime;
    }

    @PostMapping("/replacement-bookings/{replacementBookingId}/checkin-confirmations")
    public ResponseEntity<String> checkin(HttpServletRequest request, @PathVariable String replacementBookingId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody byte[] bytes) {
        String code = "INVALID_CHECKIN_REQUEST";
        JsonNode body = body(request, bytes, key, code);
        fields(body, Set.of("checkedIn"), Set.of(), code);
        if (!body.path("checkedIn").isBoolean() || !body.path("checkedIn").booleanValue()) throw invalid(code);
        return json(HttpStatus.CREATED, resolutions.checkin(request.getUserPrincipal(), replacementBookingId, key,
                runtime.effectiveAt(), runtime.syntheticDemo()));
    }

    @PatchMapping("/cases/{caseId}/tasks/{taskId}")
    public ResponseEntity<String> completeTask(HttpServletRequest request, @PathVariable String caseId,
            @PathVariable String taskId, @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody byte[] bytes) {
        String code = "INVALID_TASK_COMPLETION";
        JsonNode body = body(request, bytes, key, code);
        fields(body, Set.of("status", "completionNote", "expectedVersion"), Set.of("followupOutcome"), code);
        if (!body.path("status").isString() || !"DONE".equals(body.path("status").stringValue())) throw invalid(code);
        String note = text(body, "completionNote", 1000, code);
        Long version = version(body, code);
        if (body.has("followupOutcome") && !body.path("followupOutcome").isString()) throw invalid(code);
        String outcome = body.has("followupOutcome") ? body.path("followupOutcome").stringValue() : null;
        if (outcome != null && !Set.of("COMPLETED", "NOT_REQUIRED").contains(outcome)) throw invalid(code);
        var command = new CaseResolutionService.TaskCompletion("DONE", note, version, outcome);
        return json(HttpStatus.OK, resolutions.completeTask(request.getUserPrincipal(), caseId, taskId, key, command));
    }

    @PostMapping("/cases/{caseId}/closures")
    public ResponseEntity<String> close(HttpServletRequest request, @PathVariable String caseId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody byte[] bytes) {
        String code = "INVALID_CLOSURE_REQUEST";
        JsonNode body = body(request, bytes, key, code);
        fields(body, Set.of("reason", "expectedVersion"), Set.of(), code);
        var command = new CaseResolutionService.Closure(text(body, "reason", 80, code), version(body, code));
        return json(HttpStatus.CREATED, resolutions.close(request.getUserPrincipal(), caseId, key, command,
                runtime.effectiveAt(), runtime.syntheticDemo()));
    }

    private static ResponseEntity<String> json(HttpStatus status, String result) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "private, no-store").body(result);
    }

    private static JsonNode body(HttpServletRequest request, byte[] bytes, String key, String code) {
        // Resolve the handler before checking media type so every 415 has the
        // same safe ProblemDetail/code/traceId as other command errors.
        try {
            MediaType type = MediaType.parseMediaType(request.getContentType() == null ? "" : request.getContentType());
            if (!"application".equalsIgnoreCase(type.getType()) || !"json".equalsIgnoreCase(type.getSubtype()))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException unsupported) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE");
        }
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,100}") || bytes == null || bytes.length == 0) throw invalid(code);
        try {
            JsonNode value = JSON.readTree(bytes);
            if (value == null || !value.isObject()) throw invalid(code);
            return value;
        } catch (RuntimeException malformed) { throw invalid(code); }
    }

    private static void fields(JsonNode body, Set<String> required, Set<String> optional, String code) {
        for (String field : required) if (!body.has(field)) throw invalid(code);
        for (String field : body.propertyNames()) if (!required.contains(field) && !optional.contains(field)) throw invalid(code);
    }

    private static String text(JsonNode body, String field, int maximum, String code) {
        JsonNode item = body.path(field);
        if (!item.isString()) throw invalid(code);
        String value = item.stringValue().trim();
        if (value.isEmpty() || value.codePointCount(0, value.length()) > maximum || value.indexOf(0) >= 0) throw invalid(code);
        return value;
    }

    private static Long version(JsonNode body, String code) {
        JsonNode item = body.path("expectedVersion");
        if (!item.isIntegralNumber() || !item.canConvertToLong() || item.longValue() < 0) throw invalid(code);
        return item.longValue();
    }

    private static ResponseStatusException invalid(String code) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, code); }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> error(Exception failure, HttpServletRequest request) {
        int status = 500;
        String code = "INTERNAL_ERROR";
        if (failure instanceof ResponseStatusException typed) {
            status = typed.getStatusCode().value();
            code = typed.getReason() == null ? "INTERNAL_ERROR" : typed.getReason();
        } else if (failure instanceof HttpMediaTypeNotSupportedException || failure instanceof InvalidMediaTypeException) {
            status = 415;
            code = "UNSUPPORTED_MEDIA_TYPE";
        } else if (failure instanceof HttpMessageNotReadableException) {
            status = 400;
            String path = request.getRequestURI();
            code = path.endsWith("/checkin-confirmations") ? "INVALID_CHECKIN_REQUEST"
                    : path.endsWith("/closures") ? "INVALID_CLOSURE_REQUEST" : "INVALID_TASK_COMPLETION";
        }
        ProblemDetail body = ProblemDetail.forStatus(status);
        body.setDetail(switch (code) {
            case "INVALID_CHECKIN_REQUEST" -> "입실 확인 의도와 Idempotency-Key를 확인해 주세요.";
            case "INVALID_TASK_COMPLETION" -> "완료 상태·메모·사건 버전과 Idempotency-Key를 확인해 주세요.";
            case "INVALID_CLOSURE_REQUEST" -> "종료 사유·사건 버전과 Idempotency-Key를 확인해 주세요.";
            case "UNSUPPORTED_MEDIA_TYPE" -> "JSON 형식으로 요청해 주세요.";
            case "CASE_NOT_RESOLVED" -> "입실·숙박일 보장·필수 업무의 부족 조건을 확인해 주세요.";
            case "CASE_VERSION_CONFLICT" -> "사건이 변경되었습니다. 현재 상태를 다시 확인해 주세요.";
            case "CHECKIN_ALREADY_CONFIRMED" -> "이미 저장한 입실 확인 결과를 확인해 주세요.";
            case "IDEMPOTENCY_KEY_REUSED" -> "같은 요청 키에 다른 내용을 사용할 수 없습니다.";
            case null, default -> body.getTitle();
        });
        if (failure instanceof CaseResolutionService.CaseNotResolved unresolved) {
            body.setProperty("blockers", unresolved.blockers());
            body.setProperty("missingConditions", unresolved.missingConditions());
        }
        if (failure instanceof CaseResolutionService.CheckinAlreadyConfirmed existing)
            body.setProperty("checkinConfirmationId", existing.checkinConfirmationId());
        if (failure instanceof CaseResolutionService.CaseVersionConflict changed)
            body.setProperty("currentVersion", changed.currentVersion());
        body.setProperty("code", code);
        body.setProperty("traceId", UUID.randomUUID().toString());
        var response = ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header("Cache-Control", "private, no-store");
        if (status == 401) response.header("WWW-Authenticate", "Bearer");
        return response.body(body);
    }
}
