package com.csro.backend.incidents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class IncidentRecheckRuntimeTest {
    @Test void ordinaryProfilesStartNoBackgroundWork() {
        var jobs=mock(IncidentRecheckJobs.class);
        var runtime=new IncidentRecheckRuntime(jobs,new MockEnvironment(),mock(ApplicationContext.class));
        runtime.start(); runtime.stop();
        verifyNoInteractions(jobs);
    }

    @Test void optInCannotEscapeTheVerifiedExclusiveDemoProfile() {
        var jobs=mock(IncidentRecheckJobs.class);
        var context=mock(ApplicationContext.class);
        var environment=new MockEnvironment().withProperty("incident.recheck.worker.enabled","true")
                .withProperty("server.address","127.0.0.1");
        assertThrows(IllegalStateException.class,()->new IncidentRecheckRuntime(jobs,environment,context));
        environment.setActiveProfiles("local-chat-demo");
        assertThrows(IllegalStateException.class,()->new IncidentRecheckRuntime(jobs,environment,context));
        when(context.containsBean("localChatDemoAuthentication")).thenReturn(true);
        assertDoesNotThrow(()->new IncidentRecheckRuntime(jobs,environment,context));
        environment.setActiveProfiles("local-chat-demo","local");
        assertThrows(IllegalStateException.class,()->new IncidentRecheckRuntime(jobs,environment,context));
        verifyNoInteractions(jobs);
    }
}
