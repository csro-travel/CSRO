package com.csro.backend.quotes;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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

@WebMvcTest(controllers = QuoteController.class, properties = {
        "spring.config.location=optional:classpath:/__quotes_no_env__.properties"
})
class QuoteControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean QuoteSearchService searches;
    @MockitoBean QuoteSearchRuntime runtime;
    private static final Principal COUNSELOR = () -> "demo-counselor-001";
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-09T10:20:00+09:00");

    @BeforeEach
    void time() {
        when(runtime.effectiveAt()).thenReturn(AT);
        when(runtime.syntheticDemo()).thenReturn(true);
    }

    @Test
    void acceptanceIs202AndOnlyTheAuthenticatedPrincipalAndValidatedOptionsReachTheService() throws Exception {
        var options = new QuoteSearchService.SearchOptions(3000, null);
        when(searches.request(COUNSELOR, "C-029", options, "test-key", AT, true))
                .thenReturn(new QuoteSearchService.Acceptance("C-029", "QS-test", "PENDING"));
        mvc.perform(post("/api/v1/cases/C-029/quote-searches").principal(COUNSELOR)
                        .header("Idempotency-Key", "test-key").contentType("application/json").content("{}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.searchJobId").value("QS-test"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(header().string("Cache-Control", "private, no-store"));
        verify(searches).request(COUNSELOR, "C-029", options, "test-key", AT, true);
    }

    @Test
    void malformedOrCoercedInputsNeverEnqueueAJob() throws Exception {
        for (String body : new String[]{"{", "[]", "null", "{\"radiusMeters\":true}",
                "{\"radiusMeters\":3000.0}", "{\"radiusMeters\":20001}",
                "{\"maxAverageNightlyAmount\":null}", "{\"maxAverageNightlyAmount\":-1}",
                "{\"bookingId\":\"YB-OTHER\"}"}) {
            mvc.perform(post("/api/v1/cases/C-029/quote-searches").principal(COUNSELOR)
                            .header("Idempotency-Key", "test-key").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType("application/problem+json"))
                    .andExpect(jsonPath("$.code").value("INVALID_QUOTE_SEARCH_REQUEST"));
        }
        verifyNoInteractions(searches);
    }

    @Test
    void missingOrInvalidKeyNeverEnqueuesAJob() throws Exception {
        mvc.perform(post("/api/v1/cases/C-029/quote-searches").principal(COUNSELOR)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/cases/C-029/quote-searches").principal(COUNSELOR)
                        .header("Idempotency-Key", "unsafe key").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(searches);
    }

    @Test
    void readsCannotTriggerSearchesAndDoNotAcceptAForgedPrincipalHeader() throws Exception {
        when(searches.view(null, "C-029"))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/cases/C-029/quotes").header("X-Demo-Subject", COUNSELOR.getName()))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
        verify(searches).view(null, "C-029");
        verifyNoMoreInteractions(searches);
    }

    @Test
    void readOnlyRepresentationAndConflictsUseStableWireContracts() throws Exception {
        when(searches.view(COUNSELOR, "C-029"))
                .thenReturn("{\"caseId\":\"C-029\",\"searchJob\":null,\"groups\":[]}");
        mvc.perform(get("/api/v1/cases/C-029/quotes").principal(COUNSELOR))
                .andExpect(status().isOk()).andExpect(jsonPath("$.groups").isEmpty())
                .andExpect(header().string("Cache-Control", "private, no-store"));
        when(searches.request(eq(COUNSELOR), eq("C-029"), any(), eq("test-key"), eq(AT), eq(true)))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "QUOTE_SEARCH_IN_PROGRESS"));
        mvc.perform(post("/api/v1/cases/C-029/quote-searches").principal(COUNSELOR)
                        .header("Idempotency-Key", "test-key").contentType("application/json").content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUOTE_SEARCH_IN_PROGRESS"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }
}
