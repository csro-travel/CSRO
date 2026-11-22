package com.csro.backend.risk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.JacksonException;
import tools.jackson.core.json.JsonFactory;

/** Opt-in, one-shot local simulation. No HTTP route or scheduled job is registered. */
@Component
@ConditionalOnProperty(name = "risk.demo.enabled", havingValue = "true")
public final class RiskDemoRunner implements ApplicationRunner {
    private static final int MAX_REQUEST_BYTES = 65_536;
    private static final Set<String> REQUEST_FIELDS = Set.of(
            "hotelId", "targetCheckInDate", "asOf", "featureSetVersion", "features");
    private static final Set<String> FEATURE_FIELDS = Set.of("pastFailureRate90d",
            "targetConfirmedBookingCount", "bookingVolumeGrowth7d", "reconfirmationOverdueRate7d",
            "inventoryUnavailableRate7d", "isPeakDay");
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    private final RiskRecheckService service;
    private final ConfigurableApplicationContext context;
    private final String requestFile;
    private final String policy;

    public RiskDemoRunner(RiskRecheckService service, ConfigurableApplicationContext context,
                          @Value("${risk.demo.request-file:}") String requestFile,
                          @Value("${risk.demo.policy:F1_BASELINE}") String policy) {
        this.service = service;
        this.context = context;
        this.requestFile = requestFile;
        this.policy = policy;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try {
            if (!"none".equalsIgnoreCase(context.getEnvironment()
                    .getProperty("spring.main.web-application-type", ""))) {
                throw new IllegalArgumentException("Risk demo requires spring.main.web-application-type=none");
            }
            if (requestFile.isBlank()) {
                throw new IllegalArgumentException("Risk demo requires risk.demo.request-file");
            }
            RiskSelectionPolicy selectedPolicy = RiskSelectionPolicy.valueOf(policy);
            byte[] bytes;
            try (var input = Files.newInputStream(Path.of(requestFile))) {
                bytes = input.readNBytes(MAX_REQUEST_BYTES + 1);
            }
            if (bytes.length > MAX_REQUEST_BYTES) {
                throw new IllegalArgumentException("Risk demo request exceeds 64 KiB");
            }
            RiskRequest request = readRequest(bytes);
            var outcome = service.assessAndReconfirm(request, selectedPolicy);
            System.out.println("RISK_STAGE1_RESULT " + JSON.writeValueAsString(outcome));
        } finally {
            context.close();
        }
    }

    static RiskRequest readRequest(byte[] bytes) {
        try {
            Object root = JSON.readValue(bytes, Object.class);
            if (!(root instanceof Map<?, ?> fields) || !fields.keySet().equals(REQUEST_FIELDS)
                    || !(fields.get("hotelId") instanceof String)
                    || !(fields.get("targetCheckInDate") instanceof String)
                    || !(fields.get("asOf") instanceof String)
                    || !(fields.get("featureSetVersion") instanceof String)
                    || !(fields.get("features") instanceof Map<?, ?> features)
                    || !features.keySet().equals(FEATURE_FIELDS)) {
                throw new IllegalArgumentException("Risk demo requires the exact API-023 JSON object shape");
            }
            // Jackson's temporal modules accept arrays/epoch numbers even with
            // scalar coercion disabled. Check the wire types before conversion.
            RiskRequest request = JSON.convertValue(fields, RiskRequest.class);
            request.validate();
            return request;
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("Risk demo requires strict API-023 JSON types and values");
        }
    }
}
