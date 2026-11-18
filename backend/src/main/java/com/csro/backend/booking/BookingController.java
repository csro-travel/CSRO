package com.csro.backend.booking;

import java.security.Principal;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API-001 고객 예약 상세 조회 — {@code GET /api/v1/bookings/{bookingId}}.
 * <p>역할: 고객용 조회 응답과 캐시 헤더를 제공하는 HTTP 진입점.
 * 현재 범위는 조회 핵심이며 공통 Bearer 인증 연결은 미완료다.
 *
 * @see <a href="https://app.notion.com/p/1f32f2597c578386a3c181f38463f607">API-001 명세</a>
 */
@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    /**
     * API-001 엔드포인트: {@code GET /api/v1/bookings/{bookingId}}.
     * 인증 계층에서 확립한 Principal을 전달하며, 미연결 상태의 일반 요청은 401이다.
     */
    @GetMapping(value = "/{bookingId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BookingDetailResponse> getBooking(
            @PathVariable("bookingId") String bookingId,
            Principal principal
    ) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(bookingService.getBooking(bookingId, principal));
    }
}
