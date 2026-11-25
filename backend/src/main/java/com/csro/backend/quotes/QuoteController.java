package com.csro.backend.quotes;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/** API-009 reads saved quotes; API-010 only accepts a durable search job. */
@RestController
@RequestMapping(value = "/api/v1/cases/{caseId}", produces = MediaType.APPLICATION_JSON_VALUE)
public class QuoteController {
    private final QuoteSearchService searches;
    private final QuoteSearchRuntime runtime;

    QuoteController(QuoteSearchService searches, QuoteSearchRuntime runtime) {
        this.searches = searches;
        this.runtime = runtime;
    }

    @GetMapping("/quotes")
    public ResponseEntity<String> quotes(HttpServletRequest request, @PathVariable String caseId) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store")
                .body(searches.view(request.getUserPrincipal(), caseId));
    }

    @PostMapping(value = "/quote-searches", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<QuoteSearchService.Acceptance> search(HttpServletRequest request,
            @PathVariable String caseId, @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode body) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,100}") || !body.isObject()) throw invalidRequest();
        for (String field : body.propertyNames()) {
            if (!Set.of("radiusMeters", "maxAverageNightlyAmount").contains(field)) throw invalidRequest();
        }
        int radius = 3000;
        if (body.has("radiusMeters")) {
            JsonNode value = body.get("radiusMeters");
            if (!value.isIntegralNumber() || !value.canConvertToInt()) throw invalidRequest();
            radius = value.intValue();
        }
        Long maximum = null;
        if (body.has("maxAverageNightlyAmount")) {
            JsonNode value = body.get("maxAverageNightlyAmount");
            if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalidRequest();
            maximum = value.longValue();
        }
        if (radius < 1 || radius > 20000 || (maximum != null && maximum < 1)) throw invalidRequest();
        var accepted = searches.request(request.getUserPrincipal(), caseId,
                new QuoteSearchService.SearchOptions(radius, maximum), key,
                runtime.effectiveAt(), runtime.syntheticDemo());
        return ResponseEntity.status(HttpStatus.ACCEPTED).header("Cache-Control", "private, no-store")
                .body(accepted);
    }

    private static ResponseStatusException invalidRequest() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QUOTE_SEARCH_REQUEST");
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
            code = "INVALID_QUOTE_SEARCH_REQUEST";
        } else if (failure instanceof HttpMediaTypeNotSupportedException) {
            status = 415;
            code = "UNSUPPORTED_MEDIA_TYPE";
        }
        ProblemDetail body = ProblemDetail.forStatus(status);
        body.setDetail(switch (code) {
            case "INVALID_QUOTE_SEARCH_REQUEST" -> "검색 조건과 Idempotency-Key를 확인해 주세요.";
            case "QUOTE_SEARCH_IN_PROGRESS" -> "이미 진행 중인 검색이 있습니다. 결과를 조회해 주세요.";
            case "IDEMPOTENCY_KEY_REUSED" -> "같은 요청 키에 다른 검색 조건을 사용할 수 없습니다.";
            case "CASE_CLOSED" -> "종료된 사건에서는 새 검색을 시작할 수 없습니다.";
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
