package com.csro.backend.risk;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class RiskFileObservationsTest {
    private static final LocalDate TARGET = LocalDate.of(2026, 11, 10);
    private static final OffsetDateTime CUTOFF = OffsetDateTime.parse("2026-11-09T09:00:00+09:00");
    @TempDir Path directory;

    @Test
    void batchDisabledDoesNotInstallAnObservationProviderOrReadAnyFile() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(RiskFileObservations.class);
            context.refresh();
            assertTrue(context.getBeansOfType(RiskObservations.class).isEmpty());
        }
    }

    @Test
    void retainsHistoryAndLetsTheSharedProjectionEnforceBothObservationTimes() throws Exception {
        String present = event("NOW", "YB-001", "2026-11-09T09:00:00+09:00", "2026-11-09T09:00:00+09:00");
        String delayed = event("DELAYED", "YB-002", "2026-11-08T09:00:00+09:00", "2026-11-09T09:00:01+09:00");
        var provider = provider(shard(present + "," + present + "," + delayed));
        var history = provider.completeHistory("HOTEL-001", CUTOFF).orElseThrow();
        assertEquals(2, history.size());
        var prepared = new RiskProjection("HOTEL-001", TARGET, CUTOFF, history).prepare();
        assertEquals(List.of("YB-001"), prepared.bookings());
        assertEquals(1, prepared.input().features().get("targetConfirmedBookingCount"));
        assertEquals(.05, prepared.input().features().get("pastFailureRate90d"));
        assertTrue(provider.completeHistory("HOTEL-OTHER", CUTOFF).isEmpty());
        assertTrue(provider.completeHistory("HOTEL-001", CUTOFF.plusSeconds(1)).isEmpty());
    }

    @Test
    void snapshotMutationFailsBeforeItCanSupplyAnyEvents() throws Exception {
        String valid = shard(event("NOW", "YB-001", CUTOFF.toString(), CUTOFF.toString()));
        var provider = provider(valid);
        Path snapshot = provider.manifest().observations().getFirst().path();
        Files.writeString(snapshot, valid.replace("YB-001", "YB-999"));
        assertThrows(IllegalArgumentException.class, () -> provider.completeHistory("HOTEL-001", CUTOFF));
    }

    @Test
    void invalidOrConflictingRawHistoryFailsClosedEvenWithAMatchingFileChecksum() throws Exception {
        String good = event("E1", "YB-001", CUTOFF.toString(), CUTOFF.toString());
        for (String invalid : List.of(
                shard(good).replace("\"hotel_id\":\"HOTEL-001\"", "\"hotel_id\":\"HOTEL-OTHER\""),
                shard(good + "," + good.replace("YB-001", "YB-002")),
                shard(good.replace("\"rooms\":1", "\"rooms\":true")),
                shard(good.replace("\"available_at\":\"" + CUTOFF + "\"", "\"available_at\":\"2026-11-08T09:00:00+09:00\"")),
                shard(good).replace("\"hotel\":", "\"hotel\":{},\"hotel\":"),
                shard(good).replace("\"events\":", "\"truth\":{},\"events\":"))) {
            var provider = provider(invalid);
            assertThrows(IllegalArgumentException.class, () -> provider.completeHistory("HOTEL-001", CUTOFF));
        }
    }

    private RiskFileObservations provider(String content) throws Exception {
        Path snapshot = Files.createTempFile(directory, "observations-", ".json");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Files.write(snapshot, bytes);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return new RiskFileObservations(new RiskBatchManifest(TARGET, List.of(), List.of(
                new RiskBatchManifest.Observation("HOTEL-001", snapshot, digest))));
    }

    private static String shard(String events) {
        return "{\"hotel\":{\"hotel_id\":\"HOTEL-001\"},\"events\":[" + events + "]}";
    }

    private static String event(String id, String booking, String occurred, String available) {
        return """
                {"event_id":"%s","hotel_id":"HOTEL-001","booking_id":"%s",
                 "event_type":"BOOKING_STATUS","occurred_at":"%s","available_at":"%s",
                 "payload":{"status":"CONFIRMED","channel":"PLATFORM","check_in_date":"2026-11-10",
                 "check_out_date":"2026-11-13","rooms":1,"adults":2}}
                """.formatted(id, booking, occurred, available);
    }
}
