package com.csro.backend.replacements;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.csro.backend.quotes.QuoteSearchRuntime;
import java.security.Principal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(controllers = ReplacementController.class, properties = {
        "spring.config.location=optional:classpath:/__replacement_no_env__.properties"
})
class ReplacementControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ReplacementService service;
    @MockitoBean QuoteSearchRuntime runtime;
    private static final Principal COUNSELOR = () -> "demo-counselor-001";
    private static final Principal CUSTOMER = () -> "demo-customer-001";
    private static final String ID = "BRQ-" + "a".repeat(64);
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-09T10:20:00+09:00");
    private static final OffsetDateTime ACCEPTED = OffsetDateTime.parse("2026-11-24T12:00:00Z");

    @BeforeEach
    void serverClock() {
        when(runtime.effectiveAt()).thenReturn(AT);
        when(runtime.syntheticDemo()).thenReturn(true);
    }

    @Test
    void acceptsOnlyTheSelectionAndReturnsADurableReceiptWithResultLocation() throws Exception {
        when(service.request(COUNSELOR, "C-001", "SEL-1", "request-1", AT, true))
                .thenReturn(new ReplacementService.Acceptance("C-001", "SEL-1", ID, ACCEPTED));
        mvc.perform(post("/api/v1/cases/C-001/replacement-bookings").principal(COUNSELOR)
                        .header("Idempotency-Key", "request-1").contentType("application/json")
                        .content("{\"selectionId\":\"SEL-1\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.replacementBookingId").value(ID))
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(header().string("Location", "/api/v1/replacement-bookings/" + ID))
                .andExpect(header().string("Cache-Control", "private, no-store"));
        verify(service).request(COUNSELOR, "C-001", "SEL-1", "request-1", AT, true);
        verifyNoMoreInteractions(service);
    }

    @Test
    void malformedOrUntrustedFactsAndMissingKeysNeverReachTheCommand() throws Exception {
        for (String body : new String[]{"{", "null", "{\"selectionId\":1}",
                "{\"selectionId\":\"SEL-1\",\"expectedTotalAmount\":1}",
                "{\"selectionId\":\"SEL-1\",\"customerId\":\"other\"}"}) {
            mvc.perform(post("/api/v1/cases/C-001/replacement-bookings").principal(COUNSELOR)
                            .header("Idempotency-Key", "request-1").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REPLACEMENT_REQUEST"));
        }
        mvc.perform(post("/api/v1/cases/C-001/replacement-bookings").principal(COUNSELOR)
                        .contentType("application/json").content("{\"selectionId\":\"SEL-1\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void resultsDelegateOnlyToTheReadServiceUsingEstablishedPrincipal() throws Exception {
        when(service.view(CUSTOMER, ID)).thenReturn("{\"replacementBookingId\":\"" + ID + "\",\"status\":\"RESULT_UNKNOWN\"}");
        mvc.perform(get("/api/v1/replacement-bookings/{id}", ID).principal(CUSTOMER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESULT_UNKNOWN"))
                .andExpect(header().string("Cache-Control", "private, no-store"));
        verify(service).view(CUSTOMER, ID);
        verifyNoMoreInteractions(service);
    }

    @Test
    void forgedIdentityIsNotAuthenticationAndUnexpectedDetailsAreNotExposed() throws Exception {
        when(service.view(null, ID)).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/replacement-bookings/{id}", ID).header("X-Demo-Subject", CUSTOMER.getName()))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
        when(service.request(eq(COUNSELOR), anyString(), anyString(), anyString(), any(), anyBoolean()))
                .thenThrow(new IllegalStateException("private upstream and database details"));
        mvc.perform(post("/api/v1/cases/C-001/replacement-bookings").principal(COUNSELOR)
                        .header("Idempotency-Key", "request-1").contentType("application/json")
                        .content("{\"selectionId\":\"SEL-1\"}"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private upstream"))));
    }
}
