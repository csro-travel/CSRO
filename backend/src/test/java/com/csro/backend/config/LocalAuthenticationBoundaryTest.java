package com.csro.backend.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.csro.backend.booking.BookingController;
import com.csro.backend.booking.BookingDetailResponse;
import com.csro.backend.booking.BookingService;
import java.security.Principal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/** Normal local requests keep the same authentication boundary as other profiles. */
@WebMvcTest(controllers = BookingController.class, properties = {
        "spring.config.location=optional:classpath:/__auth_boundary_no_env__.properties"
})
@ActiveProfiles("local")
class LocalAuthenticationBoundaryTest {
    private static final String BOOKING = "YB-001";

    @Autowired MockMvc mvc;
    @MockitoBean BookingService service;

    @BeforeEach
    void requireAnEstablishedPrincipal() {
        // Match BookingService's existing missing-principal boundary without DB access.
        when(service.getBooking(eq(BOOKING), any())).thenAnswer(invocation -> {
            Principal principal = invocation.getArgument(1);
            if (principal == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
            }
            return new BookingDetailResponse(BOOKING, null, null, "CONFIRMED", null, null);
        });
    }

    @Test
    void forgedSubjectHeaderDoesNotAuthenticateALocalRequest() throws Exception {
        mvc.perform(get("/api/v1/bookings/{bookingId}", BOOKING)
                        .header("X-Demo-Subject", "demo-counselor-001"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        verify(service).getBooking(BOOKING, null);
    }

    @Test
    void forgedSubjectHeaderDoesNotOverrideAnExistingPrincipal() throws Exception {
        Principal established = () -> "authenticated-customer";

        mvc.perform(get("/api/v1/bookings/{bookingId}", BOOKING)
                        .principal(established)
                        .header("X-Demo-Subject", "demo-counselor-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(BOOKING));

        verify(service).getBooking(BOOKING, established);
    }
}
