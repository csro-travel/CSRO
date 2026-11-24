package com.csro.backend.quotes;

import jakarta.annotation.PreDestroy;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
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

/** Explicit local fixture execution; normal profiles never start this worker. */
@Component
public class QuoteSearchRuntime {
    private static final Logger LOG = LoggerFactory.getLogger(QuoteSearchRuntime.class);
    private final QuoteSearchService searches;
    private final boolean workerEnabled;
    private final OffsetDateTime demoAsOf;
    private ScheduledExecutorService worker;

    public QuoteSearchRuntime(QuoteSearchService searches, Environment environment, ApplicationContext context) {
        this.searches = searches;
        workerEnabled = environment.getProperty("quote.search.worker.enabled", Boolean.class, false);
        String configured = environment.getProperty("quote.search.demo-as-of", "");
        if (workerEnabled || !configured.isBlank()) {
            if (!Arrays.equals(environment.getActiveProfiles(), new String[]{"local-chat-demo"})
                    || !"127.0.0.1".equals(environment.getProperty("server.address"))
                    || !context.containsBean("localChatDemoAuthentication")) {
                throw new IllegalStateException("Quote fixture runtime requires the verified, exclusive local-chat-demo profile");
            }
        }
        try {
            demoAsOf = configured.isBlank() ? null : OffsetDateTime.parse(configured);
        } catch (DateTimeParseException invalid) {
            throw new IllegalStateException("Quote demo time must be an explicit ISO 8601 timestamp with timezone");
        }
    }

    public OffsetDateTime effectiveAt() {
        return demoAsOf == null ? OffsetDateTime.now(ZoneOffset.UTC) : demoAsOf;
    }

    public boolean syntheticDemo() {
        return demoAsOf != null;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!workerEnabled || worker != null) return;
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "quote-search-local-worker");
            thread.setDaemon(true);
            return thread;
        });
        worker.scheduleWithFixedDelay(() -> {
            try {
                searches.runDue(10);
            } catch (Exception failure) {
                // Neither provider text nor tokens are safe operational log content.
                LOG.warn("로컬 견적 작업 처리에 실패했습니다. 저장된 작업 상태를 확인해 주세요.");
            }
        }, 0, 2, TimeUnit.SECONDS);
    }

    @PreDestroy
    public synchronized void stop() {
        if (worker != null) worker.shutdownNow();
    }
}
