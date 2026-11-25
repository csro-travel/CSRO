package com.csro.backend.incidents;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(controllers = IncidentReportController.class, properties = {
        "spring.config.location=optional:classpath:/__incidents_no_env__.properties"
})
class IncidentReportControllerTest {
    private static final Principal CUSTOMER = () -> "demo-customer-001";
    private static final Principal COUNSELOR = () -> "demo-counselor-001";
    private static final String PATH = "/api/v1/bookings/YB-001/incident-reports";
    private static final String VALID = "{\"issueType\":\"CHECKIN_FAILED\",\"atHotel\":true,\"message\":\"확인 부탁드립니다\"}";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired MockMvc mvc;
    @MockitoBean IncidentReportService incidents;

    @Test
    void receiptUsesAuthenticatedCustomerAndNormalizedMessageWithTrueOrFalseAndUnicodeLimit() throws Exception {
        var receipt = receipt();
        when(incidents.submit(CUSTOMER, "YB-001", "report-1", "CHECKIN_FAILED", false, "입실 확인\n부탁드립니다"))
                .thenReturn(receipt);
        mvc.perform(post(PATH).principal(CUSTOMER).header("Idempotency-Key", "report-1")
                        .contentType("application/json;charset=UTF-8").content(JSON.writeValueAsString(Map.of(
                                "issueType", "CHECKIN_FAILED", "atHotel", false, "message", "  입실 확인\n부탁드립니다\n"))))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.reportId").value("REPORT-1"))
                .andExpect(jsonPath("$.bookingId").value("YB-001"))
                .andExpect(jsonPath("$.chatMessageId").value("MSG-1"))
                .andExpect(jsonPath("$.case.caseId").value("C-001"))
                .andExpect(jsonPath("$.case.created").value(true))
                .andExpect(jsonPath("$.case.status").value("OPEN"))
                .andExpect(jsonPath("$.case.priority").value("URGENT"))
                .andExpect(jsonPath("$.verificationStatus").value("PENDING"))
                .andExpect(jsonPath("$.receivedAt").exists())
                .andExpect(jsonPath("$.riskScore").doesNotExist());
        String thousandCodePoints = "😀".repeat(1000);
        when(incidents.submit(CUSTOMER, "YB-001", "report-2", "CHECKIN_FAILED", true, thousandCodePoints))
                .thenReturn(receipt);
        mvc.perform(post(PATH).principal(CUSTOMER).header("Idempotency-Key", "report-2")
                        .contentType("application/json").content(JSON.writeValueAsString(Map.of(
                                "issueType", "CHECKIN_FAILED", "atHotel", true, "message", thousandCodePoints))))
                .andExpect(status().isCreated());
        verify(incidents).submit(CUSTOMER, "YB-001", "report-1", "CHECKIN_FAILED", false, "입실 확인\n부탁드립니다");
        verify(incidents).submit(CUSTOMER, "YB-001", "report-2", "CHECKIN_FAILED", true, thousandCodePoints);
    }

    @Test
    void unsupportedMediaTypesUseTheSameSafeProblemContract() throws Exception {
        for (String contentType : new String[]{"text/plain", "application/problem+json", "application/octet-stream"}) {
            mvc.perform(post(PATH).principal(CUSTOMER).header("Idempotency-Key", "report-1")
                            .contentType(contentType).content(VALID))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                    .andExpect(jsonPath("$.traceId").isNotEmpty());
        }
        verifyNoInteractions(incidents);
    }

    @Test
    void malformedDuplicateTrailingCoercedOrUntrustedFieldsCannotReachTheService() throws Exception {
        for (String body : new String[]{"{", "null", "[]", "{}", VALID + " true", VALID + "{}",
                "{\"issueType\":\"CHECKIN_FAILED\",\"issueType\":\"CHECKIN_FAILED\",\"atHotel\":true,\"message\":\"확인\"}",
                "{\"issueType\":\"OTHER\",\"atHotel\":true,\"message\":\"확인\"}",
                "{\"issueType\":\"CHECKIN_FAILED\",\"atHotel\":\"false\",\"message\":\"확인\"}",
                "{\"issueType\":\"CHECKIN_FAILED\",\"atHotel\":0,\"message\":\"확인\"}",
                "{\"issueType\":\"CHECKIN_FAILED\",\"atHotel\":null,\"message\":\"확인\"}",
                "{\"issueType\":\"CHECKIN_FAILED\",\"atHotel\":true,\"message\":1}",
                "{\"issueType\":\"CHECKIN_FAILED\",\"atHotel\":true,\"message\":\"확인\",\"customerId\":\"forged\"}",
                "{\"issueType\":\"CHECKIN_FAILED\",\"atHotel\":true,\"message\":\"  \\n  \"}",
                "{\"issueType\":\"CHECKIN_FAILED\",\"atHotel\":true,\"message\":\"bad\\u0000text\"}",
                "{\"issueType\":\"CHECKIN_FAILED\",\"atHotel\":true,\"message\":\"\\ud800\"}",
                JSON.writeValueAsString(Map.of("issueType", "CHECKIN_FAILED", "atHotel", true, "message", "😀".repeat(1001)))}) {
            mvc.perform(post(PATH).principal(CUSTOMER).header("Idempotency-Key", "report-1")
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.code").value("INVALID_INCIDENT_REPORT"));
        }
        for (String key : new String[]{"", "bad key", "x".repeat(101)}) {
            mvc.perform(post(PATH).principal(CUSTOMER).header("Idempotency-Key", key)
                            .contentType("application/json").content(VALID))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INCIDENT_REPORT"));
        }
        mvc.perform(post(PATH).principal(CUSTOMER).contentType("application/json").content(VALID))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INCIDENT_REPORT"));
        verifyNoInteractions(incidents);
    }

    @Test
    void ownershipRoleAuthenticationAndIdempotencyFailuresHaveSafeStableProblems() throws Exception {
        when(incidents.submit(isNull(), eq("YB-001"), eq("report-1"), eq("CHECKIN_FAILED"), eq(true), anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED"));
        mvc.perform(post(PATH).header("X-Demo-Subject", CUSTOMER.getName()).header("Idempotency-Key", "report-1")
                        .contentType("application/json").content(VALID))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
        when(incidents.submit(eq(COUNSELOR), anyString(), anyString(), anyString(), anyBoolean(), anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE"));
        mvc.perform(post(PATH).principal(COUNSELOR).header("Idempotency-Key", "report-1")
                        .contentType("application/json").content(VALID))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        when(incidents.submit(eq(CUSTOMER), anyString(), eq("not-owned"), anyString(), anyBoolean(), anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND"));
        mvc.perform(post(PATH).principal(CUSTOMER).header("Idempotency-Key", "not-owned")
                        .contentType("application/json").content(VALID))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"));
        when(incidents.submit(eq(CUSTOMER), anyString(), eq("conflict"), anyString(), anyBoolean(), anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(post(PATH).principal(CUSTOMER).header("Idempotency-Key", "conflict")
                        .contentType("application/json").content(VALID))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        when(incidents.submit(eq(CUSTOMER), anyString(), eq("failed"), anyString(), anyBoolean(), anyString()))
                .thenThrow(new IllegalStateException("private database token=secret"));
        mvc.perform(post(PATH).principal(CUSTOMER).header("Idempotency-Key", "failed")
                        .contentType("application/json").content(VALID))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(content().string(not(containsString("private database"))))
                .andExpect(content().string(not(containsString("secret"))));
    }

    private static IncidentReportService.Receipt receipt() {
        return new IncidentReportService.Receipt("REPORT-1", "YB-001", "MSG-1",
                new IncidentReportService.CaseReceipt("C-001", true, "OPEN", "URGENT"), "PENDING",
                OffsetDateTime.parse("2026-11-25T10:20:00+09:00"));
    }
}
