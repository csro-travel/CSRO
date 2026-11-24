package com.csro.backend.risk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Local preprocessor output; provenance/cohort metadata never enters API-023's HTTP body. */
record RiskEventProjection(RiskRequest request, List<String> cohortBookingCodes,
                           Map<String, String> provenance) {
    private static final int MAX_BYTES = 1_048_576;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> FIELDS = Set.of(
            "format", "sourceKind", "request", "cohortBookingCodes", "provenance");
    private static final Set<String> HASHES = Set.of(
            "sourceSha256", "featureBuilderSha256", "configSha256", "lineageDigest");
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    RiskEventProjection {
        cohortBookingCodes = List.copyOf(cohortBookingCodes);
        provenance = Map.copyOf(provenance);
    }

    static RiskEventProjection read(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            return read(input.readNBytes(MAX_BYTES + 1));
        }
    }

    static RiskEventProjection read(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_BYTES) {
            throw invalid();
        }
        try {
            Object root = JSON.readValue(bytes, Object.class);
            if (!(root instanceof Map<?, ?> fields) || !fields.keySet().equals(FIELDS)
                    || !"risk-event-projection-1".equals(fields.get("format"))
                    || !"SYNTHETIC_OBSERVATIONS".equals(fields.get("sourceKind"))
                    || !(fields.get("cohortBookingCodes") instanceof List<?> codes)
                    || !(fields.get("provenance") instanceof Map<?, ?> hashes)
                    || !hashes.keySet().equals(HASHES)) {
                throw invalid();
            }
            RiskRequest request = RiskDemoRunner.readRequest(JSON.writeValueAsBytes(fields.get("request")));
            List<String> cohort = new ArrayList<>();
            for (Object value : codes) {
                if (!(value instanceof String code) || code.isBlank() || code.length() > 40) {
                    throw invalid();
                }
                cohort.add(code);
            }
            if (cohort.isEmpty() || new HashSet<>(cohort).size() != cohort.size()
                    || cohort.size() != request.features().targetConfirmedBookingCount()) {
                throw invalid();
            }
            Map<String, String> provenance = new TreeMap<>();
            for (String key : HASHES) {
                if (!(hashes.get(key) instanceof String hash) || !SHA256.matcher(hash).matches()) {
                    throw invalid();
                }
                provenance.put(key, hash);
            }
            return new RiskEventProjection(request, cohort, provenance);
        } catch (JacksonException ex) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid local synthetic event projection");
    }
}
