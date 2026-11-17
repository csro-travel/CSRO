package com.csro.backend.booking;

import java.net.URI;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * API-001 고객 예약 상세 조회 — {@code GET /api/v1/bookings/{bookingId}}.
 * <p>역할: 해당 Controller의 400·401·403·404·500을 Problem Details로 변환한다.
 * 400 매핑은 후속 ID 검증을 위한 준비이며, ID 형식 검증 자체는 아직 구현하지 않았다.
 * 조회 핵심의 오류 처리만 담당하며, 미연결인 공통 인증 필터의 오류 처리는 범위 밖이다.
 *
 * @see <a href="https://app.notion.com/p/1f32f2597c578386a3c181f38463f607">API-001 명세</a>
 */
@RestControllerAdvice(assignableTypes = BookingController.class)
public class BookingExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(BookingExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleStatus(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        String code = switch (status) {
            case BAD_REQUEST -> "INVALID_BOOKING_ID";
            case UNAUTHORIZED -> "UNAUTHENTICATED";
            case FORBIDDEN -> "FORBIDDEN_ROLE";
            case NOT_FOUND -> "BOOKING_NOT_FOUND";
            default -> "INTERNAL_ERROR";
        };
        if ("INTERNAL_ERROR".equals(code)) {
            return handleUnexpected(exception);
        }
        return problem(status, code, exception.getReason(), UUID.randomUUID().toString());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception) {
        String traceId = UUID.randomUUID().toString();
        log.error("Booking API failed; traceId={}", traceId, exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "요청을 처리하는 중 오류가 발생했습니다.", traceId);
    }

    private ResponseEntity<ProblemDetail> problem(
            HttpStatus status, String code, String detail, String traceId
    ) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(URI.create("about:blank"));
        body.setTitle(status.getReasonPhrase());
        body.setProperty("code", code);
        body.setProperty("traceId", traceId);

        ResponseEntity.BodyBuilder response = ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store");
        if (status == HttpStatus.UNAUTHORIZED) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return response.body(body);
    }
}
