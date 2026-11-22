package com.csro.backend.risk;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/** An adapter must supply the complete observed hotel history (including first confirmations
 * and requests), never a partial SQL snapshot. No production adapter exists yet. */
public interface RiskObservations {
    Optional<List<Event>> completeHistory(String hotel, OffsetDateTime cutoff);

    record Event(String event_id, String hotel_id, String booking_id, String event_type,
                 OffsetDateTime occurred_at, OffsetDateTime available_at, JsonNode payload) {
        boolean visible(OffsetDateTime time) {
            return !occurred_at.isAfter(time) && !available_at.isAfter(time);
        }
        String field(String name) { return payload.required(name).asString(); }
    }

    record Input(String hotelId, LocalDate targetCheckInDate, String asOf,
                 String featureSetVersion, Map<String,Object> features) {}
    record Prepared(Input input, List<String> bookings) {}
}
