package com.csro.backend.risk;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/** Opt-in one-shot batch; the local launcher controls repeated invocations. */
@Component
@ConditionalOnProperty(name = "risk.batch.enabled", havingValue = "true")
public final class RiskBatchRunner implements ApplicationRunner {
    private final RiskBatchService service;
    private final ConfigurableApplicationContext context;
    private final String manifestFile;
    private final String policy;
    private final int maxTargets;
    private final int jobLimit;

    public RiskBatchRunner(RiskBatchService service, ConfigurableApplicationContext context,
                           @Value("${risk.batch.manifest-file:}") String manifestFile,
                           @Value("${risk.batch.policy:F1_BASELINE}") String policy,
                           @Value("${risk.batch.max-targets:100}") int maxTargets,
                           @Value("${risk.batch.job-limit:10}") int jobLimit) {
        this.service = service;
        this.context = context;
        this.manifestFile = manifestFile;
        this.policy = policy;
        this.maxTargets = maxTargets;
        this.jobLimit = jobLimit;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try {
            var env = context.getEnvironment();
            if (!"none".equalsIgnoreCase(env.getProperty("spring.main.web-application-type", ""))
                    || env.getProperty("risk.demo.enabled", Boolean.class, false)
                    || env.getProperty("risk.jobs.demo.enabled", Boolean.class, false)) {
                throw new IllegalArgumentException("Risk batch requires a separate non-web invocation");
            }
            if (manifestFile.isBlank()) throw new IllegalArgumentException("A risk batch manifest is required");
            var result = service.run(RiskBatchManifest.read(Path.of(manifestFile)),
                    RiskSelectionPolicy.valueOf(policy), maxTargets, jobLimit);
            System.out.println("RISK_BATCH_RESULT " + JsonMapper.builder().build().writeValueAsString(result));
        } finally {
            context.close();
        }
    }
}
