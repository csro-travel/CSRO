package com.csro.backend.offers;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.csro.backend.quotes.QuoteSearchRuntime;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(controllers = OfferController.class, properties = {
        "spring.config.location=optional:classpath:/__offers_no_env__.properties"
})
class OfferControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean OfferSelectionService offers;
    @MockitoBean QuoteSearchRuntime runtime;
    private static final Principal COUNSELOR = () -> "demo-counselor-001";
    private static final Principal CUSTOMER = () -> "demo-customer-001";
    private static final OffsetDateTime EFFECTIVE = OffsetDateTime.parse("2026-11-09T10:20:00+09:00");
    private static final OffsetDateTime AUDIT = OffsetDateTime.parse("2026-11-24T12:00:00Z");

    @BeforeEach
    void useOnlyServerConfiguredValidityTime() {
        when(runtime.effectiveAt()).thenReturn(EFFECTIVE);
        when(runtime.syntheticDemo()).thenReturn(true);
    }

    @Test
    void commandsUseAuthenticatedPrincipalAndServerTimeAndReturnImmutableReceipts() throws Exception {
        when(offers.send(COUNSELOR, "C-001", List.of("QA", "QB", "QC"), "send-1", EFFECTIVE, true))
                .thenReturn(new OfferSelectionService.SendResult("C-001", "OFFER-1", "MSG-1", AUDIT));
        mvc.perform(post("/api/v1/cases/C-001/offers").principal(COUNSELOR)
                        .header("Idempotency-Key", "send-1").contentType("application/json")
                        .content("{\"quoteIds\":[\"QA\",\"QB\",\"QC\"]}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.offerId").value("OFFER-1"))
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(header().string("Cache-Control", "private, no-store"));
        when(offers.select(CUSTOMER, "C-001", "OFFER-1", "QB", "select-1", EFFECTIVE, true))
                .thenReturn(new OfferSelectionService.SelectionResult("C-001", "SEL-1", "OFFER-1", "QB", AUDIT));
        mvc.perform(put("/api/v1/cases/C-001/selection").principal(CUSTOMER)
                        .header("Idempotency-Key", "select-1").contentType("application/json")
                        .content("{\"offerId\":\"OFFER-1\",\"quoteId\":\"QB\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.selectionId").value("SEL-1"))
                .andExpect(jsonPath("$.bookingId").doesNotExist());
        verify(offers).send(COUNSELOR, "C-001", List.of("QA", "QB", "QC"), "send-1", EFFECTIVE, true);
        verify(offers).select(CUSTOMER, "C-001", "OFFER-1", "QB", "select-1", EFFECTIVE, true);
    }

    @Test
    void untrustedFactsCoercionsMalformedBodiesAndMissingKeysCannotReachWrites() throws Exception {
        for (String body : new String[]{"{", "null", "{\"quoteIds\":[\"QA\",\"QB\",1]}",
                "{\"quoteIds\":[\"QA\",\"QB\",\"QC\"],\"asOf\":\"2026-11-09\"}"}) {
            mvc.perform(post("/api/v1/cases/C-001/offers").principal(COUNSELOR)
                            .header("Idempotency-Key", "send-1").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_OFFER_REQUEST"));
        }
        mvc.perform(put("/api/v1/cases/C-001/selection").principal(CUSTOMER).contentType("application/json")
                        .content("{\"offerId\":\"OFFER-1\",\"quoteId\":\"QB\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SELECTION_REQUEST"));
        mvc.perform(put("/api/v1/cases/C-001/selection").principal(CUSTOMER)
                        .header("Idempotency-Key", "select-1").contentType("application/json")
                        .content("{\"offerId\":\"OFFER-1\",\"quoteId\":\"QB\",\"customerId\":\"other\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(offers);
    }

    @Test
    void forgedSubjectDoesNotAuthenticateAndInternalFailuresDoNotLeakDetails() throws Exception {
        when(offers.select(isNull(), eq("C-001"), eq("OFFER-1"), eq("QB"), eq("select-1"), eq(EFFECTIVE), eq(true)))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED"));
        mvc.perform(put("/api/v1/cases/C-001/selection").header("X-Demo-Subject", CUSTOMER.getName())
                        .header("Idempotency-Key", "select-1").contentType("application/json")
                        .content("{\"offerId\":\"OFFER-1\",\"quoteId\":\"QB\"}"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
        when(offers.send(eq(COUNSELOR), anyString(), anyList(), anyString(), any(), anyBoolean()))
                .thenThrow(new IllegalStateException("private database details"));
        mvc.perform(post("/api/v1/cases/C-001/offers").principal(COUNSELOR)
                        .header("Idempotency-Key", "send-1").contentType("application/json")
                        .content("{\"quoteIds\":[\"QA\",\"QB\",\"QC\"]}"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private database"))));
    }
}
