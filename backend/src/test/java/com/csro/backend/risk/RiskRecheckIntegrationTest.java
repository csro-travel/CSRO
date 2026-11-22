package com.csro.backend.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import com.csro.backend.reconfirmation.BookingReconfirmationEvidenceService;
import com.csro.backend.reconfirmation.BookingReconfirmationResult;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.HotelPending;
import com.csro.backend.risk.RiskRecheckService.RecheckStatus;
import tools.jackson.databind.json.JsonMapper;

/**
 * Synthetic process verification against a dedicated PostgreSQL and actual ML/Mock HTTP servers.
 * These are not independent model-performance tests or historical feature reconstruction.
 */
@EnabledIfEnvironmentVariable(named = "RISK_LINK_TEST", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.profiles.active=risk-link-test",
        "spring.config.import=",
        "risk.demo.enabled=false"
})
@ContextConfiguration(loader = RiskRecheckIntegrationTest.IsolatedDatabaseLoader.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RiskRecheckIntegrationTest {
    private static final String MODEL = "process-synthetic-lightgbm-0.2-84d386281d22";
    private static final String FEATURES = "risk-features-0.2";
    private static final LocalDate TARGET = LocalDate.parse("2026-11-10");
    private static final OffsetDateTime AS_OF = OffsetDateTime.parse("2026-11-09T09:00:00+09:00");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired RiskRecheckService service;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean BookingReconfirmationEvidenceService evidence;
    @MockitoSpyBean RiskMlClient ml;

    public static final class IsolatedDatabaseLoader extends SpringBootContextLoader {
        @Override
        protected SpringApplication getSpringApplication() {
            // Validate before calling super: a wrong endpoint cannot start Spring or Flyway.
            var settings = RiskLinkTestDatabase.validate(System.getenv());
            SpringApplication application = super.getSpringApplication();
            application.addInitializers(context -> {
                context.getBeanFactory().registerSingleton("dataSource",
                        new DriverManagerDataSource(settings.url(), "yb", settings.password()));
                TestPropertyValues.of(Map.ofEntries(
                        Map.entry("spring.profiles.active", "risk-link-test"),
                        Map.entry("spring.config.import", ""),
                        Map.entry("spring.flyway.url", settings.url()),
                        Map.entry("spring.flyway.user", "yb"),
                        Map.entry("spring.flyway.password", settings.password()),
                        Map.entry("spring.flyway.enabled", "true"),
                        Map.entry("spring.flyway.locations", "classpath:db/migration,classpath:db/local"),
                        Map.entry("spring.jpa.hibernate.ddl-auto", "none"),
                        Map.entry("spring.jpa.open-in-view", "false"),
                        Map.entry("risk.demo.enabled", "false"),
                        Map.entry("risk.ml.base-url", System.getenv().getOrDefault(
                                "RISK_LINK_TEST_ML_URL", "http://127.0.0.1:18002")),
                        Map.entry("risk.ml.service-token", System.getenv().getOrDefault("ML_SERVICE_TOKEN", "")),
                        Map.entry("booking.reconfirmation.mock.base-url", System.getenv().getOrDefault(
                                "RISK_LINK_TEST_MOCK_URL", "http://127.0.0.1:18001")),
                        Map.entry("booking.reconfirmation.mock.service-token",
                                System.getenv().getOrDefault("MOCK_SERVICE_TOKEN", ""))))
                        .applyTo(context.getEnvironment());
            });
            return application;
        }
    }

    @BeforeAll
    void prepareOnlyTheDedicatedEmptyDatabase() {
        assertEquals("yeohaengbom_risk_link_test", jdbc.sql("SELECT current_database()")
                .query(String.class).single());
        assertTrue(jdbc.sql("SELECT current_setting('server_version_num')::int >= 180000")
                .query(Boolean.class).single());
        assertEquals(0L, count("risk_assessments"));
        assertEquals(0L, count("cases"));
        assertEquals(0L, count("evidence_records"));
        assertEquals(0L, count("jobs"));
        for (int i = 2; i <= 5; i++) {
            String suffix = "%03d".formatted(i);
            jdbc.sql("""
                    INSERT INTO hotels (hotel_code, name, time_zone, data_source, created_at)
                    VALUES (:hotel, :hotel, 'Asia/Seoul', 'SIMULATED', :asOf)
                    """).param("hotel", "HOTEL-" + suffix).param("asOf", AS_OF).update();
            jdbc.sql("""
                    INSERT INTO bookings (booking_code, customer_id, hotel_id, supplier_code,
                        supplier_booking_id, supplier_status, check_in_date, check_out_date,
                        adults, rooms, original_total_amount, currency, created_at, updated_at)
                    SELECT :code, b.customer_id, h.id, 'MOCK_SUPPLIER', :supplier, 'CONFIRMED',
                        b.check_in_date, b.check_out_date, b.adults, b.rooms, b.original_total_amount,
                        b.currency, :asOf, :asOf
                    FROM bookings b CROSS JOIN hotels h
                    WHERE b.booking_code = 'YB-001' AND h.hotel_code = :hotel
                    """).param("code", "YB-" + suffix).param("supplier", "SUP-BOOK-" + suffix)
                    .param("hotel", "HOTEL-" + suffix).param("asOf", AS_OF).update();
            jdbc.sql("""
                    INSERT INTO booking_nights (booking_id, stay_date, updated_at)
                    SELECT b.id, b.check_in_date + days.i, :asOf
                    FROM bookings b CROSS JOIN generate_series(0, 2) days(i)
                    WHERE b.booking_code = :code
                    """).param("code", "YB-" + suffix).param("asOf", AS_OF).update();
        }
    }

    @BeforeEach
    void clearCallHistory() {
        clearInvocations(evidence, ml);
    }

    @Test
    @Order(1)
    void originalF1StoresActualMlScoresWithoutCallingReconfirmation() {
        var high = request("001", high());
        var highResult = service.assessAndReconfirm(high, RiskSelectionPolicy.F1_BASELINE);
        assertEquals(0.16093129796741762, highResult.rawRiskScore(), 1e-14);
        assertFalse(highResult.selected());
        assertTrue(highResult.rechecks().isEmpty());
        assertEquals(RiskSelectionPolicy.F1_BASELINE, highResult.policy());
        assertStored(highResult.assessmentId(), high, "0.1609");

        var low = request("001", low());
        var lowResult = service.assessAndReconfirm(low, RiskSelectionPolicy.F1_BASELINE);
        assertEquals(0.008501510369138389, lowResult.rawRiskScore(), 1e-14);
        assertFalse(lowResult.selected());
        assertStored(lowResult.assessmentId(), low, "0.0085");
        verifyNoInteractions(evidence);
        assertEquals(2L, count("risk_assessments"));
        assertEquals(0L, count("evidence_records"));
        assertEquals(0L, count("cases"));
        assertEquals(0L, count("case_tasks"));
        assertEquals(0L, count("jobs"));
        assertEquals(List.of("UNKNOWN/UNCONFIRMED", "UNKNOWN/UNCONFIRMED", "UNKNOWN/UNCONFIRMED"),
                nightStates("YB-001"));
    }

    @Test
    @Order(2)
    void mismatchedBookingCountAndCallerTransactionStopBeforeMlAndWrites() {
        long before = count("risk_assessments");
        var wrong = request("001", new RiskRequest.Features(.06, 2, .4, .95, .9, false));
        assertThrows(IllegalArgumentException.class,
                () -> service.assessAndReconfirm(wrong, RiskSelectionPolicy.F1_BASELINE));
        assertThrows(RuntimeException.class, () -> new TransactionTemplate(transactions).execute(status ->
                service.assessAndReconfirm(request("001", high()), RiskSelectionPolicy.F1_BASELINE)));
        verifyNoInteractions(ml, evidence);
        assertEquals(before, count("risk_assessments"));
    }

    @Test
    @Order(3)
    void actualHttpModelUnavailableDoesNotSaveZeroOrCallReconfirmation() throws Exception {
        long before = count("risk_assessments");
        HttpServer unavailable = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        unavailable.createContext("/internal/v1/risk-assessments", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = """
                    {"type":"about:blank","title":"Unavailable","status":503,
                     "detail":"Model unavailable","code":"MODEL_UNAVAILABLE",
                     "traceId":"00000000-0000-4000-8000-000000000001"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/problem+json");
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        unavailable.start();
        try {
            var client = new RiskMlClient(RestClient.builder().baseUrl(
                    "http://127.0.0.1:" + unavailable.getAddress().getPort()).build(), "test-placeholder");
            var direct = new RiskRecheckService(client, evidence, jdbc, transactions);
            var failure = assertThrows(RiskMlException.class,
                    () -> direct.assessAndReconfirm(request("001", high()), RiskSelectionPolicy.F1_BASELINE));
            assertEquals(503, failure.httpStatus());
            assertEquals(before, count("risk_assessments"));
            verifyNoInteractions(evidence);
        } finally {
            unavailable.stop(0);
        }
    }

    @Test
    @Order(4)
    void recall50SelectedFailureNeedsVerifiedHotelEvidenceBeforeCaseCreation() {
        var input = request("001", high());
        var result = service.assessAndReconfirm(input, RiskSelectionPolicy.RECALL_50_F1);
        assertTrue(result.selected());
        assertEquals(0.16093129796741762, result.rawRiskScore(), 1e-14);
        assertStored(result.assessmentId(), input, "0.1609");
        assertEquals(1, result.rechecks().size());
        var checked = result.rechecks().getFirst();
        assertEquals(RecheckStatus.COMPLETED, checked.status());
        assertEquals("YB-001", checked.bookingCode());
        assertTrue(checked.requestId().startsWith("RISK-" + result.assessmentId() + "-"));
        assertNotNull(checked.caseCode());
        verify(evidence).reconfirmAndApply(checked.bookingCode(), checked.requestId());
        assertEquals(List.of("UNAVAILABLE/VERIFIED", "UNKNOWN/UNCONFIRMED", "UNKNOWN/UNCONFIRMED"),
                nightStates("YB-001"));
        assertEquals(1L, perBooking("cases", "YB-001"));
        assertEquals(1L, perBooking("case_tasks", "YB-001"));
        assertEquals(2L, perBooking("evidence_records", "YB-001"));
        assertEquals("CONFIRMED", jdbc.sql("SELECT supplier_status FROM bookings WHERE booking_code='YB-001'")
                .query(String.class).single());
        assertEquals(List.of("CONTACT_CUSTOMER/TODO"), jdbc.sql("""
                SELECT t.task_type || '/' || t.status FROM case_tasks t
                JOIN bookings b ON b.id=t.booking_id WHERE b.booking_code='YB-001'
                """).query(String.class).list());
        assertEquals(0L, count("jobs"));
    }

    @Test
    @Order(5)
    void sameHighSignalAtNormalHotelDoesNotCreateACase() {
        var result = service.assessAndReconfirm(request("002", high()), RiskSelectionPolicy.RECALL_50_F1);
        assertTrue(result.selected());
        assertEquals(RecheckStatus.COMPLETED, result.rechecks().getFirst().status());
        assertNull(result.rechecks().getFirst().caseCode());
        assertEquals(List.of("AVAILABLE/VERIFIED", "AVAILABLE/VERIFIED", "AVAILABLE/VERIFIED"),
                nightStates("YB-002"));
        assertEquals(0L, perBooking("cases", "YB-002"));
        assertEquals(0L, perBooking("case_tasks", "YB-002"));
        assertEquals(2L, perBooking("evidence_records", "YB-002"));
    }

    @Test
    @Order(6)
    void missingPendingAndSupplierUnavailableRemainUnconfirmedWithSuccessfulRiskRows() {
        long before = count("risk_assessments");
        var missing = service.assessAndReconfirm(request("003", noHistory()),
                RiskSelectionPolicy.MAX_OBSERVED_RECALL);
        var pending = service.assessAndReconfirm(request("004", noHistory()),
                RiskSelectionPolicy.MAX_OBSERVED_RECALL);
        // Additional high-signal regression fixture exercises 026=503; not the retained low YB-005 comparison fixture.
        var outage = service.assessAndReconfirm(request("005", high()), RiskSelectionPolicy.RECALL_50_F1);
        assertTrue(missing.selected());
        assertTrue(pending.selected());
        assertTrue(outage.selected());
        assertEquals(RecheckStatus.FAILED, missing.rechecks().getFirst().status());
        assertEquals("NOT_FOUND", missing.rechecks().getFirst().errorCode());
        assertEquals(RecheckStatus.PENDING, pending.rechecks().getFirst().status());
        assertEquals(RecheckStatus.FAILED, outage.rechecks().getFirst().status());
        assertEquals("UNAVAILABLE", outage.rechecks().getFirst().errorCode());
        assertEquals(before + 3, count("risk_assessments"));
        for (String code : List.of("YB-003", "YB-004", "YB-005")) {
            assertEquals(List.of("UNKNOWN/UNCONFIRMED", "UNKNOWN/UNCONFIRMED", "UNKNOWN/UNCONFIRMED"),
                    nightStates(code));
            assertEquals(0L, perBooking("evidence_records", code));
            assertEquals(0L, perBooking("cases", code));
            assertEquals(0L, perBooking("case_tasks", code));
        }
    }

    @Test
    @Order(7)
    void identicalRoundedDatabaseScoresDoNotChangeRawThresholdSelection() {
        RiskMlClient boundaryMl = mock(RiskMlClient.class);
        var pendingEvidence = mock(BookingReconfirmationEvidenceService.class);
        double threshold = RiskSelectionPolicy.F1_BASELINE.threshold();
        double[] rawScores = { Math.nextDown(threshold), Math.nextUp(threshold) };
        AtomicInteger prediction = new AtomicInteger();
        when(boundaryMl.assess(any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
            RiskRequest input = call.getArgument(0);
            return new RiskMlClient.Assessment(input.hotelId(), input.targetCheckInDate(), input.asOf(),
                    rawScores[prediction.getAndIncrement()], MODEL, FEATURES);
        });
        when(pendingEvidence.reconfirmAndApply(anyString(), anyString())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
            String booking = call.getArgument(0);
            String requestId = call.getArgument(1);
            return new BookingReconfirmationEvidenceService.Outcome(new BookingReconfirmationResult(
                    booking, null, new HotelPending(requestId, "BOUNDARY-TEST", "PENDING", AS_OF), null, null),
                    null, null, null, false);
        });
        var boundaryService = new RiskRecheckService(boundaryMl, pendingEvidence, jdbc, transactions);
        var input = request("002", high());
        var below = boundaryService.assessAndReconfirm(input, RiskSelectionPolicy.F1_BASELINE);
        assertFalse(below.selected());
        verifyNoInteractions(pendingEvidence);
        var above = boundaryService.assessAndReconfirm(input, RiskSelectionPolicy.F1_BASELINE);
        assertTrue(above.selected());
        assertEquals(rawScores[0], below.rawRiskScore());
        assertEquals(rawScores[1], above.rawRiskScore());
        assertStored(below.assessmentId(), input, "0.2234");
        assertStored(above.assessmentId(), input, "0.2234");
        var attempt = above.rechecks().getFirst();
        assertEquals(RecheckStatus.PENDING, attempt.status());
        verify(pendingEvidence).reconfirmAndApply("YB-002", attempt.requestId());
        assertEquals(0L, perBooking("cases", "YB-002"));
    }

    @Test
    @Order(8)
    void replacingABookingWithAnotherAtTheSameCountDuringMlStopsBeforeRiskSave() {
        jdbc.sql("INSERT INTO hotels (hotel_code, name, data_source, created_at) "
                + "VALUES ('HOTEL-006', 'Cohort-change regression fixture', 'SIMULATED', :asOf)")
                .param("asOf", AS_OF).update();
        for (String suffix : List.of("A", "B")) {
            jdbc.sql("""
                    INSERT INTO bookings (booking_code, customer_id, hotel_id, supplier_code,
                        supplier_booking_id, supplier_status, check_in_date, check_out_date,
                        adults, rooms, original_total_amount, created_at, updated_at)
                    SELECT :code, b.customer_id, h.id, 'MOCK_SUPPLIER', :supplier, :status,
                        b.check_in_date, b.check_out_date, b.adults, b.rooms, b.original_total_amount,
                        :asOf, :asOf
                    FROM bookings b CROSS JOIN hotels h
                    WHERE b.booking_code='YB-001' AND h.hotel_code='HOTEL-006'
                    """).param("code", "YB-006-" + suffix).param("supplier", "SUP-BOOK-006-" + suffix)
                    .param("status", suffix.equals("A") ? "CONFIRMED" : "CANCELLED")
                    .param("asOf", AS_OF).update();
            jdbc.sql("""
                    INSERT INTO booking_nights (booking_id, stay_date, updated_at)
                    SELECT b.id, b.check_in_date + days.i, :asOf FROM bookings b
                    CROSS JOIN generate_series(0,2) days(i) WHERE b.booking_code=:code
                    """).param("code", "YB-006-" + suffix).param("asOf", AS_OF).update();
        }
        long before = count("risk_assessments");
        RiskMlClient changingMl = mock(RiskMlClient.class);
        var noReconfirmation = mock(BookingReconfirmationEvidenceService.class);
        when(changingMl.assess(any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            new TransactionTemplate(transactions).executeWithoutResult(status -> jdbc.sql("""
                    UPDATE bookings SET supplier_status=CASE booking_code
                        WHEN 'YB-006-A' THEN 'CANCELLED' ELSE 'CONFIRMED' END
                    WHERE booking_code IN ('YB-006-A','YB-006-B')
                    """).update());
            RiskRequest input = call.getArgument(0);
            return new RiskMlClient.Assessment(input.hotelId(), input.targetCheckInDate(), input.asOf(),
                    .16093129796741762, MODEL, FEATURES);
        });
        var changingService = new RiskRecheckService(changingMl, noReconfirmation, jdbc, transactions);
        assertThrows(IllegalStateException.class,
                () -> changingService.assessAndReconfirm(request("006", high()), RiskSelectionPolicy.F1_BASELINE));
        assertEquals(before, count("risk_assessments"));
        verifyNoInteractions(noReconfirmation);
    }

    @Test
    @Order(9)
    void riskInsertFailureDoesNotDispatchEvenWhenTheRawScoreIsSelected() {
        long before = count("risk_assessments");
        var selectedMl = mock(RiskMlClient.class);
        var noReconfirmation = mock(BookingReconfirmationEvidenceService.class);
        var failingJdbc = spy(jdbc);
        when(selectedMl.assess(any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            RiskRequest input = call.getArgument(0);
            return new RiskMlClient.Assessment(input.hotelId(), input.targetCheckInDate(), input.asOf(),
                    .3, MODEL, FEATURES);
        });
        doThrow(new DataIntegrityViolationException("Simulated risk write failure"))
                .when(failingJdbc).sql(startsWith("INSERT INTO risk_assessments"));
        var failingService = new RiskRecheckService(selectedMl, noReconfirmation, failingJdbc, transactions);
        assertThrows(DataIntegrityViolationException.class,
                () -> failingService.assessAndReconfirm(request("001", high()), RiskSelectionPolicy.F1_BASELINE));
        assertEquals(before, count("risk_assessments"));
        verifyNoInteractions(noReconfirmation);
    }

    @Test
    @Order(10)
    void aBookingCreatedAfterTheCutoffIsExcludedFromTheCurrentDemoCohort() {
        jdbc.sql("""
                INSERT INTO bookings (booking_code, customer_id, hotel_id, supplier_code,
                    supplier_booking_id, supplier_status, check_in_date, check_out_date,
                    adults, rooms, original_total_amount, created_at, updated_at)
                SELECT 'YB-FUTURE-001', b.customer_id, b.hotel_id, 'MOCK_SUPPLIER',
                    'SUP-BOOK-FUTURE-001', 'CONFIRMED', b.check_in_date, b.check_out_date,
                    b.adults, b.rooms, b.original_total_amount, :later, :later
                FROM bookings b WHERE b.booking_code='YB-001'
                """).param("later", AS_OF.plusSeconds(1)).update();
        jdbc.sql("""
                INSERT INTO booking_nights (booking_id, stay_date, updated_at)
                SELECT b.id, b.check_in_date + days.i, :later FROM bookings b
                CROSS JOIN generate_series(0,2) days(i) WHERE b.booking_code='YB-FUTURE-001'
                """).param("later", AS_OF.plusSeconds(1)).update();
        long before = count("risk_assessments");
        var input = request("001", high());
        var result = service.assessAndReconfirm(input, RiskSelectionPolicy.F1_BASELINE);
        assertFalse(result.selected());
        assertEquals(before + 1, count("risk_assessments"));
        assertStored(result.assessmentId(), input, "0.1609");
        verifyNoInteractions(evidence);
        assertEquals(2L, jdbc.sql("""
                SELECT count(*) FROM bookings b JOIN hotels h ON h.id=b.hotel_id
                WHERE h.hotel_code='HOTEL-001' AND b.check_in_date=:targetDate
                    AND b.supplier_status='CONFIRMED'
                """).param("targetDate", TARGET).query(Long.class).single());
    }

    private void assertStored(long assessmentId, RiskRequest input, String rounded) {
        var stored = jdbc.sql("""
                SELECT r.risk_score, r.model_version, r.feature_set_version, r.target_check_in_date,
                    r.as_of, r.feature_snapshot::text AS snapshot, h.hotel_code
                FROM risk_assessments r JOIN hotels h ON h.id=r.hotel_id WHERE r.id=:id
                """).param("id", assessmentId).query((rs, row) -> {
            assertEquals(0, new BigDecimal(rounded).compareTo(rs.getBigDecimal("risk_score")));
            assertEquals(MODEL, rs.getString("model_version"));
            assertEquals(FEATURES, rs.getString("feature_set_version"));
            assertEquals(input.targetCheckInDate(), rs.getObject("target_check_in_date", LocalDate.class));
            assertEquals(input.asOf().toInstant(), rs.getObject("as_of", OffsetDateTime.class).toInstant());
            assertEquals(input.hotelId(), rs.getString("hotel_code"));
            var snapshot = JSON.readTree(rs.getString("snapshot"));
            assertEquals(6, snapshot.size());
            assertEquals(JSON.valueToTree(input.features()), snapshot);
            return 1;
        }).list();
        assertEquals(1, stored.size());
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private long perBooking(String table, String code) {
        return jdbc.sql("SELECT count(*) FROM " + table + " t JOIN bookings b ON b.id=t.booking_id "
                + "WHERE b.booking_code=:code").param("code", code).query(Long.class).single();
    }

    private List<String> nightStates(String code) {
        return jdbc.sql("""
                SELECT n.fulfillment_status || '/' || n.fact_status
                FROM booking_nights n JOIN bookings b ON b.id=n.booking_id
                WHERE b.booking_code=:code ORDER BY n.stay_date
                """).param("code", code).query(String.class).list();
    }

    private static RiskRequest request(String suffix, RiskRequest.Features features) {
        return new RiskRequest("HOTEL-" + suffix, TARGET, AS_OF, FEATURES, features);
    }

    private static RiskRequest.Features high() {
        return new RiskRequest.Features(.06, 1, .3999999999999999, .95, .9, false);
    }

    private static RiskRequest.Features low() {
        return new RiskRequest.Features(.005, 1, 0, 0, 0, false);
    }

    private static RiskRequest.Features noHistory() {
        return new RiskRequest.Features(.05, 1, 0, 0, 0, false);
    }
}
