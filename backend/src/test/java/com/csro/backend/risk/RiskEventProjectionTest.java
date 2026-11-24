package com.csro.backend.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

class RiskEventProjectionTest {
    private static final String HASH = "a".repeat(64);
    private static final String INPUT = """
            {"format":"risk-event-projection-1","sourceKind":"SYNTHETIC_OBSERVATIONS",
             "request":{"hotelId":"HOTEL-001","targetCheckInDate":"2026-11-10",
              "asOf":"2026-11-09T09:00:00+09:00","featureSetVersion":"risk-features-0.2",
              "features":{"pastFailureRate90d":0.05,"targetConfirmedBookingCount":1,
               "bookingVolumeGrowth7d":0.0,"reconfirmationOverdueRate7d":0.0,
               "inventoryUnavailableRate7d":0.0,"isPeakDay":false}},
             "cohortBookingCodes":["YB-001"],
             "provenance":{"sourceSha256":"HASH","featureBuilderSha256":"HASH",
              "configSha256":"HASH","lineageDigest":"HASH"}}
            """.replace("HASH", HASH);

    @Test
    void preservesExactCohortAndKeepsProvenanceOutsideModelFeatures() {
        var projection = read(INPUT);
        assertEquals(List.of("YB-001"), projection.cohortBookingCodes());
        assertEquals(0.05, projection.request().features().pastFailureRate90d());
        assertEquals(4, projection.provenance().size());
    }

    @Test
    void rejectsUnboundCohortsAndNonSyntheticOrMalformedMetadata() {
        for (String invalid : List.of(
                INPUT.replace("[\"YB-001\"]", "[]"),
                INPUT.replace("[\"YB-001\"]", "[\"YB-001\",\"YB-001\"]"),
                INPUT.replace("SYNTHETIC_OBSERVATIONS", "REAL"),
                INPUT.replace(HASH, "not-a-hash"),
                INPUT.replace("\"format\":", "\"extra\":1,\"format\":"),
                INPUT.replace("\"format\":", "\"format\":\"other\",\"format\":"),
                INPUT + "{}")) {
            assertThrows(IllegalArgumentException.class, () -> read(invalid));
        }
    }

    private static RiskEventProjection read(String input) {
        return RiskEventProjection.read(input.getBytes(StandardCharsets.UTF_8));
    }
}
