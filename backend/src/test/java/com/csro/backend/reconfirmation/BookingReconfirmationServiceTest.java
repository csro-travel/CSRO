package com.csro.backend.reconfirmation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withRawStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.csro.backend.reconfirmation.BookingReconfirmationException.Kind;
import com.csro.backend.reconfirmation.BookingReconfirmationException.Step;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.HotelPending;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.HotelReply;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.Night;
import com.csro.backend.reconfirmation.ReconfirmationBookingRepository.Booking;

class BookingReconfirmationServiceTest {

    private static final String BASE = "http://127.0.0.1:8001";
    private static final String HOTEL = BASE + "/mock-hotel/v1/reconfirmations";
    private static final LocalDate CHECK_IN = LocalDate.parse("2026-11-10");
    private static final LocalDate CHECK_OUT = LocalDate.parse("2026-11-13");
    private static final List<LocalDate> DATES = CHECK_IN.datesUntil(CHECK_OUT).toList();
    private static final String SUPPLIER = """
            {"supplierBookingId":"SUP-BOOK-001","referenceBookingId":"YB-001",
             "propertyId":"HOTEL-001","contractStatus":"CONFIRMED",
             "stay":{"checkInDate":"2026-11-10","checkOutDate":"2026-11-13","adults":2,"rooms":1},
             "updatedAt":"2026-11-09T09:00:00+09:00"}
            """;
    private static final String REPLY = """
            {"reconfirmationRequestId":"RECHECK-001","reconfirmationId":"RC-001","status":"RESPONDED",
             "supplierBookingId":"SUP-BOOK-001","propertyId":"HOTEL-001","nights":[
               {"stayDate":"2026-11-10","reportedProvisionStatus":"UNAVAILABLE","message":"First night"},
               {"stayDate":"2026-11-11","reportedProvisionStatus":"UNKNOWN","message":"Checking"},
               {"stayDate":"2026-11-12","reportedProvisionStatus":"UNKNOWN","message":"Checking"}
             ],"occurredAt":"2026-11-09T10:05:00+09:00","sentAt":"2026-11-09T10:06:00+09:00"}
            """;
    private static final String PENDING = """
            {"reconfirmationRequestId":"RECHECK-004","reconfirmationId":"RC-004",
             "status":"PENDING","receivedAt":"2026-11-09T10:05:00+09:00"}
            """;

    private final ReconfirmationBookingRepository bookings = mock(ReconfirmationBookingRepository.class);
    private MockRestServiceServer server;
    private RestClient client;
    private BookingReconfirmationService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
        service = new BookingReconfirmationService(bookings, new ReconfirmationMockClient(client, "test-service-token"));
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void verifiesSupplierAndHotelClaimsSeparately(int scenario) {
        givenBooking(scenario, DATES);
        givenSupplier(scenario, forScenario(SUPPLIER, scenario));
        String reply = forScenario(REPLY, scenario);
        List<String> expected = List.of("UNAVAILABLE", "UNKNOWN", "UNKNOWN");
        if (scenario == 2) {
            reply = reply.replace("UNAVAILABLE", "AVAILABLE").replace("UNKNOWN", "AVAILABLE");
            expected = List.of("AVAILABLE", "AVAILABLE", "AVAILABLE");
        }
        expectHotel(scenario, 200, reply, MediaType.APPLICATION_JSON);

        BookingReconfirmationResult result = service.reconfirm(bookingCode(scenario), requestId(scenario));
        HotelReply hotel = assertInstanceOf(HotelReply.class, result.hotel());
        assertEquals("CONFIRMED", result.supplier().contractStatus());
        assertEquals(DATES, hotel.nights().stream().map(Night::stayDate).toList());
        assertEquals(expected, hotel.nights().stream().map(Night::reportedProvisionStatus).toList());
    }

    @Test
    void returnsPendingAsASeparateOutcome() {
        givenBooking(4, DATES);
        givenSupplier(4, forScenario(SUPPLIER, 4));
        expectHotel(4, 202, PENDING, MediaType.APPLICATION_JSON);

        HotelPending result = assertInstanceOf(HotelPending.class,
                service.reconfirm("YB-004", "RECHECK-004").hotel());
        assertEquals("PENDING", result.status());
        assertEquals("RC-004", result.reconfirmationId());
    }

    @Test
    void rejectsPendingWithAnotherRequestId() {
        givenBooking(4, DATES);
        givenSupplier(4, forScenario(SUPPLIER, 4));
        expectHotel(4, 202, PENDING.replace("RECHECK-004", "wrong"), MediaType.APPLICATION_JSON);
        assertFailure(4, Step.API_027, Kind.INVALID_RESPONSE, 202, null);
    }

    @ParameterizedTest
    @MethodSource("badSupplierReplies")
    void stopsBeforeHotelIfSupplierDiffers(String body) {
        givenBooking(1, DATES);
        givenSupplier(1, body);
        assertFailure(1, Step.API_026, Kind.INVALID_RESPONSE, 200, null);
    }

    static Stream<String> badSupplierReplies() {
        return Stream.of(SUPPLIER.replace("SUP-BOOK-001", "SUP-BOOK-009"),
                SUPPLIER.replace("YB-001", "YB-009"), SUPPLIER.replace("HOTEL-001", "HOTEL-009"),
                SUPPLIER.replace("CONFIRMED", "CANCELLED"),
                SUPPLIER.replace("2026-11-10", "2026-11-09"),
                SUPPLIER.replace("2026-11-13", "2026-11-14"),
                SUPPLIER.replace("\"adults\":2", "\"adults\":3"),
                SUPPLIER.replace("\"rooms\":1", "\"rooms\":2"));
    }

    @ParameterizedTest
    @MethodSource("badHotelReplies")
    void rejectsMismatchedHotelReply(String reply) {
        givenBooking(1, DATES);
        givenSupplier(1, SUPPLIER);
        expectHotel(1, 200, reply, MediaType.APPLICATION_JSON);
        assertFailure(1, Step.API_027, Kind.INVALID_RESPONSE, 200, null);
    }

    static Stream<String> badHotelReplies() {
        return Stream.of(REPLY.replace("RECHECK-001", "wrong"), REPLY.replace("SUP-BOOK-001", "wrong"),
                REPLY.replace("HOTEL-001", "wrong"), REPLY.replace("2026-11-12", "2026-11-11"),
                REPLY.replace("2026-11-12", "2026-11-13"),
                REPLY.replaceFirst(",\\s*\\{\"stayDate\":\"2026-11-12\"[^}]*}", ""),
                REPLY.replace("UNAVAILABLE", "UNRECOGNIZED"));
    }

    @Test
    void doesNotCallMockWithMissingStoredNights() {
        givenBooking(1, DATES.subList(0, 2));
        assertFailure(1, Step.BOOKING, Kind.INVALID_BOOKING_NIGHTS, null, null);
    }

    @Test
    void doesNotCallMockForMissingBooking() {
        when(bookings.findByCode("YB-001")).thenReturn(Optional.empty());
        assertFailure(1, Step.BOOKING, Kind.BOOKING_NOT_FOUND, null, null);
    }

    @Test
    void rejectsMissingServiceTokenBeforeHttp() {
        givenBooking(1, DATES);
        service = new BookingReconfirmationService(bookings, new ReconfirmationMockClient(client, ""));
        assertFailure(1, Step.API_026, Kind.NO_SERVICE_TOKEN, null, null);
    }

    @ParameterizedTest
    @MethodSource("upstreamFailures")
    void preservesHttpStatusAndProblemCode(int scenario, Step step, int status, Kind kind, String code) {
        givenBooking(scenario, DATES);
        String problem = """
                {"type":"about:blank","title":"Upstream error","status":%d,"detail":"Fixture",
                 "code":"%s","traceId":"test-trace"}
                """.formatted(status, code);
        if (step == Step.API_027) {
            givenSupplier(scenario, forScenario(SUPPLIER, scenario));
            expectHotel(scenario, status, problem, MediaType.APPLICATION_PROBLEM_JSON);
        } else {
            server.expect(requestTo(supplierUri(scenario)))
                    .andRespond(withRawStatus(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem));
        }
        assertEquals("test-trace", assertFailure(scenario, step, kind, status, code).traceId());
    }

    static Stream<Arguments> upstreamFailures() {
        return Stream.of(
                Arguments.of(1, Step.API_026, 401, Kind.AUTHENTICATION, "UNAUTHENTICATED_SERVICE"),
                Arguments.of(1, Step.API_026, 404, Kind.NOT_FOUND, "SUPPLIER_BOOKING_NOT_FOUND"),
                Arguments.of(5, Step.API_026, 503, Kind.UNAVAILABLE, "SUPPLIER_UNAVAILABLE"),
                Arguments.of(3, Step.API_027, 404, Kind.NOT_FOUND, "HOTEL_RESERVATION_NOT_FOUND"),
                Arguments.of(1, Step.API_027, 422, Kind.REQUEST_MISMATCH, "STAY_DATES_MISMATCH"),
                Arguments.of(5, Step.API_027, 503, Kind.UNAVAILABLE, "HOTEL_UNAVAILABLE"));
    }

    @Test
    void keepsHttpStatusEvenWhenProblemBodyCannotBeDecoded() {
        givenBooking(1, DATES);
        server.expect(requestTo(supplierUri(1))).andRespond(withRawStatus(503).body("unparseable"));
        assertFailure(1, Step.API_026, Kind.UNAVAILABLE, 503, null);
    }

    @Test
    void distinguishesConnectionFailureAndTimeout() {
        givenBooking(1, DATES);
        server.expect(requestTo(supplierUri(1))).andRespond(withException(new ConnectException("closed port")));
        givenSupplier(1, SUPPLIER);
        server.expect(requestTo(HOTEL)).andRespond(withException(new SocketTimeoutException("read timed out")));

        assertFailure(1, Step.API_026, Kind.CONNECTION_FAILURE, null, null);
        assertFailure(1, Step.API_027, Kind.TIMEOUT, null, null);
    }

    private BookingReconfirmationException assertFailure(int scenario, Step step, Kind kind, Integer status, String code) {
        BookingReconfirmationException failure = assertThrows(BookingReconfirmationException.class,
                () -> service.reconfirm(bookingCode(scenario), requestId(scenario)));
        assertEquals(step, failure.step());
        assertEquals(kind, failure.kind());
        assertEquals(status, failure.httpStatus());
        assertEquals(code, failure.upstreamCode());
        return failure;
    }

    private void givenBooking(int scenario, List<LocalDate> dates) {
        when(bookings.findByCode(bookingCode(scenario))).thenReturn(Optional.of(new Booking(
                bookingCode(scenario), "SUP-BOOK-%03d".formatted(scenario), "HOTEL-%03d".formatted(scenario),
                "CONFIRMED", CHECK_IN, CHECK_OUT, 2, 1, dates)));
    }

    private void givenSupplier(int scenario, String body) {
        server.expect(requestTo(supplierUri(scenario)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-service-token"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void expectHotel(int scenario, int status, String body, MediaType type) {
        String request = forScenario("""
                {"reconfirmationRequestId":"RECHECK-001","supplierBookingId":"SUP-BOOK-001",
                 "propertyId":"HOTEL-001","stayDates":["2026-11-10","2026-11-11","2026-11-12"]}
                """, scenario);
        server.expect(requestTo(HOTEL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-service-token"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(request, JsonCompareMode.STRICT))
                .andRespond(withRawStatus(status).contentType(type).body(body));
    }

    private static String forScenario(String json, int scenario) {
        return json.replace("001", "%03d".formatted(scenario));
    }

    private static String supplierUri(int scenario) {
        return BASE + "/mock-supplier/v1/bookings/SUP-BOOK-%03d".formatted(scenario);
    }

    private static String bookingCode(int scenario) {
        return "YB-%03d".formatted(scenario);
    }

    private static String requestId(int scenario) {
        return "RECHECK-%03d".formatted(scenario);
    }
}
