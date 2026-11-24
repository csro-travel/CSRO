package com.csro.backend.replacements;

import jakarta.annotation.PreDestroy;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Explicitly enabled, loopback-only booking worker. Normal profiles start no work. */
@Component
public class ReplacementRuntime {
    private static final Logger LOG = LoggerFactory.getLogger(ReplacementRuntime.class);
    private final ReplacementService service;
    private final boolean enabled;
    private final String recoveryId;
    private ScheduledExecutorService worker;

    public ReplacementRuntime(ReplacementService service, Environment environment, ApplicationContext context) {
        this.service = service;
        enabled = environment.getProperty("replacement.worker.enabled", Boolean.class, false);
        recoveryId = environment.getProperty("replacement.recovery.request-id", "");
        if (enabled || !recoveryId.isBlank()) {
            if (!Arrays.equals(environment.getActiveProfiles(), new String[]{"local-chat-demo"})
                    || !"127.0.0.1".equals(environment.getProperty("server.address"))
                    || !context.containsBean("localChatDemoAuthentication"))
                throw new IllegalStateException("Replacement worker requires verified exclusive local-chat-demo");
            if (!recoveryId.isBlank() && !recoveryId.matches("BRQ-[0-9a-f]{64}"))
                throw new IllegalStateException("Replacement recovery requires the original BRQ identifier");
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if ((!enabled && recoveryId.isBlank()) || worker != null) return;
        if (!recoveryId.isBlank()) service.resume(recoveryId);
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "replacement-local-worker"); thread.setDaemon(true); return thread;
        });
        worker.scheduleWithFixedDelay(() -> {
            try {
                if (enabled) service.runDue(10);
                else {
                    var result = service.execute(recoveryId);
                    if (result.manualRequired() || "CONFIRMED".equals(result.status()) || "FAILED".equals(result.status())) stop();
                }
            } catch (RuntimeException failure) {
                LOG.warn("가상 예약 결과 확인에 실패했습니다. 저장된 요청 상태를 확인해 주세요.");
            }
        }, 0, 2, TimeUnit.SECONDS);
    }

    @PreDestroy
    public synchronized void stop() { if (worker != null) worker.shutdownNow(); }
}
