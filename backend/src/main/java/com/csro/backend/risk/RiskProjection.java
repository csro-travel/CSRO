package com.csro.backend.risk;

import java.time.*;
import java.util.*;
import java.util.stream.Stream;

final class RiskProjection {
    private static final Set<String> EVENT_TYPES = Set.of("BOOKING_STATUS", "RECONFIRMATION_REQUEST",
            "HOTEL_REPLY", "INVENTORY_PROBE", "OBSERVED_OUTCOME");
    private final List<RiskObservations.Event> events;
    private final LocalDate target;
    private final OffsetDateTime cutoff;
    private final String hotel;

    RiskProjection(String hotel, LocalDate target, OffsetDateTime cutoff, List<RiskObservations.Event> history) {
        this.hotel = hotel;
        this.target = target;
        this.cutoff = cutoff;
        var unique = new HashMap<String,RiskObservations.Event>();
        for (var event : history) {
            if (!EVENT_TYPES.contains(event.event_type())) continue;
            if (event.booking_id() == null) {
                if ("HOTEL_REPLY".equals(event.event_type())) continue;
                if (!"INVENTORY_PROBE".equals(event.event_type())
                        && !("OBSERVED_OUTCOME".equals(event.event_type()) && event.payload().path("label").isNull()))
                    throw new IllegalArgumentException("Observed booking event requires booking ID");
            }
            if (!hotel.equals(event.hotel_id())) throw new IllegalArgumentException("Mixed hotel history");
            var old = unique.putIfAbsent(event.event_id(), event);
            if (old != null && !old.equals(event)) throw new IllegalArgumentException("Conflicting event ID");
        }
        events = unique.values().stream().filter(e -> e.visible(cutoff))
                .sorted(Comparator.comparing((RiskObservations.Event e) -> e.occurred_at().toInstant())
                        .thenComparing(RiskObservations.Event::event_id)).toList();
    }

    private Stream<RiskObservations.Event> of(String type) {
        return events.stream().filter(e -> type.equals(e.event_type()));
    }

    private static boolean platformConfirmed(RiskObservations.Event status) {
        return status != null && "PLATFORM".equals(status.field("channel"))
                && "CONFIRMED".equals(status.field("status"));
    }

    private static boolean window(OffsetDateTime time, OffsetDateTime start, OffsetDateTime end) {
        return !time.isBefore(start) && time.isBefore(end);
    }

    RiskObservations.Prepared prepare() {
        var latest = new HashMap<String,RiskObservations.Event>();
        var first = new HashMap<String,RiskObservations.Event>();
        of("BOOKING_STATUS").forEach(e -> {
            latest.put(e.booking_id(), e);
            if ("CONFIRMED".equals(e.field("status"))) first.putIfAbsent(e.booking_id(), e);
        });
        var cohort = latest.values().stream().filter(RiskProjection::platformConfirmed)
                .filter(e -> target.toString().equals(e.field("check_in_date")))
                .map(RiskObservations.Event::booking_id).sorted().toList();
        var known = new HashMap<String,Integer>();
        of("OBSERVED_OUTCOME").filter(e -> platformConfirmed(first.get(e.booking_id()))).forEach(e -> {
            var day = LocalDate.parse(e.field("check_in_date"));
            var label = e.payload().get("label");
            if (day.isBefore(cutoff.toLocalDate().minusDays(90)) || !day.isBefore(cutoff.toLocalDate())
                    || label == null || label.isNull()) return;
            if (!label.isIntegralNumber() || !(label.asInt() == 0 || label.asInt() == 1))
                throw new IllegalArgumentException("Invalid observed outcome");
            var old = known.put(e.booking_id(), label.asInt());
            if (old != null && old != label.asInt()) throw new IllegalArgumentException("Conflicting outcome");
        });
        var recent = first.values().stream().filter(RiskProjection::platformConfirmed)
                .filter(e -> window(e.occurred_at(), cutoff.minusDays(7), cutoff)).count();
        var previous = first.values().stream().filter(RiskProjection::platformConfirmed)
                .filter(e -> window(e.occurred_at(), cutoff.minusDays(35), cutoff.minusDays(7))).count();
        double growth = (recent + 1) / (previous / 4.0 + 1) - 1;
        var features = Map.<String,Object>of(
                "pastFailureRate90d", (known.values().stream().mapToInt(Integer::intValue).sum() + 1.0) / (known.size() + 20),
                "targetConfirmedBookingCount", cohort.size(),
                "bookingVolumeGrowth7d", Math.clamp(growth, -1, 10),
                "reconfirmationOverdueRate7d", overdueRate(),
                "inventoryUnavailableRate7d", inventoryRate(),
                "isPeakDay", peakDay());
        return new RiskObservations.Prepared(new RiskObservations.Input(hotel, target,
                cutoff.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME), "risk-features-0.2", features), cohort);
    }

    private double overdueRate() {
        var requests = of("RECONFIRMATION_REQUEST").toList();
        var seen = new HashSet<String>();
        int eligible = 0, overdue = 0;
        for (var request : requests) {
            String booking = request.booking_id();
            if (!seen.add(booking)) continue;
            var due = OffsetDateTime.parse(request.field("due_at"));
            if (!window(due, cutoff.minusDays(7), cutoff)) continue;
            var status = of("BOOKING_STATUS").filter(e -> booking.equals(e.booking_id()) && e.visible(due))
                    .reduce((a,b) -> b).orElse(null);
            if (!platformConfirmed(status)) continue;
            eligible++;
            boolean timely = of("HOTEL_REPLY").filter(e -> booking.equals(e.booking_id()) && !e.available_at().isAfter(due))
                    .filter(e -> e.payload().path("http_status").isIntegralNumber()
                            && e.payload().path("http_status").asInt() == 200
                            && e.payload().path("schema_valid").isBoolean() && e.payload().get("schema_valid").asBoolean())
                    .filter(e -> validNights(e, status))
                    .anyMatch(e -> requests.stream().anyMatch(r -> booking.equals(r.booking_id())
                            && r.field("request_id").equals(e.field("request_id")) && !r.occurred_at().isAfter(e.occurred_at())));
            if (!timely) overdue++;
        }
        return eligible == 0 ? 0 : (double) overdue / eligible;
    }

    private static boolean validNights(RiskObservations.Event reply, RiskObservations.Event status) {
        var nights = reply.payload().get("nights");
        if (nights == null) return true;
        if (!nights.isArray()) return false;
        var expected = new HashSet<>(LocalDate.parse(status.field("check_in_date"))
                .datesUntil(LocalDate.parse(status.field("check_out_date"))).map(LocalDate::toString).toList());
        for (var night : nights) {
            if (!expected.remove(night.path("stay_date").asString(""))
                    || !Set.of("AVAILABLE","UNAVAILABLE","UNKNOWN").contains(night.path("status").asString(""))) return false;
        }
        return expected.isEmpty();
    }

    private double inventoryRate() {
        var seen = new HashSet<String>();
        int valid = 0, unavailable = 0;
        for (var e : of("INVENTORY_PROBE").toList()) {
            var p = e.payload();
            if (!target.toString().equals(e.field("target_check_in_date"))
                    || !seen.add(p.path("request_id").asString(e.event_id()))
                    || !window(e.occurred_at(), cutoff.minusDays(7), cutoff)
                    || !target.plusDays(1).toString().equals(p.path("check_out_date").asString(""))
                    || !"STANDARD".equals(p.path("room_type").asString("")) || !"KRW".equals(p.path("currency").asString(""))
                    || !p.path("adults").isIntegralNumber() || p.path("adults").asInt() != 2
                    || !p.path("rooms").isIntegralNumber() || p.path("rooms").asInt() != 1) continue;
            var rooms = p.path("available_rooms");
            if (!p.path("http_status").isIntegralNumber() || p.path("http_status").asInt() != 200 || !rooms.isIntegralNumber() || rooms.asLong() < 0) continue;
            valid++;
            if (rooms.asLong() == 0) unavailable++;
        }
        return valid == 0 ? 0 : (double) unavailable / valid;
    }

    private boolean peakDay() {
        // Exact synthetic calendar in simulator_v0.2.json, not an official holiday calendar.
        return target.getDayOfWeek() == DayOfWeek.FRIDAY || target.getDayOfWeek() == DayOfWeek.SATURDAY
                || target.getMonthValue() == 7 || target.getMonthValue() == 8
                || Set.of("2025-01-01","2025-05-05","2025-12-24","2025-12-25","2025-12-31").contains(target.toString());
    }
}
