package com.csro.backend.quotes;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class QuoteSearchRuntimeTest {
    @Test
    void ordinaryStartupNeverRunsTheLocalWorker() {
        var searches = mock(QuoteSearchService.class);
        var runtime = new QuoteSearchRuntime(searches, new MockEnvironment(), mock(ApplicationContext.class));
        runtime.start();
        runtime.stop();
        assertFalse(runtime.syntheticDemo());
        verifyNoInteractions(searches);
    }

    @Test
    void workerOrHistoricalTimeCannotEscapeTheDedicatedDemoGuard() {
        var context = mock(ApplicationContext.class);
        for (String key : new String[]{"quote.search.worker.enabled", "quote.search.demo-as-of"}) {
            var environment = new MockEnvironment().withProperty(key,
                    key.endsWith("enabled") ? "true" : "2026-11-09T10:20:00+09:00");
            assertThrows(IllegalStateException.class,
                    () -> new QuoteSearchRuntime(mock(QuoteSearchService.class), environment, context));
            environment.setActiveProfiles("local-chat-demo");
            environment.setProperty("server.address", "127.0.0.1");
            assertThrows(IllegalStateException.class,
                    () -> new QuoteSearchRuntime(mock(QuoteSearchService.class), environment, context));
        }
    }

    @Test
    void verifiedExclusiveDemoUsesOnlyAnExplicitTimezoneAwareTime() {
        var context = mock(ApplicationContext.class);
        when(context.containsBean("localChatDemoAuthentication")).thenReturn(true);
        var environment = new MockEnvironment().withProperty("server.address", "127.0.0.1")
                .withProperty("quote.search.demo-as-of", "2026-11-09T10:20:00+09:00");
        environment.setActiveProfiles("local-chat-demo");
        var runtime = new QuoteSearchRuntime(mock(QuoteSearchService.class), environment, context);
        assertTrue(runtime.syntheticDemo());
        assertEquals("2026-11-09T10:20+09:00", runtime.effectiveAt().toString());
        environment.setProperty("quote.search.demo-as-of", "2026-11-09T10:20:00");
        assertThrows(IllegalStateException.class,
                () -> new QuoteSearchRuntime(mock(QuoteSearchService.class), environment, context));
        environment.setProperty("quote.search.demo-as-of", "2026-11-09T10:20:00+09:00");
        environment.setActiveProfiles("local-chat-demo", "local");
        assertThrows(IllegalStateException.class,
                () -> new QuoteSearchRuntime(mock(QuoteSearchService.class), environment, context));
    }
}
