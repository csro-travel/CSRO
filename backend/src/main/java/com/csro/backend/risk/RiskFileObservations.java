package com.csro.backend.risk;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Explicit local synthetic history adapter. A checksum binds each immutable
 * cycle snapshot, not the truth or completeness of its supplied observations.
 * A second RiskObservations bean is deliberately not overridden or preferred.
 */
@Component
@ConditionalOnProperty(name = "risk.batch.enabled", havingValue = "true")
public final class RiskFileObservations implements RiskObservations {
    private static final int MAX_BYTES = 64 * 1024 * 1024;
    private static final Set<String> EVENT_TYPES = Set.of("BOOKING_STATUS", "RECONFIRMATION_REQUEST",
            "HOTEL_REPLY", "INVENTORY_PROBE", "OBSERVED_OUTCOME");
    private static final Set<String> EVENT_FIELDS = Set.of("event_id", "hotel_id", "booking_id",
            "event_type", "occurred_at", "available_at", "payload");
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final RiskBatchManifest manifest;
    private final Map<String, RiskBatchManifest.Observation> sources;

    @Autowired
    public RiskFileObservations(@Value("${risk.batch.manifest-file:}") String manifestFile) {
        this(readManifest(manifestFile));
    }

    RiskFileObservations(RiskBatchManifest manifest) {
        this.manifest = manifest;
        var byHotel = new HashMap<String, RiskBatchManifest.Observation>();
        for (var source : manifest.observations()) byHotel.put(source.hotelId(), source);
        this.sources = Map.copyOf(byHotel);
    }

    RiskBatchManifest manifest() { return manifest; }

    @Override
    public Optional<List<Event>> completeHistory(String hotel, OffsetDateTime cutoff) {
        var expected = manifest.targetCheckInDate().minusDays(1).atTime(9, 0).atOffset(ZoneOffset.ofHours(9));
        if (hotel == null || cutoff == null || !cutoff.isEqual(expected)) return Optional.empty();
        var source = sources.get(hotel);
        if (source == null) return Optional.empty();
        try (var stream = Files.newInputStream(source.path())) {
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES || !digest(bytes).equals(source.sha256())) throw invalid();
            return Optional.of(history(JSON.readValue(bytes, Object.class), hotel));
        } catch (IOException | JacksonException | java.time.DateTimeException ex) {
            throw invalid();
        }
    }

    private static List<Event> history(Object value, String hotel) {
        var root = object(value);
        if (!root.keySet().equals(Set.of("hotel", "events"))) throw invalid();
        var identity = object(root.get("hotel"));
        if (!identity.keySet().equals(Set.of("hotel_id")) || !hotel.equals(identity.get("hotel_id"))
                || !(root.get("events") instanceof List<?> events)) throw invalid();
        var unique = new HashMap<String, Event>();
        var result = new ArrayList<Event>();
        for (Object raw : events) {
            var event = object(raw);
            if (!event.keySet().equals(EVENT_FIELDS) || !hotel.equals(event.get("hotel_id"))) throw invalid();
            String id = identifier(event.get("event_id"));
            String kind = identifier(event.get("event_type"));
            if (!EVENT_TYPES.contains(kind)) throw invalid();
            String booking = event.get("booking_id") == null ? null : identifier(event.get("booking_id"));
            OffsetDateTime occurred = instant(event.get("occurred_at"));
            OffsetDateTime available = instant(event.get("available_at"));
            if (available.isBefore(occurred)) throw invalid();
            var payload = object(event.get("payload"));
            validatePayload(kind, booking, payload);
            var parsed = new Event(id, hotel, booking, kind, occurred, available,
                    JSON.convertValue(payload, JsonNode.class));
            var previous = unique.putIfAbsent(id, parsed);
            if (previous != null && !previous.equals(parsed)) throw invalid();
            if (previous == null) result.add(parsed);
        }
        // Canonical RiskProjection owns point-in-time filtering and all formulas.
        // Future observations remain here and cannot become early visible evidence.
        return List.copyOf(result);
    }

    private static void validatePayload(String kind, String booking, Map<?, ?> payload) {
        switch (kind) {
            case "BOOKING_STATUS" -> {
                identifier(booking);
                if (!oneOf(payload.get("status"), Set.of("CONFIRMED", "CANCELLED"))
                        || !oneOf(payload.get("channel"), Set.of("PLATFORM", "OTHER"))
                        || !day(payload.get("check_out_date")).isAfter(day(payload.get("check_in_date")))) throw invalid();
                for (String name : List.of("adults", "rooms")) {
                    if (payload.containsKey(name) && (!integer(payload.get(name)) || number(payload.get(name)).signum() < 1))
                        throw invalid();
                }
            }
            case "RECONFIRMATION_REQUEST" -> {
                identifier(booking);
                identifier(payload.get("request_id"));
                instant(payload.get("due_at"));
            }
            case "OBSERVED_OUTCOME" -> {
                var label = payload.get("label");
                if (label != null) {
                    identifier(booking);
                    if (!integer(label) || number(label).signum() < 0 || number(label).compareTo(BigInteger.ONE) > 0)
                        throw invalid();
                }
                day(payload.get("check_in_date"));
            }
            case "HOTEL_REPLY" -> {
                identifier(payload.get("request_id"));
                if (payload.containsKey("schema_valid") && !(payload.get("schema_valid") instanceof Boolean)) throw invalid();
                if (payload.containsKey("nights")) {
                    if (!(payload.get("nights") instanceof List<?> nights)) throw invalid();
                    for (var raw : nights) {
                        var night = object(raw);
                        day(night.get("stay_date"));
                        if (!oneOf(night.get("status"), Set.of("AVAILABLE", "UNAVAILABLE", "UNKNOWN"))) throw invalid();
                    }
                }
                optionalInteger(payload, "http_status");
            }
            case "INVENTORY_PROBE" -> {
                identifier(payload.get("request_id"));
                day(payload.get("target_check_in_date"));
                day(payload.get("check_out_date"));
                if (!integer(payload.get("adults")) || !integer(payload.get("rooms"))) throw invalid();
                identifier(payload.get("room_type"));
                identifier(payload.get("currency"));
                optionalInteger(payload, "available_rooms");
                optionalInteger(payload, "http_status");
            }
            default -> throw invalid();
        }
    }

    private static void optionalInteger(Map<?, ?> payload, String key) {
        if (payload.get(key) != null && !integer(payload.get(key))) throw invalid();
    }

    private static boolean oneOf(Object value, Set<String> allowed) {
        return value instanceof String text && allowed.contains(text);
    }

    private static boolean integer(Object value) {
        return value instanceof Integer || value instanceof Long || value instanceof BigInteger;
    }

    private static BigInteger number(Object value) { return new BigInteger(value.toString()); }

    private static Map<?, ?> object(Object value) {
        if (!(value instanceof Map<?, ?> fields)) throw invalid();
        return fields;
    }

    private static String identifier(Object value) {
        if (!(value instanceof String text) || text.isBlank() || !text.equals(text.strip())
                || text.chars().anyMatch(c -> c < 32 || c == 127)) throw invalid();
        return text;
    }

    private static LocalDate day(Object value) {
        if (!(value instanceof String text) || !text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid();
        LocalDate result = LocalDate.parse(text);
        if (result.getYear() < 1) throw invalid();
        return result;
    }

    private static OffsetDateTime instant(Object value) {
        if (!(value instanceof String text) || !text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]{1,6})?)?(?:Z|[+-](?:[01][0-9]|2[0-3]):[0-5][0-9])")) throw invalid();
        var result = OffsetDateTime.parse(text);
        if (result.getYear() < 1) throw invalid();
        return result;
    }

    private static RiskBatchManifest readManifest(String path) {
        try {
            if (path == null || path.isBlank()) throw invalid();
            return RiskBatchManifest.read(Path.of(path));
        } catch (IOException | java.nio.file.InvalidPathException ex) {
            throw invalid();
        }
    }

    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid local synthetic risk observations");
    }
}
