package com.csro.backend.replacements;

import com.csro.backend.quotes.QuoteSearchRuntime;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/** API-015 accepts one durable intention. API-016 reads its business-DB result only. */
@RestController
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class ReplacementController {
    private final ReplacementService replacements;
    private final QuoteSearchRuntime runtime;

    ReplacementController(ReplacementService replacements, QuoteSearchRuntime runtime) {
        this.replacements = replacements;
        this.runtime = runtime;
    }

    @PostMapping(value = "/cases/{caseId}/replacement-bookings", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReplacementService.Acceptance> request(HttpServletRequest request,
            @PathVariable String caseId, @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode body) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,100}") || body == null || !body.isObject()
                || body.size() != 1 || !body.has("selectionId") || !body.get("selectionId").isString())
            throw invalid();
        String selection = body.get("selectionId").stringValue();
        if (selection.isBlank() || selection.length() > 60 || selection.chars().anyMatch(Character::isISOControl))
            throw invalid();
        var accepted = replacements.request(request.getUserPrincipal(), caseId, selection, key,
                runtime.effectiveAt(), runtime.syntheticDemo());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/replacement-bookings/" + accepted.replacementBookingId()))
                .header("Cache-Control", "private, no-store").body(accepted);
    }

    @GetMapping("/replacement-bookings/{replacementBookingId}")
    public ResponseEntity<String> view(HttpServletRequest request, @PathVariable String replacementBookingId) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store")
                .body(replacements.view(request.getUserPrincipal(), replacementBookingId));
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_REPLACEMENT_REQUEST");
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
            code = "INVALID_REPLACEMENT_REQUEST";
        } else if (failure instanceof HttpMediaTypeNotSupportedException) {
            status = 415;
            code = "UNSUPPORTED_MEDIA_TYPE";
        }
        ProblemDetail body = ProblemDetail.forStatus(status);
        body.setDetail(switch (code) {
            case "INVALID_REPLACEMENT_REQUEST" -> "선택 번호와 Idempotency-Key를 확인해 주세요.";
            case "CURRENT_SELECTION_CHANGED" -> "고객의 현재 선택을 다시 확인해 주세요.";
            case "SELECTION_ALREADY_REQUESTED" -> "이미 접수한 예약 요청의 결과를 확인해 주세요.";
            case "REPLACEMENT_LOCKED" -> "기존 예약 요청의 결과를 먼저 확인해 주세요.";
            case "QUOTE_EXPIRED" -> "견적 기한이 지났습니다. 새 견적을 고객에게 제안해 주세요.";
            case "IDEMPOTENCY_KEY_REUSED" -> "같은 요청 키에 다른 선택을 사용할 수 없습니다.";
            case null, default -> body.getTitle();
        });
        if (failure instanceof ReplacementService.SelectionAlreadyRequested existing)
            body.setProperty("replacementBookingId", existing.replacementBookingId());
        body.setProperty("code", code);
        body.setProperty("traceId", UUID.randomUUID().toString());
        var response = ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header("Cache-Control", "private, no-store");
        if (status == 401) response.header("WWW-Authenticate", "Bearer");
        return response.body(body);
    }
}
