package com.csro.backend.risk;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** API-023 v0.2 input; values are supplied by Spring, never inferred from future facts. */
public record RiskRequest(String hotelId, LocalDate targetCheckInDate, OffsetDateTime asOf,
                          String featureSetVersion, Features features) {

    public static final String FEATURE_SET_VERSION = "risk-features-0.2";
    private static final ZoneOffset SEOUL = ZoneOffset.ofHours(9);

    public void validate() {
        require(hotelId != null && !hotelId.isBlank(), "hotelId must not be blank");
        require(targetCheckInDate != null && targetCheckInDate.getYear() >= 1
                && targetCheckInDate.getYear() <= 9999
                && targetCheckInDate.isAfter(LocalDate.of(1, 1, 1)), "Invalid target date");
        require(asOf != null && asOf.getYear() >= 1 && asOf.getYear() <= 9999
                && asOf.getOffset().getTotalSeconds() % 60 == 0, "Invalid asOf");
        var expected = LocalDateTime.of(targetCheckInDate.minusDays(1), java.time.LocalTime.of(9, 0))
                .atOffset(SEOUL).toInstant();
        require(asOf.toInstant().equals(expected), "asOf must equal D-1 09:00 Asia/Seoul");
        require(FEATURE_SET_VERSION.equals(featureSetVersion), "Unsupported feature schema");
        require(features != null, "features must not be null");
        require(inRange(features.pastFailureRate90d(), 0, 1), "Invalid past failure rate");
        require(features.targetConfirmedBookingCount() >= 0, "Invalid target booking count");
        require(inRange(features.bookingVolumeGrowth7d(), -1, 10), "Invalid booking growth");
        require(inRange(features.reconfirmationOverdueRate7d(), 0, 1), "Invalid overdue rate");
        require(inRange(features.inventoryUnavailableRate7d(), 0, 1), "Invalid inventory rate");
    }

    private static boolean inRange(double value, double minimum, double maximum) {
        return Double.isFinite(value) && value >= minimum && value <= maximum;
    }

    private static void require(boolean condition, String detail) {
        if (!condition) {
            throw new IllegalArgumentException(detail);
        }
    }

    public record Features(double pastFailureRate90d, int targetConfirmedBookingCount,
                           double bookingVolumeGrowth7d, double reconfirmationOverdueRate7d,
                           double inventoryUnavailableRate7d, boolean isPeakDay) {}
}
