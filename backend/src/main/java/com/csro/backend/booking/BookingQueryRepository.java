package com.csro.backend.booking;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * API-001 고객 예약 상세 조회 — {@code GET /api/v1/bookings/{bookingId}}.
 * <p>역할: 고객 소유 예약·호텔·활성 사건을 읽기 전용 SQL로 조회한다.
 * 현재는 조회 핵심만 구현했으며, 공통 인증 연결 후 검증된 subject_key를 전달받는다.
 *
 * @see <a href="https://app.notion.com/p/1f32f2597c578386a3c181f38463f607">API-001 명세</a>
 */
@Repository
public class BookingQueryRepository {

    private final JdbcClient jdbcClient;

    public BookingQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(readOnly = true)
    public Optional<BookingDetailResponse> findOwnedBooking(
            String bookingCode,
            String customerSubjectKey
    ) {
        // Obtain customerSubjectKey from verified authentication in the caller.
        return jdbcClient.sql("""
                SELECT b.booking_code,
                       h.hotel_code,
                       h.name AS hotel_name,
                       b.check_in_date,
                       b.check_out_date,
                       b.check_out_date - b.check_in_date AS nights,
                       b.adults,
                       b.supplier_status,
                       b.original_total_amount,
                       b.currency,
                       c.case_code AS active_case_code
                FROM bookings b
                JOIN users u ON u.id = b.customer_id
                JOIN hotels h ON h.id = b.hotel_id
                LEFT JOIN cases c
                       ON c.booking_id = b.id
                      AND c.status IN ('OPEN', 'IN_PROGRESS', 'WAITING')
                WHERE b.booking_code = :bookingCode
                  AND u.subject_key = :customerSubjectKey
                  AND u.role = 'CUSTOMER'
                  AND u.is_active = true
                """)
                .param("bookingCode", bookingCode)
                .param("customerSubjectKey", customerSubjectKey)
                .query((rs, rowNum) -> {
                    String activeCaseId = rs.getString("active_case_code");

                    return new BookingDetailResponse(
                            rs.getString("booking_code"),
                            new BookingDetailResponse.HotelInfo(
                                    rs.getString("hotel_code"),
                                    rs.getString("hotel_name")
                            ),
                            new BookingDetailResponse.StayInfo(
                                    rs.getObject("check_in_date", LocalDate.class),
                                    rs.getObject("check_out_date", LocalDate.class),
                                    rs.getInt("nights"),
                                    rs.getInt("adults")
                            ),
                            rs.getString("supplier_status"),
                            new BookingDetailResponse.OriginalPrice(
                                    rs.getLong("original_total_amount"),
                                    rs.getString("currency")
                            ),
                            new BookingDetailResponse.SupportInfo(
                                    activeCaseId,
                                    activeCaseId != null
                            )
                    );
                })
                .optional();
    }
}
