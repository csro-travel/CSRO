package com.csro.backend.incidents;

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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** API-002: customer-reported check-in failure; reporting alone does not verify a room failure. */
@RestController
@RequestMapping(value = "/api/v1/bookings/{bookingId}/incident-reports", produces = MediaType.APPLICATION_JSON_VALUE)
public class IncidentReportController {
    private static final Set<String> FIELDS = Set.of("issueType", "atHotel", "message");
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final IncidentReportService incidents;

    IncidentReportController(IncidentReportService incidents) {
        this.incidents = incidents;
    }

    @PostMapping
    public ResponseEntity<IncidentReportService.Receipt> submit(HttpServletRequest request,
            @PathVariable String bookingId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody byte[] bytes) {
        if (!jsonContentType(request.getContentType())) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE");
        }
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,100}")) throw invalid();
        JsonNode body;
        try {
            body = JSON.readTree(bytes);
        } catch (JacksonException | IllegalArgumentException failure) {
            throw invalid();
        }
        if (body == null || !body.isObject() || body.size() != FIELDS.size()) throw invalid();
        for (String field : body.propertyNames()) if (!FIELDS.contains(field)) throw invalid();
        JsonNode issue = body.get("issueType");
        JsonNode atHotel = body.get("atHotel");
        JsonNode message = body.get("message");
        if (!issue.isString() || !"CHECKIN_FAILED".equals(issue.stringValue())
                || !atHotel.isBoolean() || !message.isString()) throw invalid();
        String text = message.stringValue().strip();
        if (text.isEmpty() || text.indexOf(0) >= 0 || text.codePointCount(0, text.length()) > 1000
                || !validUnicode(text)) throw invalid();
        var receipt = incidents.submit(request.getUserPrincipal(), bookingId, key,
                issue.stringValue(), atHotel.booleanValue(), text);
        return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control", "private, no-store").body(receipt);
    }

    private static boolean jsonContentType(String value) {
        if (value == null) return false;
        try {
            var type = MediaType.parseMediaType(value);
            return "application".equalsIgnoreCase(type.getType()) && "json".equalsIgnoreCase(type.getSubtype());
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    private static boolean validUnicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (++index == value.length() || !Character.isLowSurrogate(value.charAt(index))) return false;
            } else if (Character.isLowSurrogate(character)) return false;
        }
        return true;
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INCIDENT_REPORT");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> error(Exception failure) {
        int status = 500;
        String code = "INTERNAL_ERROR";
        if (failure instanceof HttpMessageNotReadableException) {
            status = 400;
            code = "INVALID_INCIDENT_REPORT";
        } else if (failure instanceof HttpMediaTypeNotSupportedException) {
            status = 415;
            code = "UNSUPPORTED_MEDIA_TYPE";
        } else if (failure instanceof ResponseStatusException typed) {
            String reason = typed.getReason();
            int candidate = typed.getStatusCode().value();
            if (candidate == 400 && reason != null && Set.of("INVALID_INCIDENT_REPORT", "INVALID_IDEMPOTENCY_KEY").contains(reason)) {
                status = 400;
                code = "INVALID_INCIDENT_REPORT";
            } else if ((candidate == 401 && "UNAUTHENTICATED".equals(reason))
                    || (candidate == 403 && "FORBIDDEN_ROLE".equals(reason))
                    || (candidate == 404 && "BOOKING_NOT_FOUND".equals(reason))
                    || (candidate == 409 && "IDEMPOTENCY_KEY_REUSED".equals(reason))
                    || (candidate == 415 && "UNSUPPORTED_MEDIA_TYPE".equals(reason))) {
                status = candidate;
                code = reason;
            }
        }
        var body = ProblemDetail.forStatus(status);
        body.setDetail(switch (code) {
            case "INVALID_INCIDENT_REPORT" -> "신고 유형·현장 여부·메시지와 요청 키를 확인해 주세요.";
            case "IDEMPOTENCY_KEY_REUSED" -> "같은 요청 키에 다른 내용을 사용할 수 없습니다.";
            case "UNSUPPORTED_MEDIA_TYPE" -> "JSON 형식으로 신고를 보내 주세요.";
            default -> body.getTitle();
        });
        body.setProperty("code", code);
        body.setProperty("traceId", UUID.randomUUID().toString());
        var response = ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header("Cache-Control", "private, no-store");
        if (status == 401) response.header("WWW-Authenticate", "Bearer");
        return response.body(body);
    }
}
