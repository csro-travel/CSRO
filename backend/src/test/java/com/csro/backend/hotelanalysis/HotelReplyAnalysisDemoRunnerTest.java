package com.csro.backend.hotelanalysis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class HotelReplyAnalysisDemoRunnerTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PREFIX = "HOTEL_REPLY_ANALYSIS_RESULT ";

    @Test
    void remainsDisabledInOrdinaryProfilesWithoutTheExplicitEvidenceFlag() {
        var service = mock(HotelReplyAnalysisService.class);
        var context = mock(ApplicationContext.class);
        var output = new ArrayList<String>();
        var runner = new HotelReplyAnalysisDemoRunner(service, new MockEnvironment(), context, output::add);
        runner.run();
        runner.run();
        assertTrue(output.isEmpty());
        verifyNoInteractions(service, context);
    }

    @Test
    void refusesAnUnverifiedOrNonexclusiveDemoContextBeforeCallingTheService() {
        for (int scenario = 0; scenario < 4; scenario++) {
            var fixture = fixture("EV-42");
            switch (scenario) {
                case 0 -> fixture.environment().setActiveProfiles("local");
                case 1 -> fixture.environment().setActiveProfiles("local-chat-demo", "local");
                case 2 -> fixture.environment().setProperty("server.address", "0.0.0.0");
                case 3 -> when(fixture.context().containsBean("localChatDemoAuthentication")).thenReturn(false);
                default -> throw new AssertionError();
            }
            assertThrows(IllegalStateException.class, fixture::runner);
            verifyNoInteractions(fixture.service());
            assertTrue(fixture.output().isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "42", "EV-0", "EV-01", "EV--1", "EV-EXAMPLE-C002", "EV-9223372036854775808"})
    void acceptsOnlyAnActualPositiveLongEvidenceIdentifier(String identifier) {
        var fixture = fixture(identifier);
        var failure = assertThrows(IllegalStateException.class, fixture::runner);
        assertNull(failure.getCause());
        assertEquals("Hotel analysis demo requires an existing EV-{positive id} identifier", failure.getMessage());
        verifyNoInteractions(fixture.service());
    }

    @Test
    void analyzesOnlyTheSelectedEvidenceOnceAndPrintsNoFreeformProviderContent() {
        var fixture = fixture("EV-42");
        String unsafeProviderValue = "http://private-source/Bearer-secret-original-hotel-text";
        when(fixture.service().analyze(42L)).thenReturn(new HotelReplyAnalysisService.Outcome(
                HotelReplyAnalysisService.State.ANALYZED, 501L, "EV-42", "EXTRACTED",
                unsafeProviderValue, 12L, null, null, true));
        var runner = fixture.runner();
        runner.run();
        runner.run();

        verify(fixture.service(), times(1)).analyze(42L);
        verifyNoMoreInteractions(fixture.service());
        JsonNode summary = summary(fixture.output());
        assertEquals("ANALYZED", summary.get("status").stringValue());
        assertEquals("EV-42", summary.get("evidenceId").stringValue());
        assertEquals(501L, summary.get("analysisId").longValue());
        assertEquals("TASK-12", summary.get("manualTaskId").stringValue());
        assertTrue(summary.get("requiresHumanVerification").booleanValue());
        assertTrue(summary.get("manualRequired").booleanValue());
        assertFalse(summary.get("manualHandoffRequired").booleanValue());
        assertTrue(summary.get("modelVersionRecorded").booleanValue());
        assertFalse(fixture.output().getFirst().contains(unsafeProviderValue));
        assertFalse(fixture.output().getFirst().contains("http://"));
        assertFalse(fixture.output().getFirst().contains("\n"));
    }

    @Test
    void aMissingActiveCaseRequiresExplicitManualHandoffEvenAfterAValidAnalysis() {
        var fixture = fixture("EV-42");
        when(fixture.service().analyze(42L)).thenReturn(new HotelReplyAnalysisService.Outcome(
                HotelReplyAnalysisService.State.REUSED, 501L, "EV-42", "AMBIGUOUS",
                "hotel-reply-extractor-0.1", null, null, null, true));
        fixture.runner().run();
        JsonNode summary = summary(fixture.output());
        assertEquals("REUSED", summary.get("status").stringValue());
        assertTrue(summary.get("manualHandoffRequired").booleanValue());
        assertTrue(summary.get("requiresHumanVerification").booleanValue());
        assertTrue(summary.get("manualTaskId").isNull());
    }

    @Test
    void reportsControlledAgentFailureWithoutClaimingASuccessfulAnalysisOrTask() {
        var fixture = fixture("EV-42");
        String trace = "f7821a23-9802-465e-83f3-c55bd95df95b";
        when(fixture.service().analyze(42L)).thenReturn(new HotelReplyAnalysisService.Outcome(
                HotelReplyAnalysisService.State.MANUAL_REQUIRED, null, "EV-42", null,
                null, null, "AGENT_UNAUTHENTICATED", trace, true));
        fixture.runner().run();
        JsonNode summary = summary(fixture.output());
        assertEquals("MANUAL_REQUIRED", summary.get("status").stringValue());
        assertEquals("AGENT_UNAUTHENTICATED", summary.get("errorCode").stringValue());
        assertEquals(trace, summary.get("traceId").stringValue());
        assertTrue(summary.get("analysisId").isNull());
        assertTrue(summary.get("manualHandoffRequired").booleanValue());
        assertFalse(summary.get("modelVersionRecorded").booleanValue());
    }

    @Test
    void turnsUnexpectedLocalFailureIntoSafeManualDiagnosticsWithoutRetainingItsMessage() {
        var fixture = fixture("EV-42");
        String sensitive = "Authorization: Bearer private-token; SQL raw_text=private-hotel-original";
        when(fixture.service().analyze(42L)).thenThrow(new IllegalStateException(sensitive));
        var runner = fixture.runner();
        assertDoesNotThrow(runner::run);
        runner.run();
        verify(fixture.service(), times(1)).analyze(42L);
        JsonNode summary = summary(fixture.output());
        assertEquals("MANUAL_REQUIRED", summary.get("status").stringValue());
        assertEquals("LOCAL_ANALYSIS_FAILED", summary.get("errorCode").stringValue());
        assertTrue(summary.get("traceId").stringValue().matches("[a-f0-9-]{36}"));
        assertFalse(fixture.output().getFirst().contains(sensitive));
        assertFalse(fixture.output().getFirst().contains("private-token"));
        assertFalse(fixture.output().getFirst().contains("private-hotel-original"));
    }

    private static JsonNode summary(List<String> lines) {
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().startsWith(PREFIX));
        return JSON.readTree(lines.getFirst().substring(PREFIX.length()));
    }

    private static Fixture fixture(String identifier) {
        var service = mock(HotelReplyAnalysisService.class);
        var environment = new MockEnvironment().withProperty("server.address", "127.0.0.1")
                .withProperty(HotelReplyAnalysisDemoRunner.EVIDENCE_PROPERTY, identifier);
        environment.setActiveProfiles("local-chat-demo");
        var context = mock(ApplicationContext.class);
        when(context.containsBean("localChatDemoAuthentication")).thenReturn(true);
        return new Fixture(service, environment, context, new ArrayList<>());
    }

    private record Fixture(HotelReplyAnalysisService service, MockEnvironment environment,
                           ApplicationContext context, List<String> output) {
        HotelReplyAnalysisDemoRunner runner() {
            return new HotelReplyAnalysisDemoRunner(service, environment, context, output::add);
        }
    }
}
