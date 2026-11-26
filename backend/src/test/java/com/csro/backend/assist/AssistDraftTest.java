package com.csro.backend.assist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withRawStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.csro.backend.cases.CaseQueries;
import java.security.Principal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 초안 Agent 응답 경계와 실패 시 무저장. 업무 DB와 실제 Agent에 접속하지 않는다. */
class AssistDraftTest {
    private static final String BASE = "http://127.0.0.1:8001";
    private static final String TOKEN = "assist-client-unit-fixture";
    private static final Principal COUNSELOR = () -> "demo-counselor-001";
    private static final String FACTS = "{\"hotelName\":\"제주 오션뷰 호텔\",\"nights\":[]}";
    private static final String VALID = """
            {"caseId":"C-001","kind":"GUIDANCE","source":"RULES","model":null,"summary":"요약입니다.",
             "nextActions":["고객에게 안내하세요."],"customerMessageDraft":"안녕하세요.","warnings":["초안입니다."]}
            """;

    private MockRestServiceServer server;
    private AssistAgentClient client;
    private final CaseQueries cases = mock(CaseQueries.class);
    private final AssistStore store = mock(AssistStore.class);
    private final AssistStore.CaseFacts stored = new AssistStore.CaseFacts(7, 3, FACTS);

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AssistAgentClient(builder.build(), TOKEN);
        when(cases.authorize(COUNSELOR)).thenReturn(COUNSELOR.getName());
        when(store.facts("C-001")).thenReturn(Optional.of(stored));
    }

    @Test
    void validDraftIsReturnedAndOnlyItsKindSourceAndModelAreRecorded() {
        server.expect(requestTo(BASE + AssistAgentClient.PATH)).andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(content().json("{\"caseId\":\"C-001\",\"kind\":\"GUIDANCE\",\"facts\":" + FACTS + "}",
                        JsonCompareMode.STRICT))
                .andRespond(withSuccess(VALID, MediaType.APPLICATION_JSON));
        var draft = new AssistService(cases, store, client).draft(COUNSELOR, "C-001", "GUIDANCE");
        assertEquals("RULES", draft.source());
        assertNull(draft.model());
        assertEquals(List.of("고객에게 안내하세요."), draft.nextActions());
        verify(store).recordGenerated(eq(stored), eq("demo-counselor-001"), eq("GUIDANCE"), eq("RULES"), eq(null), any());
        server.verify();
    }

    @Test
    void invalidOrFailedAgentReplyIs503AndRecordsNothing() {
        String[] invalid = {
                "[]",
                VALID.replace("C-001", "C-002"),
                VALID.replace("\"RULES\"", "\"LLM\""),
                VALID.replace("\"RULES\"", "\"OTHER\""),
                VALID.replace("\"요약입니다.\"", "\"" + "가".repeat(401) + "\""),
                VALID.replace("[\"고객에게 안내하세요.\"]", "[1]"),
                VALID.replace("\"안녕하세요.\"", "{}"),
                VALID.replace(",\"warnings\":[\"초안입니다.\"]", ""),
        };
        for (String body : invalid) {
            server.expect(requestTo(BASE + AssistAgentClient.PATH)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        }
        server.expect(requestTo(BASE + AssistAgentClient.PATH))
                .andRespond(withRawStatus(503).contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"detail\":\"upstream text\"}"));
        var service = new AssistService(cases, store, client);
        for (int index = 0; index <= invalid.length; index++) {
            var failure = assertThrows(AssistAgentClient.Unavailable.class, () -> service.draft(COUNSELOR, "C-001", "GUIDANCE"));
            assertEquals(503, failure.getStatusCode().value());
            assertEquals("ASSIST_UNAVAILABLE", failure.getReason());
            assertNull(failure.getCause());
        }
        assertThrows(AssistAgentClient.Unavailable.class,
                () -> new AssistService(cases, store, new AssistAgentClient(RestClient.create(BASE), " "))
                        .draft(COUNSELOR, "C-001", "GUIDANCE"));
        verify(store, never()).recordGenerated(any(), any(), any(), any(), any(), any());
        server.verify();
    }
}
