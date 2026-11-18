package com.csro.backend.booking;

import java.time.LocalDate;

/**
 * API-001 고객 예약 상세 조회 — {@code GET /api/v1/bookings/{bookingId}}.
 * <p>역할: 예약·호텔·숙박 기간·기존 금액·활성 상담 연결의 성공 JSON 계약.
 * 조회 핵심만 포함하며, 공통 인증 연결은 미완료이고 내부 위험 점수·원문은 노출하지 않는다.
 *
 * @see <a href="https://app.notion.com/p/1f32f2597c578386a3c181f38463f607">API-001 명세</a>
 */
public record BookingDetailResponse(
        String bookingId,
        HotelInfo hotel,
        StayInfo stay,
        String bookingStatus,
        OriginalPrice originalPrice,
        SupportInfo support
) {
    public record HotelInfo(String hotelId, String name) {}
    public record StayInfo(LocalDate checkInDate, LocalDate checkOutDate, int nights, int adults) {}
    public record OriginalPrice(long amount, String currency) {}
    public record SupportInfo(String activeCaseId, boolean needsAttention) {}
}
