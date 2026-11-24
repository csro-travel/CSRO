package com.csro.backend.replacements;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class ReplacementRuntimeTest {
    @Test
    void normalStartupDoesNotDispatchOrResumeReservations() {
        var service = mock(ReplacementService.class);
        var runtime = new ReplacementRuntime(service, new MockEnvironment(), mock(ApplicationContext.class));
        runtime.start();
        runtime.stop();
        verifyNoInteractions(service);
    }

    @Test
    void workerAndRecoveryCannotBypassTheVerifiedExclusiveLoopbackProfile() {
        var service = mock(ReplacementService.class);
        var context = mock(ApplicationContext.class);
        when(context.containsBean("localChatDemoAuthentication")).thenReturn(true);
        for (String option : new String[]{"replacement.worker.enabled", "replacement.recovery.request-id"}) {
            String value = option.endsWith("enabled") ? "true" : "BRQ-" + "a".repeat(64);
            var normal = new MockEnvironment().withProperty(option, value).withProperty("server.address", "127.0.0.1");
            assertThrows(IllegalStateException.class, () -> new ReplacementRuntime(service, normal, context));
            var mixed = new MockEnvironment().withProperty(option, value).withProperty("server.address", "127.0.0.1");
            mixed.setActiveProfiles("local-chat-demo", "local");
            assertThrows(IllegalStateException.class, () -> new ReplacementRuntime(service, mixed, context));
            var exposed = new MockEnvironment().withProperty(option, value).withProperty("server.address", "0.0.0.0");
            exposed.setActiveProfiles("local-chat-demo");
            assertThrows(IllegalStateException.class, () -> new ReplacementRuntime(service, exposed, context));
            var unverified = new MockEnvironment().withProperty(option, value).withProperty("server.address", "127.0.0.1");
            unverified.setActiveProfiles("local-chat-demo");
            assertThrows(IllegalStateException.class, () -> new ReplacementRuntime(service, unverified, mock(ApplicationContext.class)));
        }
        verifyNoInteractions(service);
    }
}
