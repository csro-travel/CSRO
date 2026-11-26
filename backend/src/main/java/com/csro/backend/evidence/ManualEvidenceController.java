package com.csro.backend.evidence;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/** API-006 under the approved minimal contract: counselor records a direct hotel call as evidence. */
@RestController
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class ManualEvidenceController {
    private final ManualEvidenceService evidence;

    ManualEvidenceController(ManualEvidenceService evidence) {
        this.evidence = evidence;
    }

    @PostMapping(value = "/cases/{caseId}/evidence", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ManualEvidenceService.Recorded> record(HttpServletRequest request, @PathVariable String caseId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode body) {
        if (body == null || !body.isObject() || !Set.copyOf(body.propertyNames()).equals(Set.of("callNote", "nights"))
                || !body.get("callNote").isString() || !body.get("nights").isArray()) throw invalid();
        List<ManualEvidenceService.Claim> nights = new ArrayList<>();
        for (JsonNode night : body.get("nights")) {
            if (!night.isObject() || !Set.copyOf(night.propertyNames()).equals(Set.of("stayDate", "reportedProvisionStatus"))
                    || !night.get("stayDate").isString() || !night.get("reportedProvisionStatus").isString()) throw invalid();
            try {
                nights.add(new ManualEvidenceService.Claim(LocalDate.parse(night.get("stayDate").stringValue()),
                        night.get("reportedProvisionStatus").stringValue()));
            } catch (DateTimeParseException malformed) { throw invalid(); }
        }
        var recorded = evidence.record(request.getUserPrincipal(), caseId, key, body.get("callNote").stringValue(), nights);
        return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control", "private, no-store").body(recorded);
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_EVIDENCE_REQUEST");
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
            code = "INVALID_EVIDENCE_REQUEST";
        } else if (failure instanceof HttpMediaTypeNotSupportedException) {
            status = 415;
            code = "UNSUPPORTED_MEDIA_TYPE";
        }
        ProblemDetail body = ProblemDetail.forStatus(status);
        body.setDetail(switch (code) {
            case "INVALID_EVIDENCE_REQUEST" -> "통화 내용(1~2000자), 숙박일과 확인 결과, Idempotency-Key를 확인해 주세요.";
            case "STAY_DATE_OUT_OF_RANGE" -> "원예약 숙박일만 기록할 수 있습니다.";
            case "CASE_CLOSED" -> "이미 종료된 사건입니다.";
            case "IDEMPOTENCY_KEY_REUSED" -> "같은 요청 키를 다른 기록에 사용할 수 없습니다.";
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
