package com.csro.backend.risk;

import java.io.IOException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class RiskProjectionTest {
    static final String HOTEL = "HOTEL-001";
    static final LocalDate DAY = LocalDate.of(2026, 11, 10);
    static final OffsetDateTime CUTOFF = OffsetDateTime.parse("2026-11-09T09:00:00+09:00");
    static final JsonMapper JSON = JsonMapper.builder().build();
    static List<RiskObservations.Event> history() {
        try (var stream = RiskProjectionTest.class.getResourceAsStream("/risk/hotel-001-observations.json")) {
            return List.of(JSON.treeToValue(JSON.readTree(stream).required("events"), RiskObservations.Event[].class));
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test void matchesPythonBuildFeaturesIncludingLateObservation() {
        var result = new RiskProjection(HOTEL, DAY, CUTOFF, history()).prepare();
        assertEquals(List.of("YB-001"), result.bookings());
        assertEquals(Map.of("pastFailureRate90d", 2.0 / 21, "targetConfirmedBookingCount", 1,
                "bookingVolumeGrowth7d", 0.6000000000000001, "reconfirmationOverdueRate7d", 1.0,
                "inventoryUnavailableRate7d", 0.5, "isPeakDay", false), result.input().features());
    }

    @Test void completeButEmptyHistoryUsesModelDenominatorRules() {
        var features = new RiskProjection(HOTEL, DAY, CUTOFF, List.of()).prepare().input().features();
        assertEquals(0.05, features.get("pastFailureRate90d"));
        assertEquals(0.0, features.get("inventoryUnavailableRate7d"));
        assertEquals(0.0, features.get("reconfirmationOverdueRate7d"));
    }
}
