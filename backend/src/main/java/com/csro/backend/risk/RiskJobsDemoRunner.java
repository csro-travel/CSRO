package com.csro.backend.risk;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/** Opt-in, bounded, one-shot drain; no HTTP endpoint or background scheduler. */
@Component
@ConditionalOnProperty(name = "risk.jobs.demo.enabled", havingValue = "true")
public final class RiskJobsDemoRunner implements ApplicationRunner {
    private final RiskRecheckJobs jobs;
    private final ConfigurableApplicationContext context;
    private final int limit;

    public RiskJobsDemoRunner(RiskRecheckJobs jobs, ConfigurableApplicationContext context,
                              @Value("${risk.jobs.demo.limit:10}") int limit) {
        this.jobs = jobs;
        this.context = context;
        this.limit = limit;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (!"none".equalsIgnoreCase(context.getEnvironment()
                    .getProperty("spring.main.web-application-type", ""))
                    || context.getEnvironment().getProperty("risk.demo.enabled", Boolean.class, false)) {
                throw new IllegalArgumentException("Job drain requires a separate non-web invocation");
            }
            var results = jobs.runDue(limit);
            System.out.println("RISK_JOBS_RESULT " + JsonMapper.builder().build().writeValueAsString(results));
        } finally {
            context.close();
        }
    }
}
