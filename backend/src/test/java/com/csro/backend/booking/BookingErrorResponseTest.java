package com.csro.backend.booking;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

/**
 * API-001 고객 예약 상세 조회 — {@code GET /api/v1/bookings/{bookingId}}.
 * <p>역할: 조회 핵심의 400·500 오류 계약과 내부 정보 비노출을 DB 없이 검증한다.
 * 공통 인증은 미연결이며, 이 테스트도 인증 공급자나 Bearer 검증을 실행하지 않는다.
 *
 * @see <a href="https://app.notion.com/p/1f32f2597c578386a3c181f38463f607">API-001 명세</a>
 */
@DisplayName("API-001 고객 예약 상세 조회 | 400·500 오류 응답 테스트")
class BookingErrorResponseTest {

    @Test
    @DisplayName("API-001 | 400 오류는 INVALID_BOOKING_ID로 유지하며 형식 정책은 별도")
    void badRequestKeepsItsStatusAndBookingErrorCode() throws Exception {
        BookingService service = mock(BookingService.class);
        // Only the exception mapping is tested. No booking-ID regex is assumed.
        when(service.getBooking("TEST-INVALID", null)).thenThrow(new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "예약 식별자 형식이 올바르지 않습니다."));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new BookingController(service))
                .setControllerAdvice(new BookingExceptionHandler())
                .build();

        mockMvc.perform(get("/api/v1/bookings/TEST-INVALID")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.code").value("INVALID_BOOKING_ID"))
                .andExpect(jsonPath("$.detail").value("예약 식별자 형식이 올바르지 않습니다."))
                .andExpect(jsonPath("$.traceId", not(emptyOrNullString())));
    }

    @Test
    @DisplayName("API-001 | 예기치 못한 오류는 안전한 500 Problem Details로 반환")
    void unexpectedFailureReturnsSafeProblemDetails() throws Exception {
        BookingService service = mock(BookingService.class);
        when(service.getBooking("YB-001", null)).thenThrow(new IllegalStateException(
                "INTERNAL_SQL_MARKER: SELECT password_hash FROM users; "
                        + "password=TEST_ONLY_SECRET_MARKER"));

        // Standalone MVC uses the converters available on the classpath;
        // this also checks ProblemDetail serialization with the Boot 4 dependencies.
        // No Spring context or DB is started.
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new BookingController(service))
                .setControllerAdvice(new BookingExceptionHandler())
                .build();

        mockMvc.perform(get("/api/v1/bookings/YB-001")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.traceId", not(emptyOrNullString())))
                .andExpect(jsonPath("$.detail").value("요청을 처리하는 중 오류가 발생했습니다."))
                .andExpect(content().string(not(containsString("INTERNAL_SQL_MARKER"))))
                .andExpect(content().string(not(containsString("password_hash"))))
                .andExpect(content().string(not(containsString("TEST_ONLY_SECRET_MARKER"))));
    }
}
