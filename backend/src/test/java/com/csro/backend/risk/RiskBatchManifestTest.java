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
            {"format":"risk-automation-batch-2","targetCheckInDate":"2026-11-10",
             "projections":["projection.json"],"observations":[]}
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
                INPUT.replace("risk-automation-batch-2", "risk-automation-batch-1"),
                INPUT.replace(",\"observations\":[]", ""),
                INPUT + "{}")) {
            assertThrows(IllegalArgumentException.class, () -> read(invalid));
        }
    }

    @Test
    void bindsEachHotelToOneSnapshotAndDigestWithoutAmbiguousSources() {
        String source = "{\"hotelId\":\"HOTEL-001\",\"path\":\"observed.json\",\"sha256\":\"" + "a".repeat(64) + "\"}";
        String valid = INPUT.replace("\"observations\":[]", "\"observations\":[" + source + "]");
        var entry = read(valid).observations().getFirst();
        assertEquals("HOTEL-001", entry.hotelId());
        assertEquals(Path.of("handoff/observed.json").toAbsolutePath(), entry.path());
        for (String invalid : List.of(valid.replace("\"observations\":[" + source + "]",
                "\"observations\":[" + source + "," + source + "]"), valid.replace("a".repeat(64), "abc"))) {
            assertThrows(IllegalArgumentException.class, () -> read(invalid));
        }
    }

    private static RiskBatchManifest read(String input) {
        return RiskBatchManifest.read(input.getBytes(StandardCharsets.UTF_8), Path.of("handoff"));
    }
}
