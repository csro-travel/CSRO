package com.csro.backend.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class RiskBatchManifestTest {
    private static final String INPUT = """
            {"format":"risk-automation-batch-1","targetCheckInDate":"2026-11-10",
             "projections":["projection.json"]}
            """;

    @Test
    void resolvesLocalPathsAndAllowsAnEmptyBatchForIndependentJobRecovery() {
        var manifest = read(INPUT);
        assertEquals(LocalDate.of(2026, 11, 10), manifest.targetCheckInDate());
        assertEquals(List.of(Path.of("handoff/projection.json").toAbsolutePath()), manifest.projections());
        assertEquals(List.of(), read(INPUT.replace("[\"projection.json\"]", "[]")).projections());
    }

    @Test
    void rejectsAmbiguousOrExtendedInputBeforeAnyDatabaseOrHttpWork() {
        for (String invalid : List.of(
                INPUT.replace("[\"projection.json\"]", "[\"projection.json\",\"./projection.json\"]"),
                INPUT.replace("\"2026-11-10\"", "[2026,11,10]"),
                INPUT.replace("\"2026-11-10\"", "\"2026-02-30\""),
                INPUT.replace("\"2026-11-10\"", "\"0000-11-10\""),
                INPUT.replace("\"2026-11-10\"", "\"0001-01-01\""),
                INPUT.replace("\"format\":", "\"format\":\"other\",\"format\":"),
                INPUT.replace("\"format\":", "\"extra\":1,\"format\":"),
                INPUT + "{}")) {
            assertThrows(IllegalArgumentException.class, () -> read(invalid));
        }
    }

    private static RiskBatchManifest read(String input) {
        return RiskBatchManifest.read(input.getBytes(StandardCharsets.UTF_8), Path.of("handoff"));
    }
}
