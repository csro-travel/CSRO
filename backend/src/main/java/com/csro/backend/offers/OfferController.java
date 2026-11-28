package com.csro.backend.offers;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import com.csro.backend.quotes.QuoteSearchRuntime;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/** API-013 sends stored quotes; API-014 records the customer's choice, without booking. */
@RestController
@RequestMapping(value = "/api/v1/cases/{caseId}", produces = MediaType.APPLICATION_JSON_VALUE)
public class OfferController {
    private final OfferSelectionService offers;
    private final QuoteSearchRuntime runtime;

    OfferController(OfferSelectionService offers, QuoteSearchRuntime runtime) {
        this.offers = offers;
        this.runtime = runtime;
    }

    @PostMapping(value = "/offers", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OfferSelectionService.SendResult> send(HttpServletRequest request,
            @PathVariable String caseId, @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode body) {
        String code = "INVALID_OFFER_REQUEST";
        requireFields(body, Set.of("quoteIds"), key, code);
        JsonNode ids = body.get("quoteIds");
        if (ids == null || !ids.isArray() || ids.size() < 1 || ids.size() > 3) throw invalid(code);
        var quoteIds = new ArrayList<String>();
        for (JsonNode id : ids) quoteIds.add(identifier(id, 80, code));
        return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control", "private, no-store")
                .body(offers.send(request.getUserPrincipal(), caseId, quoteIds, key,
                        runtime.effectiveAt(), runtime.syntheticDemo()));
    }

    @PutMapping(value = "/selection", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OfferSelectionService.SelectionResult> select(HttpServletRequest request,
            @PathVariable String caseId, @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode body) {
        String code = "INVALID_SELECTION_REQUEST";
        requireFields(body, Set.of("offerId", "quoteId"), key, code);
        String offerId = identifier(body.get("offerId"), 60, code);
        String quoteId = identifier(body.get("quoteId"), 80, code);
        return ResponseEntity.ok().header("Cache-Control", "private, no-store")
                .body(offers.select(request.getUserPrincipal(), caseId, offerId, quoteId, key,
                        runtime.effectiveAt(), runtime.syntheticDemo()));
    }

    private static void requireFields(JsonNode body, Set<String> fields, String key, String code) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,100}") || body == null || !body.isObject()
                || body.size() != fields.size()) throw invalid(code);
        for (String field : body.propertyNames()) if (!fields.contains(field)) throw invalid(code);
    }

    private static String identifier(JsonNode node, int maximum, String code) {
        if (node == null || !node.isString()) throw invalid(code);
        String value = node.stringValue();
        if (value.isBlank() || value.indexOf(0) >= 0 || value.codePointCount(0, value.length()) > maximum)
            throw invalid(code);
        return value;
    }

    private static ResponseStatusException invalid(String code) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> error(Exception failure, HttpServletRequest request) {
        int status = 500;
        String code = "INTERNAL_ERROR";
        if (failure instanceof ResponseStatusException typed) {
            status = typed.getStatusCode().value();
            code = typed.getReason();
        } else if (failure instanceof HttpMessageNotReadableException) {
            status = 400;
            code = request.getRequestURI().endsWith("/selection")
                    ? "INVALID_SELECTION_REQUEST" : "INVALID_OFFER_REQUEST";
        } else if (failure instanceof HttpMediaTypeNotSupportedException) {
            status = 415;
            code = "UNSUPPORTED_MEDIA_TYPE";
        }
        ProblemDetail body = ProblemDetail.forStatus(status);
        body.setDetail(switch (code) {
            case "INVALID_OFFER_REQUEST" -> "견적 3곳과 Idempotency-Key를 확인해 주세요.";
            case "INVALID_SELECTION_REQUEST" -> "수신한 제안·견적 번호와 Idempotency-Key를 확인해 주세요.";
            case "IDEMPOTENCY_KEY_REUSED" -> "같은 요청 키에 다른 내용을 사용할 수 없습니다.";
            case "QUOTE_EXPIRED" -> "견적 기한이 지났습니다. 상담사에게 새 제안을 요청해 주세요.";
            case "OFFER_NOT_ACTIVE", "NEW_OFFER_REQUIRED" -> "현재 제안을 다시 확인해 주세요.";
            case "REPLACEMENT_LOCKED" -> "예약 처리 상태를 먼저 확인해 주세요.";
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
