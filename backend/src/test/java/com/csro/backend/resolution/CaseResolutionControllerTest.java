package com.csro.backend.resolution;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.csro.backend.quotes.QuoteSearchRuntime;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/** Critical HTTP command boundaries; business invariants are checked against the isolated DB separately. */
@WebMvcTest(controllers = CaseResolutionController.class, properties = {
        "spring.config.location=optional:classpath:/__resolution_no_env__.properties"
})
class CaseResolutionControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean CaseResolutionService resolutions;
    @MockitoBean QuoteSearchRuntime runtime;
    private static final Principal CUSTOMER = () -> "demo-customer-001";
    private static final Principal COUNSELOR = () -> "demo-counselor-001";
    private static final String BRQ = "BRQ-" + "a".repeat(64);
    private static final String CHECKIN = "/api/v1/replacement-bookings/" + BRQ + "/checkin-confirmations";
    private static final String TASK = "/api/v1/cases/C-001/tasks/TASK-1";
    private static final String CLOSE = "/api/v1/cases/C-001/closures";
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-10T15:00:00+09:00");

    @BeforeEach
    void serverClock() {
        when(runtime.effectiveAt()).thenReturn(AT);
        when(runtime.syntheticDemo()).thenReturn(true);
    }

    @Test
    void customerCheckinUsesEstablishedPrincipalAndServerTimeWithAnImmutable201Receipt() throws Exception {
        when(resolutions.checkin(CUSTOMER, BRQ, "checkin-1", AT, true)).thenReturn("""
                {"checkinConfirmationId":"CHECKIN-example","caseId":"C-001","replacementBookingId":"%s",
                 "status":"CUSTOMER_CONFIRMED","checkinAt":"2026-11-10T15:00:00+09:00","isSynthetic":true,"clockMode":"SYNTHETIC_DEMO"}
                """.formatted(BRQ));
        mvc.perform(post(CHECKIN).principal(CUSTOMER).header("Idempotency-Key", "checkin-1")
                        .contentType("application/json").content("{\"checkedIn\":true}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CUSTOMER_CONFIRMED"))
                .andExpect(jsonPath("$.checkinConfirmationId").value("CHECKIN-example"))
                .andExpect(header().string("Cache-Control", "private, no-store"));
        verify(resolutions).checkin(CUSTOMER, BRQ, "checkin-1", AT, true);
        verifyNoMoreInteractions(resolutions);
    }

    @Test
    void counselorCommandsCarryTheExactVersionAndTrimmedNotesWithoutAClientClock() throws Exception {
        var completion = new CaseResolutionService.TaskCompletion("DONE", "수동 조치 완료 확인", 3L, "COMPLETED");
        when(resolutions.completeTask(COUNSELOR, "C-001", "TASK-1", "task-1", completion))
                .thenReturn("{\"caseId\":\"C-001\",\"taskId\":\"TASK-1\",\"status\":\"DONE\",\"version\":4}");
        mvc.perform(patch(TASK).principal(COUNSELOR).header("Idempotency-Key", "task-1").contentType("application/json")
                        .content("{\"status\":\"DONE\",\"completionNote\":\"  수동 조치 완료 확인  \",\"expectedVersion\":3,\"followupOutcome\":\"COMPLETED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(4))
                .andExpect(header().string("Cache-Control", "private, no-store"));
        var closure = new CaseResolutionService.Closure("모든 보장·후속 확인 완료", 4L);
        when(resolutions.close(COUNSELOR, "C-001", "close-1", closure, AT, true))
                .thenReturn("{\"closureId\":\"CLOSE-example\",\"caseId\":\"C-001\",\"status\":\"CLOSED\",\"version\":5}");
        mvc.perform(post(CLOSE).principal(COUNSELOR).header("Idempotency-Key", "close-1").contentType("application/json")
                        .content("{\"reason\":\" 모든 보장·후속 확인 완료 \",\"expectedVersion\":4}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CLOSED"));
        verify(resolutions).completeTask(COUNSELOR, "C-001", "TASK-1", "task-1", completion);
        verify(resolutions).close(COUNSELOR, "C-001", "close-1", closure, AT, true);
        verifyNoMoreInteractions(resolutions);
    }

    @Test
    void checkinRequiresTrueAndRejectsUntrustedFactsDuplicateKeysAndTrailingJsonBeforeWriting() throws Exception {
        for (String body : new String[]{"{", "null", "{\"checkedIn\":false}", "{\"checkedIn\":1}",
                "{\"checkedIn\":true,\"asOf\":\"2026-11-10\"}",
                "{\"checkedIn\":true,\"checkedIn\":false}", "{\"checkedIn\":true}{}"}) {
            mvc.perform(post(CHECKIN).principal(CUSTOMER).header("Idempotency-Key", "checkin-1")
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CHECKIN_REQUEST"));
        }
        verifyNoInteractions(resolutions);
    }

    @Test
    void taskAndClosureCannotCoerceVersionsCompletePendingFollowupOrAcceptHiddenState() throws Exception {
        for (String body : new String[]{
                "{\"status\":\"DONE\",\"completionNote\":\"확인\",\"expectedVersion\":true}",
                "{\"status\":\"DONE\",\"completionNote\":\"확인\",\"expectedVersion\":3.0}",
                "{\"status\":\"DONE\",\"completionNote\":\"확인\",\"expectedVersion\":3,\"followupOutcome\":\"REQUESTED\"}",
                "{\"status\":\"DONE\",\"completionNote\":\"확인\",\"expectedVersion\":3,\"isRequired\":false}",
                "{\"status\":\"DONE\",\"completionNote\":\"확인\",\"expectedVersion\":3,\"expectedVersion\":4}"}) {
            mvc.perform(patch(TASK).principal(COUNSELOR).header("Idempotency-Key", "task-1")
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TASK_COMPLETION"));
        }
        for (String body : new String[]{"{\"reason\":\"완료\",\"expectedVersion\":\"4\"}",
                "{\"reason\":\"완료\",\"expectedVersion\":-1}",
                "{\"reason\":\"완료\",\"expectedVersion\":4,\"status\":\"CLOSED\"}",
                "{\"reason\":\"완료\",\"expectedVersion\":4}{}"}) {
            mvc.perform(post(CLOSE).principal(COUNSELOR).header("Idempotency-Key", "close-1")
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CLOSURE_REQUEST"));
        }
        verifyNoInteractions(resolutions);
    }

    @Test
    void keysAndJsonMediaTypeAreRequiredWithStableProblemsAndNoWrite() throws Exception {
        mvc.perform(post(CHECKIN).principal(CUSTOMER).contentType("application/json").content("{\"checkedIn\":true}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CHECKIN_REQUEST"));
        mvc.perform(patch(TASK).principal(COUNSELOR).header("Idempotency-Key", "unsafe key")
                        .contentType("application/json").content("{\"status\":\"DONE\",\"completionNote\":\"완료\",\"expectedVersion\":3}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TASK_COMPLETION"));
        mvc.perform(post(CLOSE).principal(COUNSELOR).header("Idempotency-Key", "close-1").contentType("text/plain")
                        .content("{\"reason\":\"완료\",\"expectedVersion\":4}"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(header().string("Cache-Control", "private, no-store"));
        verifyNoInteractions(resolutions);
    }

    @Test
    void typedConflictsExposeOnlyThePublicBlockersVersionOrExistingConfirmation() throws Exception {
        when(resolutions.close(eq(COUNSELOR), eq("C-001"), eq("close-1"), any(), eq(AT), eq(true)))
                .thenThrow(new CaseResolutionService.CaseNotResolved(
                        List.of(Map.of("code", "REQUIRED_TASKS_INCOMPLETE", "taskIds", List.of("TASK-1"))),
                        List.of("REQUIRED_TASKS_INCOMPLETE")));
        mvc.perform(post(CLOSE).principal(COUNSELOR).header("Idempotency-Key", "close-1").contentType("application/json")
                        .content("{\"reason\":\"완료\",\"expectedVersion\":4}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASE_NOT_RESOLVED"))
                .andExpect(jsonPath("$.blockers[0].taskIds[0]").value("TASK-1"))
                .andExpect(jsonPath("$.missingConditions[0]").value("REQUIRED_TASKS_INCOMPLETE"));
        when(resolutions.completeTask(eq(COUNSELOR), eq("C-001"), eq("TASK-1"), eq("task-1"), any()))
                .thenThrow(new CaseResolutionService.CaseVersionConflict(9));
        mvc.perform(patch(TASK).principal(COUNSELOR).header("Idempotency-Key", "task-1").contentType("application/json")
                        .content("{\"status\":\"DONE\",\"completionNote\":\"완료\",\"expectedVersion\":3}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASE_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.currentVersion").value(9));
        when(resolutions.checkin(CUSTOMER, BRQ, "checkin-1", AT, true))
                .thenThrow(new CaseResolutionService.CheckinAlreadyConfirmed("CHECKIN-existing"));
        mvc.perform(post(CHECKIN).principal(CUSTOMER).header("Idempotency-Key", "checkin-1").contentType("application/json")
                        .content("{\"checkedIn\":true}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.checkinConfirmationId").value("CHECKIN-existing"));
    }

    @Test
    void forgedSubjectCannotAuthenticateAndUnexpectedFailuresDoNotExposePrivateDetails() throws Exception {
        when(resolutions.checkin(null, BRQ, "checkin-1", AT, true))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED"));
        mvc.perform(post(CHECKIN).header("X-Demo-Subject", CUSTOMER.getName()).header("Idempotency-Key", "checkin-1")
                        .contentType("application/json").content("{\"checkedIn\":true}"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
        when(resolutions.close(eq(COUNSELOR), anyString(), anyString(), any(), any(), anyBoolean()))
                .thenThrow(new IllegalStateException("private database details"));
        mvc.perform(post(CLOSE).principal(COUNSELOR).header("Idempotency-Key", "close-1").contentType("application/json")
                        .content("{\"reason\":\"완료\",\"expectedVersion\":4}"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private database"))));
    }
}
