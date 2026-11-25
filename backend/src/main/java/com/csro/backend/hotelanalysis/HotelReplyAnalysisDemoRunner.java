package com.csro.backend.hotelanalysis;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** One explicitly selected stored hotel original; no endpoint, poller, fixture creation or fact adoption. */
@Component
public final class HotelReplyAnalysisDemoRunner {
    static final String EVIDENCE_PROPERTY = "hotel.analysis.demo.evidence-id";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SUMMARY_PREFIX = "HOTEL_REPLY_ANALYSIS_RESULT ";
    private final HotelReplyAnalysisService service;
    private final Long evidenceId;
    private final Consumer<String> output;
    private final AtomicBoolean invoked = new AtomicBoolean();

    @Autowired
    public HotelReplyAnalysisDemoRunner(HotelReplyAnalysisService service, Environment environment,
                                        ApplicationContext context) {
        this(service, environment, context, System.out::println);
    }

    HotelReplyAnalysisDemoRunner(HotelReplyAnalysisService service, Environment environment,
                                ApplicationContext context, Consumer<String> output) {
        this.service = service;
        this.output = output;
        String configured = environment.getProperty(EVIDENCE_PROPERTY, "");
        if (configured.isEmpty()) {
            evidenceId = null;
            return;
        }
        if (!Arrays.equals(environment.getActiveProfiles(), new String[]{"local-chat-demo"})
                || !"127.0.0.1".equals(environment.getProperty("server.address"))
                || !context.containsBean("localChatDemoAuthentication")) {
            throw new IllegalStateException("Hotel analysis demo requires verified exclusive local-chat-demo");
        }
        evidenceId = parseEvidenceId(configured);
    }

    /** The existing verified web context remains alive for the counselor demo. */
    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        if (evidenceId == null || !invoked.compareAndSet(false, true)) return;
        try {
            var outcome = service.analyze(evidenceId);
            var summary = baseSummary();
            summary.put("status", outcome.status());
            summary.put("analysisId", outcome.analysisId());
            summary.put("analysisStatus", switch (outcome.analysisStatus()) {
                case "EXTRACTED", "AMBIGUOUS" -> outcome.analysisStatus();
                case null, default -> null;
            });
            // Provider version/warnings/quotes are free-form strings. Keep them in the
            // validated analysis record; never serialize that record into a startup log.
            summary.put("modelVersionRecorded", outcome.modelVersion() != null);
            summary.put("manualTaskId", outcome.manualTaskId() == null ? null : "TASK-" + outcome.manualTaskId());
            summary.put("manualHandoffRequired", outcome.manualTaskId() == null);
            summary.put("errorCode", safeCode(outcome.errorCode()));
            summary.put("traceId", safeTrace(outcome.traceId()));
            emit(summary);
        } catch (RuntimeException failure) {
            var summary = baseSummary();
            summary.put("status", "MANUAL_REQUIRED");
            summary.put("analysisId", null);
            summary.put("analysisStatus", null);
            summary.put("modelVersionRecorded", false);
            summary.put("manualTaskId", null);
            summary.put("manualHandoffRequired", true);
            summary.put("errorCode", failure instanceof HotelReplyAnalysisException typed
                    ? safeCode(typed.code()) : "LOCAL_ANALYSIS_FAILED");
            summary.put("traceId", failure instanceof HotelReplyAnalysisException typed
                    ? safeTrace(typed.traceId()) : UUID.randomUUID().toString());
            emit(summary);
        }
    }

    private Map<String, Object> baseSummary() {
        var summary = new LinkedHashMap<String, Object>();
        summary.put("evidenceId", "EV-" + evidenceId);
        summary.put("requiresHumanVerification", true);
        summary.put("manualRequired", true);
        return summary;
    }

    private void emit(Map<String, Object> summary) {
        output.accept(SUMMARY_PREFIX + JSON.writeValueAsString(summary));
    }

    private static Long parseEvidenceId(String value) {
        if (!value.matches("EV-[1-9][0-9]*")) throw invalidEvidence();
        try {
            return Long.parseLong(value.substring(3));
        } catch (NumberFormatException invalid) {
            // Do not retain the supplied flag in an exception message or cause.
            throw invalidEvidence();
        }
    }

    private static IllegalStateException invalidEvidence() {
        return new IllegalStateException("Hotel analysis demo requires an existing EV-{positive id} identifier");
    }

    private static String safeCode(String value) {
        if (value == null) return null;
        return value.matches("[A-Z][A-Z0-9_]{0,79}") ? value : "ANALYSIS_INTERNAL_ERROR";
    }

    private static String safeTrace(String value) {
        if (value == null) return null;
        return value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                ? value : UUID.randomUUID().toString();
    }
}
