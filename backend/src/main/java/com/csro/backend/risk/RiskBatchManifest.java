package com.csro.backend.risk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Local launcher handoff, never an HTTP DTO or a source of business truth. */
record RiskBatchManifest(LocalDate targetCheckInDate, List<Path> projections) {
    private static final int MAX_BYTES = 1_048_576;
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    RiskBatchManifest {
        if (targetCheckInDate == null || targetCheckInDate.getYear() < 1
                || targetCheckInDate.getYear() > 9999
                || targetCheckInDate.equals(LocalDate.of(1, 1, 1)) || projections == null) {
            throw invalid();
        }
        projections = List.copyOf(projections);
    }

    static RiskBatchManifest read(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw invalid();
            return read(bytes, path.toAbsolutePath().getParent());
        }
    }

    static RiskBatchManifest read(byte[] bytes, Path parent) {
        try {
            if (bytes == null || bytes.length > MAX_BYTES || parent == null) throw invalid();
            Object root = JSON.readValue(bytes, Object.class);
            if (!(root instanceof Map<?, ?> fields)
                    || !fields.keySet().equals(Set.of("format", "targetCheckInDate", "projections"))
                    || !"risk-automation-batch-1".equals(fields.get("format"))
                    || !(fields.get("targetCheckInDate") instanceof String target)
                    || !target.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                    || !(fields.get("projections") instanceof List<?> values) || values.size() > 100) {
                throw invalid();
            }
            List<Path> paths = new ArrayList<>();
            for (Object value : values) {
                if (!(value instanceof String name) || name.isBlank() || name.length() > 4096) throw invalid();
                paths.add(parent.resolve(name).toAbsolutePath().normalize());
            }
            if (new HashSet<>(paths).size() != paths.size()) throw invalid();
            return new RiskBatchManifest(LocalDate.parse(target), paths);
        } catch (JacksonException | java.time.DateTimeException | java.nio.file.InvalidPathException ex) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid local risk batch manifest");
    }
}
