package com.csro.backend.risk;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class RiskBatchRunnerTest {
    @Test
    void refusesEveryCompetingRunnerModeBeforeBatchWork() {
        for (String competing : new String[]{"risk.demo.enabled", "risk.jobs.demo.enabled"}) {
            var service = mock(RiskBatchService.class);
            var context = context(new MockEnvironment().withProperty("spring.main.web-application-type", "none")
                    .withProperty(competing, "true"));
            assertThrows(IllegalArgumentException.class,
                    () -> new RiskBatchRunner(service, context, "unused", "F1_BASELINE", 100, 10).run(null));
            verifyNoInteractions(service);
            verify(context).close();
        }
    }

    @Test
    void oldRunnersAlsoRefuseBatchModeRegardlessOfApplicationRunnerOrdering() {
        var env = new MockEnvironment().withProperty("spring.main.web-application-type", "none")
                .withProperty("risk.batch.enabled", "true");
        var assessments = mock(RiskRecheckService.class);
        var assessmentContext = context(env);
        assertThrows(IllegalArgumentException.class,
                () -> new RiskDemoRunner(assessments, assessmentContext, "unused", "", "F1_BASELINE").run(null));
        verifyNoInteractions(assessments);
        var jobs = mock(RiskRecheckJobs.class);
        var jobsContext = context(env);
        assertThrows(IllegalArgumentException.class, () -> new RiskJobsDemoRunner(jobs, jobsContext, 10).run(null));
        verifyNoInteractions(jobs);
    }

    private static ConfigurableApplicationContext context(MockEnvironment environment) {
        var context = mock(ConfigurableApplicationContext.class);
        when(context.getEnvironment()).thenReturn(environment);
        return context;
    }
}
