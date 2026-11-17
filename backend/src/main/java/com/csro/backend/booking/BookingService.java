package com.csro.backend.booking;

import java.security.Principal;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * API-001 고객 예약 상세 조회 — {@code GET /api/v1/bookings/{bookingId}}.
 * <p>역할: Principal에 해당하는 계정의 활성 여부·고객 역할을 확인하고 소유 예약을 조회한다.
 * 조회·인가 핵심만 구현했으며, Bearer 검증과 Principal 생성은 미연결인 공통 인증 계층의 책임이다.
 *
 * @see <a href="https://app.notion.com/p/1f32f2597c578386a3c181f38463f607">API-001 명세</a>
 */
@Service
public class BookingService {

    private final BookingQueryRepository bookingQueryRepository;
    private final JdbcClient jdbcClient;

    public BookingService(BookingQueryRepository bookingQueryRepository, JdbcClient jdbcClient) {
        this.bookingQueryRepository = bookingQueryRepository;
        this.jdbcClient = jdbcClient;
    }

    @Transactional(readOnly = true)
    public BookingDetailResponse getBooking(String bookingCode, Principal principal) {
        // Principal must be established by the shared authentication layer.
        // Request headers, query parameters, and booking codes are not identities.
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw unauthenticated();
        }

        String subjectKey = principal.getName();
        Account account = jdbcClient.sql("""
                SELECT role, is_active
                FROM users
                WHERE subject_key = :subjectKey
                """)
                .param("subjectKey", subjectKey)
                .query((rs, rowNum) -> new Account(
                        rs.getString("role"), rs.getBoolean("is_active")))
                .optional()
                .orElseThrow(BookingService::unauthenticated);

        if (!account.active()) {
            throw unauthenticated();
        }
        if (!"CUSTOMER".equals(account.role())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "고객용 예약 조회 권한이 없습니다.");
        }

        return bookingQueryRepository.findOwnedBooking(bookingCode, subjectKey)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "조회할 수 있는 예약 정보가 없습니다."));
    }

    private static ResponseStatusException unauthenticated() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
    }

    private record Account(String role, boolean active) {}
}
