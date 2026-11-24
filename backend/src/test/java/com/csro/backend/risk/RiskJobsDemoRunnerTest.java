package com.csro.backend.risk;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class RiskJobsDemoRunnerTest {
    @Test
    void refusesConcurrentAssessmentModeBeforeDrainingJobs() {
        var jobs = mock(RiskRecheckJobs.class);
        var context = mock(ConfigurableApplicationContext.class);
        when(context.getEnvironment()).thenReturn(new MockEnvironment()
                .withProperty("spring.main.web-application-type", "none")
                .withProperty("risk.demo.enabled", "true"));
        assertThrows(IllegalArgumentException.class, () -> new RiskJobsDemoRunner(jobs, context, 10).run(null));
        verifyNoInteractions(jobs);
        verify(context).close();
    }
}
