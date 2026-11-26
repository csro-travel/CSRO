package com.csro.backend.hotelanalysis;

import static com.csro.backend.hotelanalysis.HotelReplyAnalysisContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.sun.net.httpserver.HttpServer;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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

/** API-024 HTTP boundary only; no Spring context, business DB or extractor changes. */
class HotelReplyAnalysisClientTest {
    private static final String BASE = "http://127.0.0.1:8000";
    private static final String ENDPOINT = BASE + "/internal/v1/hotel-reply-extractions";
    private static final String TOKEN = "unit-test-agent-service-token";
    private static final String TEXT = "11월 10일은 객실 제공이 어렵고 11월 11일과 12일은 아직 확인 중입니다.";
    private static final List<LocalDate> DATES = List.of(LocalDate.parse("2026-11-10"),
            LocalDate.parse("2026-11-11"), LocalDate.parse("2026-11-12"));
    private static final String REQUEST = """
            {"evidenceId":"EV-002","bookingId":"YB-001","hotelId":"HOTEL-001",
             "stayDates":["2026-11-10","2026-11-11","2026-11-12"],
             "originalMessage":"11월 10일은 객실 제공이 어렵고 11월 11일과 12일은 아직 확인 중입니다.",
             "locale":"ko-KR","messageOccurredAt":"2026-11-09T10:05+09:00","hotelTimeZone":"Asia/Seoul"}
            """;
    private static final String REPLY = """
            {"evidenceId":"EV-002","analysisStatus":"EXTRACTED","modelVersion":"hotel-reply-extractor-0.1",
             "claims":[{"stayDate":"2026-11-10","reportedProvisionStatus":"UNAVAILABLE",
                        "supportingText":"11월 10일은 객실 제공이 어렵고"}],
             "unresolvedStayDates":["2026-11-11","2026-11-12"],
             "requiresHumanVerification":true,"warnings":["11월 11일과 12일은 미확인입니다."]}
            """;
    private RestClient transport;
    private MockRestServiceServer server;
    private HotelReplyAnalysisClient client;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        transport = builder.build();
        client = new HotelReplyAnalysisClient(transport, TOKEN);
    }

    @AfterEach
    void noUnexpectedCallsOrRetries() {
        server.verify();
    }

    @Test
    void sendsTheEightFieldsWithoutChangingTextAndRetainsUnknownResponseDataOnlyInSnapshot() {
        String reply = REPLY.replace("\"warnings\":", "\"unused\":{\"closeCase\":true,\"confidence\":0.99},\"warnings\":");
        server.expect(requestTo(ENDPOINT)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json(REQUEST, JsonCompareMode.STRICT))
                .andRespond(withSuccess(reply, MediaType.APPLICATION_JSON));

        var analysis = client.analyze(input());

        assertEquals("EV-002", analysis.evidenceId());
        assertEquals("EXTRACTED", analysis.analysisStatus());
        assertEquals("hotel-reply-extractor-0.1", analysis.modelVersion());
        assertEquals(List.of(new Claim(DATES.getFirst(), "UNAVAILABLE", "11월 10일은 객실 제공이 어렵고")), analysis.claims());
        assertEquals(DATES.subList(1, 3), analysis.unresolvedStayDates());
        assertTrue(analysis.requiresHumanVerification());
        assertEquals(reply, analysis.responseJson());
        assertEquals(HotelReplyAnalysisClient.requestJson(input()), analysis.requestJson());
        assertDoesNotThrow(() -> HotelReplyAnalysisClient.validateAnalysis(input(), analysis));
    }

    @Test
    void explicitlyTransmitsUnknownOccurrenceTimeAndAcceptsAmbiguousAndNewModelVersions() {
        String reply = """
                {"evidenceId":"EV-002","analysisStatus":"AMBIGUOUS","modelVersion":"future-extractor-0.2",
                 "claims":[],"unresolvedStayDates":["2026-11-10","2026-11-11","2026-11-12"],
                 "requiresHumanVerification":true,"warnings":[]}
                """;
        var input = new Input("EV-002", "YB-001", "HOTEL-001", DATES, TEXT, "ko-KR", null, "Asia/Seoul");
        server.expect(requestTo(ENDPOINT)).andExpect(content().json(REQUEST.replace(
                        "\"messageOccurredAt\":\"2026-11-09T10:05+09:00\"", "\"messageOccurredAt\":null"), JsonCompareMode.STRICT))
                .andRespond(withSuccess(reply, MediaType.APPLICATION_JSON));
        var analysis = client.analyze(input);
        assertEquals("AMBIGUOUS", analysis.analysisStatus());
        assertEquals("future-extractor-0.2", analysis.modelVersion());
        assertTrue(analysis.claims().isEmpty());
        assertEquals(DATES, analysis.unresolvedStayDates());
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void rejectsWrongIdentityDatesInventedQuotesAndCoercedOrMalformedResults(String reply) {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(reply, MediaType.APPLICATION_JSON));
        var failure = assertThrows(HotelReplyAnalysisException.class, () -> client.analyze(input()));
        assertEquals("INVALID_AGENT_RESPONSE", failure.code());
        assertEquals(200, failure.httpStatus());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains(TEXT));
    }

    static Stream<String> invalidResponses() {
        String claim = "{\"stayDate\":\"2026-11-10\",\"reportedProvisionStatus\":\"UNAVAILABLE\",\"supportingText\":\"11월 10일은 객실 제공이 어렵고\"}";
        return Stream.of("", "{", "{}", "null", "[]", REPLY + "{}",
                REPLY.replace("EV-002", "EV-WRONG"),
                REPLY.replace("\"evidenceId\":\"EV-002\"", "\"evidenceId\":\"EV-002\",\"evidenceId\":\"EV-002\""),
                REPLY.replace("\"requiresHumanVerification\":true,", ""),
                REPLY.replace("2026-11-10", "2026-11-13"),
                REPLY.replace("2026-11-10", "2026-11-31"),
                REPLY.replace("\"2026-11-10\"", "1794268800"),
                REPLY.replace("\"2026-11-11\",\"2026-11-12\"", "\"2026-11-10\",\"2026-11-12\""),
                REPLY.replace("\"2026-11-11\",\"2026-11-12\"", "\"2026-11-11\",\"2026-11-11\""),
                REPLY.replace("\"2026-11-11\",\"2026-11-12\"", "\"2026-11-11\""),
                "{\"evidenceId\":\"EV-002\",\"analysisStatus\":\"EXTRACTED\",\"modelVersion\":\"extractor\",\"claims\":["
                        + claim + "," + claim + "],\"unresolvedStayDates\":[\"2026-11-11\",\"2026-11-12\"],"
                        + "\"requiresHumanVerification\":true,\"warnings\":[]}",
                REPLY.replace("11월 10일은 객실 제공이 어렵고", "원문에 없는 호텔 확정 안내"),
                REPLY.replace("11월 10일은 객실 제공이 어렵고", " "),
                REPLY.replace("\"UNAVAILABLE\"", "\"UNKNOWN\""),
                REPLY.replace("\"EXTRACTED\"", "\"AMBIGUOUS\""),
                REPLY.replace("hotel-reply-extractor-0.1", " "),
                REPLY.replace("hotel-reply-extractor-0.1", "x".repeat(81)),
                REPLY.replace("\"hotel-reply-extractor-0.1\"", "1"),
                REPLY.replace("\"requiresHumanVerification\":true", "\"requiresHumanVerification\":false"),
                REPLY.replace("\"requiresHumanVerification\":true", "\"requiresHumanVerification\":1"),
                REPLY.replace("\"requiresHumanVerification\":true", "\"requiresHumanVerification\":\"true\""),
                REPLY.replace("[\"11월 11일과 12일은 미확인입니다.\"]", "[1]"),
                REPLY.replace("\"warnings\":[", "\"unused\":\"\\u0000\",\"warnings\":["));
    }

    @ParameterizedTest
    @MethodSource("invalidInputs")
    void invalidLocalContextCannotCallTheAgent(Input request) {
        var failure = assertThrows(HotelReplyAnalysisException.class, () -> client.analyze(request));
        assertEquals("INVALID_ANALYSIS_REQUEST", failure.code());
        assertNull(failure.httpStatus());
    }

    static Stream<Arguments> invalidInputs() {
        return Stream.of(Arguments.of((Object) null),
                Arguments.of(new Input("", "YB-001", "HOTEL-001", DATES, TEXT, "ko-KR", null, "Asia/Seoul")),
                Arguments.of(new Input("EV-002", "YB-001", "HOTEL-001", List.of(), TEXT, "ko-KR", null, "Asia/Seoul")),
                Arguments.of(new Input("EV-002", "YB-001", "HOTEL-001", Arrays.asList(DATES.getFirst(), null), TEXT, "ko-KR", null, "Asia/Seoul")),
                Arguments.of(new Input("EV-002", "YB-001", "HOTEL-001", List.of(DATES.getFirst(), DATES.getFirst()), TEXT, "ko-KR", null, "Asia/Seoul")),
                Arguments.of(new Input("EV-002", "YB-001", "HOTEL-001", DATES, " ", "ko-KR", null, "Asia/Seoul")),
                Arguments.of(new Input("EV-002", "YB-001", "HOTEL-001", DATES, "😀".repeat(10_001), "ko-KR", null, "Asia/Seoul")),
                Arguments.of(new Input("EV-002", "YB-001", "HOTEL-001", DATES, "bad\u0000text", "ko-KR", null, "Asia/Seoul")),
                Arguments.of(new Input("EV-002", "YB-001", "HOTEL-001", DATES, TEXT, "en-US", null, "Asia/Seoul")),
                Arguments.of(new Input("EV-002", "YB-001", "HOTEL-001", DATES, TEXT, "ko-KR", null, "+09:00")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "bad token", "bad\nheader"})
    void configurationIsCheckedAtCallTimeWithoutAnHttpAttempt(String token) {
        var unconfigured = new HotelReplyAnalysisClient(transport, token);
        assertEquals("AGENT_NOT_CONFIGURED",
                assertThrows(HotelReplyAnalysisException.class, () -> unconfigured.analyze(input())).code());
    }

    @Test
    void malformedBaseUrlDoesNotPreventConstructionButFailsClosedOnExplicitUse() {
        var unconfigured = assertDoesNotThrow(() -> new HotelReplyAnalysisClient("bad base url", TOKEN));
        var failure = assertThrows(HotelReplyAnalysisException.class, () -> unconfigured.analyze(input()));
        assertEquals("AGENT_NOT_CONFIGURED", failure.code());
        assertNull(failure.getCause());
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "application/problem+json", "application/*"})
    void incorrectSuccessMediaCannotBeStoredAsAnAnalysis(String type) {
        server.expect(requestTo(ENDPOINT)).andRespond(withRawStatus(200).header("Content-Type", type).body(REPLY));
        assertEquals("INVALID_AGENT_RESPONSE",
                assertThrows(HotelReplyAnalysisException.class, () -> client.analyze(input())).code());
    }

    @Test
    void missingContentTypeAndOversizedSuccessBodyAreRejected() {
        server.expect(requestTo(ENDPOINT)).andRespond(withRawStatus(200).body(REPLY));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess("x".repeat(1024 * 1024 + 1), MediaType.APPLICATION_JSON));
        assertEquals("INVALID_AGENT_RESPONSE", assertThrows(HotelReplyAnalysisException.class, () -> client.analyze(input())).code());
        assertEquals("INVALID_AGENT_RESPONSE", assertThrows(HotelReplyAnalysisException.class, () -> client.analyze(input())).code());
    }

    @ParameterizedTest
    @MethodSource("upstreamErrors")
    void upstreamErrorsRetainOnlyStableCodeObservedStatusAndLocalTrace(int status, String code) {
        server.expect(requestTo(ENDPOINT)).andRespond(withRawStatus(status).contentType(MediaType.TEXT_HTML)
                .header("X-Request-ID", TOKEN).body("private provider body token=" + TOKEN));
        var failure = assertThrows(HotelReplyAnalysisException.class, () -> client.analyze(input()));
        assertEquals(code, failure.code());
        assertEquals(status, failure.httpStatus());
        assertNotNull(failure.traceId());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains(TOKEN));
        assertFalse(failure.toString().contains("private provider"));
    }

    static Stream<Arguments> upstreamErrors() {
        return Stream.of(Arguments.of(400, "INVALID_ANALYSIS_REQUEST"), Arguments.of(422, "INVALID_ANALYSIS_REQUEST"),
                Arguments.of(401, "AGENT_UNAUTHENTICATED"), Arguments.of(503, "AGENT_UNAVAILABLE"),
                Arguments.of(500, "AGENT_HTTP_ERROR"), Arguments.of(302, "AGENT_HTTP_ERROR"));
    }

    @Test
    void timeoutAndConnectionErrorsHaveNoUnderlyingMessageCauseOrRetry() {
        server.expect(requestTo(ENDPOINT)).andRespond(withException(new SocketTimeoutException(TOKEN)));
        server.expect(requestTo(ENDPOINT)).andRespond(withException(new ConnectException(TEXT)));
        var timeout = assertThrows(HotelReplyAnalysisException.class, () -> client.analyze(input()));
        assertEquals("AGENT_TIMEOUT", timeout.code());
        assertNull(timeout.getCause());
        var connection = assertThrows(HotelReplyAnalysisException.class, () -> client.analyze(input()));
        assertEquals("AGENT_CONNECTION_FAILED", connection.code());
        assertNull(connection.getCause());
        assertFalse(timeout.toString().contains(TOKEN));
        assertFalse(connection.toString().contains(TEXT));
    }

    @Test
    void productionTransportDoesNotForwardAuthenticationAcrossRedirects() throws Exception {
        var redirected = new AtomicInteger();
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/internal/v1/hotel-reply-extractions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Location", "/redirect-target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        http.createContext("/redirect-target", exchange -> {
            redirected.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        http.start();
        try {
            var actual = new HotelReplyAnalysisClient("http://127.0.0.1:" + http.getAddress().getPort(), TOKEN);
            var failure = assertThrows(HotelReplyAnalysisException.class, () -> actual.analyze(input()));
            assertEquals("AGENT_HTTP_ERROR", failure.code());
            assertEquals(302, failure.httpStatus());
            assertEquals(0, redirected.get());
        } finally {
            http.stop(0);
        }
    }

    @Test
    void storageGuardRejectsTypedResultOrRequestSnapshotsThatDifferFromTheActualInputAndReply() {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(REPLY, MediaType.APPLICATION_JSON));
        var analysis = client.analyze(input());
        var forged = new Analysis(analysis.evidenceId(), analysis.analysisStatus(), "different-typed-version",
                analysis.claims(), analysis.unresolvedStayDates(), true, analysis.warnings(), analysis.requestJson(), analysis.responseJson());
        assertEquals("INVALID_AGENT_RESPONSE", assertThrows(HotelReplyAnalysisException.class,
                () -> HotelReplyAnalysisClient.validateAnalysis(input(), forged)).code());
        var changedRequest = new Analysis(analysis.evidenceId(), analysis.analysisStatus(), analysis.modelVersion(),
                analysis.claims(), analysis.unresolvedStayDates(), true, analysis.warnings(),
                analysis.requestJson().replace("YB-001", "YB-OTHER"), analysis.responseJson());
        assertEquals("INVALID_AGENT_RESPONSE", assertThrows(HotelReplyAnalysisException.class,
                () -> HotelReplyAnalysisClient.validateAnalysis(input(), changedRequest)).code());
    }

    private static Input input() {
        return new Input("EV-002", "YB-001", "HOTEL-001", DATES, TEXT, "ko-KR",
                OffsetDateTime.parse("2026-11-09T10:05:00+09:00"), "Asia/Seoul");
    }
}
