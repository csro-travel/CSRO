package com.csro.backend.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RiskDemoRunnerTest {
    private static final String INPUT = """
            {"hotelId":"HOTEL-001","targetCheckInDate":"2026-11-10",
             "asOf":"2026-11-09T09:00:00+09:00","featureSetVersion":"risk-features-0.2",
             "features":{"pastFailureRate90d":0.06,"targetConfirmedBookingCount":1,
              "bookingVolumeGrowth7d":0.4,"reconfirmationOverdueRate7d":0.95,
              "inventoryUnavailableRate7d":0.9,"isPeakDay":false}}
            """;

    @Test
    void readsTheCanonicalDemoRequest() {
        var request = RiskDemoRunner.readRequest(INPUT.getBytes(StandardCharsets.UTF_8));
        assertEquals(LocalDate.of(2026, 11, 10), request.targetCheckInDate());
        assertEquals(1, request.features().targetConfirmedBookingCount());
    }

    static Stream<String> nonWireInputs() {
        return Stream.of(
                INPUT.replace("\"2026-11-10\"", "[2026,11,10]"),
                INPUT.replace("\"2026-11-09T09:00:00+09:00\"", "1794182400"),
                INPUT.replace("\"targetConfirmedBookingCount\":1", "\"targetConfirmedBookingCount\":\"1\""),
                INPUT.replace("\"targetConfirmedBookingCount\":1", "\"targetConfirmedBookingCount\":1.5"),
                INPUT.replace("\"isPeakDay\":false", "\"isPeakDay\":\"false\""),
                INPUT.replace(",\"isPeakDay\":false", ""),
                INPUT.replace("\"hotelId\":\"HOTEL-001\"", "\"hotelId\":\"HOTEL-001\",\"extra\":0"));
    }

    @ParameterizedTest
    @MethodSource("nonWireInputs")
    void rejectsCoercionAndIncompleteOrExtendedRequests(String input) {
        assertThrows(IllegalArgumentException.class,
                () -> RiskDemoRunner.readRequest(input.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsDuplicateFieldsBeforeAnyServiceInvocation() {
        var duplicate = INPUT.replace("\"hotelId\":\"HOTEL-001\"",
                "\"hotelId\":\"HOTEL-001\",\"hotelId\":\"HOTEL-002\"");
        assertThrows(IllegalArgumentException.class,
                () -> RiskDemoRunner.readRequest(duplicate.getBytes(StandardCharsets.UTF_8)));
    }
}
