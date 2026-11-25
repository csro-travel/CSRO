package com.csro.backend.incidents;

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

/** Opt-in demo execution only. Receipt persistence remains available without this runtime. */
@Component
public class IncidentRecheckRuntime {
    private static final Logger LOG = LoggerFactory.getLogger(IncidentRecheckRuntime.class);
    private final IncidentRecheckJobs jobs;
    private final boolean enabled;
    private ScheduledExecutorService worker;

    public IncidentRecheckRuntime(IncidentRecheckJobs jobs, Environment environment, ApplicationContext context) {
        this.jobs = jobs;
        enabled = environment.getProperty("incident.recheck.worker.enabled",Boolean.class,false);
        if (enabled && (!Arrays.equals(environment.getActiveProfiles(),new String[]{"local-chat-demo"})
                || !"127.0.0.1".equals(environment.getProperty("server.address"))
                || !context.containsBean("localChatDemoAuthentication")))
            throw new IllegalStateException("Incident worker requires verified exclusive local-chat-demo");
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!enabled || worker != null) return;
        worker=Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread=new Thread(task,"incident-recheck-local-worker");
            thread.setDaemon(true);
            return thread;
        });
        worker.scheduleWithFixedDelay(() -> {
            try { jobs.runDue(10); }
            catch (RuntimeException failure) {
                LOG.warn("고객 신고 재확인 처리에 실패했습니다. 저장된 작업과 필수 확인 업무를 확인해 주세요.");
            }
        },0,2,TimeUnit.SECONDS);
    }

    @PreDestroy
    public synchronized void stop() { if (worker != null) worker.shutdownNow(); }
}
