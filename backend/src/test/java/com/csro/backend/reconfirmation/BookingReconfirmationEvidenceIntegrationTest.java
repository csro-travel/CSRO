package com.csro.backend.reconfirmation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootContextLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;

import com.csro.backend.reconfirmation.BookingReconfirmationEvidenceService.Outcome;
import com.csro.backend.reconfirmation.BookingReconfirmationEvidenceService.RequestBodyConflictException;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.EvidenceBody;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.HotelPending;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.HotelReply;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.Night;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.Stay;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.SupplierBooking;

@EnabledIfEnvironmentVariable(named = "BOOKING_RECONFIRMATION_EVIDENCE_TEST", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ContextConfiguration(loader = BookingReconfirmationEvidenceIntegrationTest.IsolatedDatabaseLoader.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BookingReconfirmationEvidenceIntegrationTest {

    private static final LocalDate FIRST = LocalDate.parse("2026-11-10");
    private static final OffsetDateTime OCCURRED = OffsetDateTime.parse("2026-11-09T10:05:00+09:00");

    @Autowired BookingReconfirmationEvidenceService service;
    @Autowired ReconfirmationBookingRepository bookings;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;

    public static final class IsolatedDatabaseLoader extends SpringBootContextLoader {

        private static final String ISOLATED_URL =
                "jdbc:postgresql://127.0.0.1:25433/yeohaengbom_reconfirmation_test";

        @Override
        protected SpringApplication getSpringApplication() {
            String url = System.getenv("BOOKING_RECONFIRMATION_TEST_DB_URL");
            String password = System.getenv("BOOKING_RECONFIRMATION_TEST_DB_PASSWORD");
            if (!ISOLATED_URL.equals(url) || password == null || password.isBlank()) {
                throw new IllegalStateException("Evidence integration tests require "
                        + "BOOKING_RECONFIRMATION_TEST_DB_URL=" + ISOLATED_URL
                        + " and BOOKING_RECONFIRMATION_TEST_DB_PASSWORD");
            }

            SpringApplication application = super.getSpringApplication();
            application.addInitializers(context -> {
                // Use the validated endpoint for both JDBC and Flyway, even when the shell has Spring DB settings.
                context.getBeanFactory().registerSingleton("dataSource", new DriverManagerDataSource(url, "yb", password));
                TestPropertyValues.of(Map.of(
                        "spring.flyway.url", url,
                        "spring.flyway.user", "yb",
                        "spring.flyway.password", password,
                        "spring.flyway.enabled", "true",
                        "spring.flyway.locations", "classpath:db/migration,classpath:db/local",
                        "spring.jpa.hibernate.ddl-auto", "none"))
                        .applyTo(context.getEnvironment());
            });
            return application;
        }
    }

    @BeforeAll
    void seedOnlyIsolatedDb() {
        assertTrue(jdbc.sql("SELECT current_setting('server_version_num')::int >= 180000")
                .query(Boolean.class).single());
        assertEquals(0L, count("SELECT count(*) FROM cases"));
        assertEquals(0L, count("SELECT count(*) FROM evidence_records"));
        for (int i = 2; i <= 9; i++) {
            String id = "%03d".formatted(i);
            jdbc.sql("INSERT INTO hotels (hotel_code, name) VALUES (:hotel, :hotel)")
                    .param("hotel", "HOTEL-" + id).update();
            jdbc.sql("""
                    INSERT INTO bookings (booking_code, customer_id, hotel_id, supplier_booking_id,
                                          check_in_date, check_out_date, adults, rooms, original_total_amount)
                    SELECT :code, b.customer_id, h.id, :supplier, b.check_in_date, b.check_out_date,
                           b.adults, b.rooms, b.original_total_amount
                    FROM bookings b CROSS JOIN hotels h
                    WHERE b.booking_code = 'YB-001' AND h.hotel_code = :hotel
                    """).param("code", "YB-" + id).param("supplier", "SUP-BOOK-" + id)
                    .param("hotel", "HOTEL-" + id).update();
            jdbc.sql("""
                    INSERT INTO booking_nights (booking_id, stay_date)
                    SELECT b.id, b.check_in_date + days.i FROM bookings b CROSS JOIN generate_series(0, 2) days(i)
                    WHERE b.booking_code = :code
                    """).param("code", "YB-" + id).update();
        }
    }

    @Test
    @Order(1)
    void storesLiveYb001WireBodiesAndCreatesFirstCaseOnlyOnce() {
        Outcome first = service.reconfirmAndApply("YB-001", "RECHECK-001");
        Outcome repeat = service.reconfirmAndApply("YB-001", "RECHECK-001");
        assertFalse(first.replayed());
        assertTrue(repeat.replayed());
        assertEquals(first.supplierEvidenceId(), repeat.supplierEvidenceId());
        assertEquals(first.hotelEvidenceId(), repeat.hotelEvidenceId());
        assertEquals("C-001", first.activeCaseCode());
        assertEquals("C-001", repeat.activeCaseCode());

        assertEquals("CONFIRMED", jdbc.sql("SELECT supplier_status FROM bookings WHERE id = :id")
                .param("id", bookingId("YB-001")).query(String.class).single());
        assertEquals(List.of("UNAVAILABLE/VERIFIED", "UNKNOWN/UNCONFIRMED", "UNKNOWN/UNCONFIRMED"),
                nightStates("YB-001"));
        assertEquals(List.of("UNAVAILABLE/ACCEPTED", "UNKNOWN/PENDING_REVIEW", "UNKNOWN/PENDING_REVIEW"),
                jdbc.sql("""
                        SELECT e.reported_status || '/' || e.review_status
                        FROM evidence_nights e JOIN booking_nights n ON e.booking_night_id = n.id
                        WHERE e.evidence_id = :evidence ORDER BY n.stay_date
                        """).param("evidence", first.hotelEvidenceId()).query(String.class).list());
        assertEquals(1L, jdbc.sql("""
                SELECT count(*) FROM booking_nights n
                JOIN evidence_nights e ON e.id = n.accepted_assertion_id AND e.booking_night_id = n.id
                WHERE n.booking_id = :id AND n.stay_date = DATE '2026-11-10' AND e.evidence_id = :evidence
                """).param("id", bookingId("YB-001")).param("evidence", first.hotelEvidenceId())
                .query(Long.class).single());
        assertEquals(first.result().supplierEvidence().rawJson(), rawBody(first.supplierEvidenceId()));
        assertEquals(first.result().hotelEvidence().rawJson(), rawBody(first.hotelEvidenceId()));
        assertEquals(List.of("HOTEL_API/MOCK_HOTEL/false", "SUPPLIER_API/MOCK_SUPPLIER/false"),
                jdbc.sql("""
                        SELECT source_type || '/' || provider_code || '/' || source_authenticated::text
                        FROM evidence_records WHERE booking_id = :id ORDER BY source_type
                        """).param("id", bookingId("YB-001")).query(String.class).list());
        assertEquals(2L, perBooking("""
                SELECT count(*) FROM evidence_records WHERE booking_id = :id
                    AND CAST(raw_text AS jsonb) = CASE WHEN source_type = 'HOTEL_API'
                        THEN raw_payload -> 'response' ELSE raw_payload END
                    AND received_at > occurred_at
                """, "YB-001"));
        String requestJson = jdbc.sql("SELECT raw_payload ->> 'requestJson' FROM evidence_records WHERE id = :id")
                .param("id", first.hotelEvidenceId()).query(String.class).single();
        assertEquals(first.result().hotelEvidence().requestJson(), requestJson);
        assertTrue(jdbc.sql("""
                SELECT CAST(:requestJson AS jsonb) = CAST('{
                  "reconfirmationRequestId": "RECHECK-001",
                  "supplierBookingId": "SUP-BOOK-001",
                  "propertyId": "HOTEL-001",
                  "stayDates": ["2026-11-10", "2026-11-11", "2026-11-12"]
                }' AS jsonb)
                """).param("requestJson", requestJson).query(Boolean.class).single());
        assertEquals(List.of("C-001/OPEN/URGENT/CONTACT_CUSTOMER/TODO"), caseTasks("YB-001"));
    }

    @Test
    void handlesLiveNormalPendingAndUpstreamFailures() {
        Outcome normal = service.reconfirmAndApply("YB-002", "RECHECK-002");
        assertNull(normal.activeCaseCode());
        assertEquals(0L, perBooking("SELECT count(*) FROM cases WHERE booking_id = :id", "YB-002"));
        assertEquals(List.of("AVAILABLE/VERIFIED", "AVAILABLE/VERIFIED", "AVAILABLE/VERIFIED"), nightStates("YB-002"));

        assertEquals(404, assertThrows(BookingReconfirmationException.class,
                () -> service.reconfirmAndApply("YB-003", "RECHECK-003")).httpStatus());
        Outcome pending = service.reconfirmAndApply("YB-004", "RECHECK-004");
        assertTrue(pending.result().hotel() instanceof HotelPending);
        assertNull(pending.hotelEvidenceId());
        assertEquals(503, assertThrows(BookingReconfirmationException.class,
                () -> service.reconfirmAndApply("YB-005", "RECHECK-005")).httpStatus());
        for (String code : List.of("YB-003", "YB-004", "YB-005")) {
            assertEquals(List.of("UNKNOWN/UNCONFIRMED", "UNKNOWN/UNCONFIRMED", "UNKNOWN/UNCONFIRMED"),
                    nightStates(code));
            assertEquals(0L, perBooking("SELECT count(*) FROM evidence_records WHERE booking_id = :id", code));
            assertEquals(0L, perBooking("SELECT count(*) FROM cases WHERE booking_id = :id", code));
        }
    }

    @Test
    void differentSameRequestIsRejectedAndNewConflictingEvidencePreservesAssertion() {
        var http = mock(BookingReconfirmationService.class);
        var writer = new BookingReconfirmationEvidenceService(http, bookings, jdbc, transactions);
        when(http.reconfirm("YB-006", "r6")).thenReturn(result("006", "r6", "UNAVAILABLE"),
                result("006", "r6", "AVAILABLE"));
        when(http.reconfirm("YB-006", "r6-new")).thenReturn(result("006", "r6-new", "AVAILABLE"));

        Outcome initial = writer.reconfirmAndApply("YB-006", "r6");
        long accepted = acceptedAssertion("YB-006");
        assertThrows(RequestBodyConflictException.class, () -> writer.reconfirmAndApply("YB-006", "r6"));
        assertEquals(2L, perBooking("SELECT count(*) FROM evidence_records WHERE booking_id = :id", "YB-006"));
        Outcome changed = writer.reconfirmAndApply("YB-006", "r6-new");
        assertEquals(initial.activeCaseCode(), changed.activeCaseCode());
        assertEquals(4L, perBooking("SELECT count(*) FROM evidence_records WHERE booking_id = :id", "YB-006"));
        assertEquals(accepted, acceptedAssertion("YB-006"));
        assertEquals(initial.result().hotelEvidence().rawJson(), rawBody(initial.hotelEvidenceId()));
        assertEquals(changed.result().hotelEvidence().rawJson(), rawBody(changed.hotelEvidenceId()));
        assertEquals(List.of("UNAVAILABLE/CONFLICT", "UNKNOWN/UNCONFIRMED", "UNKNOWN/UNCONFIRMED"),
                nightStates("YB-006"));
        assertEquals(1, caseTasks("YB-006").size());
    }

    @Test
    void concurrentRequestsCreateOneActiveCaseAndTask() throws Exception {
        var http = mock(BookingReconfirmationService.class);
        var writer = new BookingReconfirmationEvidenceService(http, bookings, jdbc, transactions);
        when(http.reconfirm("YB-007", "r7-a")).thenReturn(result("007", "r7-a", "UNAVAILABLE"));
        when(http.reconfirm("YB-007", "r7-b")).thenReturn(result("007", "r7-b", "UNAVAILABLE"));
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(3)) {
            var a = workers.submit(() -> { start.await(); return writer.reconfirmAndApply("YB-007", "r7-a"); });
            var b = workers.submit(() -> { start.await(); return writer.reconfirmAndApply("YB-007", "r7-b"); });
            var duplicate = workers.submit(() -> { start.await(); return writer.reconfirmAndApply("YB-007", "r7-a"); });
            start.countDown();
            Outcome original = a.get(15, TimeUnit.SECONDS);
            Outcome repeated = duplicate.get(15, TimeUnit.SECONDS);
            assertNotNull(original.activeCaseCode());
            assertEquals(original.activeCaseCode(), b.get(15, TimeUnit.SECONDS).activeCaseCode());
            assertEquals(original.activeCaseCode(), repeated.activeCaseCode());
            assertEquals(original.supplierEvidenceId(), repeated.supplierEvidenceId());
            assertEquals(original.hotelEvidenceId(), repeated.hotelEvidenceId());
            assertTrue(original.replayed() ^ repeated.replayed());
        }
        assertEquals(1L, perBooking("SELECT count(*) FROM cases WHERE booking_id = :id", "YB-007"));
        assertEquals(1, caseTasks("YB-007").size());
        assertEquals(4L, perBooking("SELECT count(*) FROM evidence_records WHERE booking_id = :id", "YB-007"));
        assertEquals(1L, perBooking("""
                SELECT count(*) FROM evidence_nights WHERE booking_id = :id AND review_status = 'ACCEPTED'
                """, "YB-007"));
    }

    @Test
    void uniquenessFailureOnHotelInsertRollsBackSupplierAndAllBusinessChanges() throws Exception {
        var http = mock(BookingReconfirmationService.class);
        var writer = new BookingReconfirmationEvidenceService(http, bookings, jdbc, transactions);
        when(http.reconfirm("YB-008", "r8")).thenReturn(result("008", "r8", "UNAVAILABLE"));
        String key = "recheck-request:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((bookingId("YB-008") + ":r8").getBytes(StandardCharsets.UTF_8)));
        jdbc.sql("""
                INSERT INTO evidence_records (booking_id, source_type, provider_code, provider_event_id,
                    raw_text, received_at)
                VALUES (:other, 'HOTEL_API', 'MOCK_HOTEL', :key, '{}', now())
                """).param("other", bookingId("YB-009")).param("key", key).update();

        assertThrows(DataIntegrityViolationException.class, () -> writer.reconfirmAndApply("YB-008", "r8"));
        assertEquals(0L, perBooking("SELECT count(*) FROM evidence_records WHERE booking_id = :id", "YB-008"));
        assertEquals(0L, perBooking("SELECT count(*) FROM evidence_nights WHERE booking_id = :id", "YB-008"));
        assertEquals(List.of("UNKNOWN/UNCONFIRMED", "UNKNOWN/UNCONFIRMED", "UNKNOWN/UNCONFIRMED"),
                nightStates("YB-008"));
        assertTrue(caseTasks("YB-008").isEmpty());
    }

    private BookingReconfirmationResult result(String id, String request, String first) {
        var supplier = new SupplierBooking("SUP-BOOK-" + id, "YB-" + id, "HOTEL-" + id, "CONFIRMED",
                new Stay(FIRST, FIRST.plusDays(3), 2, 1), OCCURRED);
        var reply = new HotelReply(request, "fixed-rc", "RESPONDED", supplier.supplierBookingId(), supplier.propertyId(),
                List.of(new Night(FIRST, first, "first"), new Night(FIRST.plusDays(1), "UNKNOWN", "second"),
                        new Night(FIRST.plusDays(2), "UNKNOWN", "third")), OCCURRED, OCCURRED);
        return new BookingReconfirmationResult("YB-" + id, supplier, reply,
                new EvidenceBody("{\"contractStatus\":\"CONFIRMED\"}", OffsetDateTime.now(), true, null),
                new EvidenceBody("{\"first\":\"" + first + "\"}", OffsetDateTime.now(), true,
                        "{\"reconfirmationRequestId\":\"" + request + "\"}"));
    }

    private long bookingId(String code) {
        return jdbc.sql("SELECT id FROM bookings WHERE booking_code = :code")
                .param("code", code).query(Long.class).single();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private long perBooking(String sql, String code) {
        return jdbc.sql(sql).param("id", bookingId(code)).query(Long.class).single();
    }

    private String rawBody(long evidenceId) {
        return jdbc.sql("SELECT raw_text FROM evidence_records WHERE id = :id")
                .param("id", evidenceId).query(String.class).single();
    }

    private long acceptedAssertion(String code) {
        return jdbc.sql("SELECT accepted_assertion_id FROM booking_nights WHERE booking_id = :id AND stay_date = :day")
                .param("id", bookingId(code)).param("day", FIRST).query(Long.class).single();
    }

    private List<String> nightStates(String code) {
        return jdbc.sql("""
                SELECT fulfillment_status || '/' || fact_status FROM booking_nights
                WHERE booking_id = :id ORDER BY stay_date
                """).param("id", bookingId(code)).query(String.class).list();
    }

    private List<String> caseTasks(String code) {
        return jdbc.sql("""
                SELECT c.case_code || '/' || c.status || '/' || c.priority || '/' || t.task_type || '/' || t.status
                FROM cases c JOIN case_tasks t ON c.id = t.case_id WHERE c.booking_id = :id
                """).param("id", bookingId(code)).query(String.class).list();
    }
}
