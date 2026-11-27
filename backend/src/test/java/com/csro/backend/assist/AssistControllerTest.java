package com.csro.backend.assist;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.Principal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AssistController.class, properties = {
        "spring.config.location=optional:classpath:/__assist_no_env__.properties"
})
class AssistControllerTest {
    private static final Principal COUNSELOR = () -> "demo-counselor-001";
    private static final String PATH = "/api/v1/cases/C-001/assist-drafts";
    @Autowired MockMvc mvc;
    @MockitoBean AssistService assist;

    @Test
    void draftIsReturnedWithExplicitNullsAndUnavailableAgentIsA503Problem() throws Exception {
        when(assist.draft(COUNSELOR, "C-001", "CLOSURE_SUMMARY")).thenReturn(new AssistService.AssistDraft("C-001",
                "CLOSURE_SUMMARY", "RULES", null, "2026-11-27T01:02:03.000004Z", "요약입니다.", List.of(), null, List.of("초안입니다.")));
        mvc.perform(post(PATH).principal(COUNSELOR).contentType("application/json").content("{\"kind\":\"CLOSURE_SUMMARY\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(content().json("""
                        {"caseId":"C-001","kind":"CLOSURE_SUMMARY","source":"RULES","model":null,
                         "generatedAt":"2026-11-27T01:02:03.000004Z","summary":"요약입니다.","nextActions":[],
                         "customerMessageDraft":null,"warnings":["초안입니다."]}
                        """, org.springframework.test.json.JsonCompareMode.STRICT));
        when(assist.draft(COUNSELOR, "C-001", "GUIDANCE")).thenThrow(new AssistAgentClient.Unavailable());
        mvc.perform(post(PATH).principal(COUNSELOR).contentType("application/json").content("{\"kind\":\"GUIDANCE\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("ASSIST_UNAVAILABLE"));
    }

    @Test
    void anythingButOneKnownKindIsRejectedBeforeTheService() throws Exception {
        for (String body : new String[]{"{", "[]", "null", "{}", "{\"kind\":\"AUTO_SEND\"}", "{\"kind\":1}",
                "{\"kind\":\"GUIDANCE\",\"send\":true}"}) {
            mvc.perform(post(PATH).principal(COUNSELOR).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(content().contentType("application/problem+json"))
                    .andExpect(jsonPath("$.code").value("INVALID_ASSIST_REQUEST"));
        }
        verifyNoInteractions(assist);
    }
}
