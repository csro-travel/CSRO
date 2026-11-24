package com.csro.backend.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.time.OffsetDateTime;
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

import com.csro.backend.risk.RiskMlException.Kind;
import com.csro.backend.risk.RiskRequest.Features;

/** API-023 wire boundary tests only: no Spring context, business database or model training. */
class RiskMlClientTest {

    private static final String BASE = "http://127.0.0.1:8002";
    private static final String ENDPOINT = BASE + "/internal/v1/risk-assessments";
    private static final String TOKEN = "unit-test-ml-service-token";
    private static final String TRACE = "11111111-1111-4111-8111-111111111111";
    private static final LocalDate TARGET = LocalDate.of(2026, 11, 10);
    private static final OffsetDateTime AS_OF = OffsetDateTime.parse("2026-11-09T09:00:00+09:00");
    private static final Features FEATURES = new Features(.05, 1, .4, .2, .1, false);
    private static final String REQUEST_JSON = """
            {"hotelId":"HOTEL-001","targetCheckInDate":"2026-11-10",
             "asOf":"2026-11-09T09:00+09:00","featureSetVersion":"risk-features-0.2",
             "features":{"pastFailureRate90d":0.05,"targetConfirmedBookingCount":1,
              "bookingVolumeGrowth7d":0.4,"reconfirmationOverdueRate7d":0.2,
              "inventoryUnavailableRate7d":0.1,"isPeakDay":false}}
            """;
    private static final String REPLY = """
            {"hotelId":"HOTEL-001","targetCheckInDate":"2026-11-10",
             "asOf":"2026-11-09T09:00:00+09:00","riskScore":0.16093123456789123,
             "modelVersion":"process-synthetic-lightgbm-0.2-84d386281d22",
             "featureSetVersion":"risk-features-0.2"}
            """;

    private RestClient restClient;
    private MockRestServiceServer server;
    private RiskMlClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
        client = new RiskMlClient(restClient, TOKEN);
    }

    @AfterEach
    void verifyNoUnexpectedOrRetriedRequests() {
        server.verify();
    }

    @Test
    void sendsExactlyTheVersionedFiveFieldRequestAndKeepsRawScorePrecision() {
        server.expect(requestTo(ENDPOINT)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json(REQUEST_JSON, JsonCompareMode.STRICT))
                .andRespond(withSuccess(REPLY, MediaType.APPLICATION_JSON));

        RiskMlClient.Assessment result = client.assess(request());

        assertEquals("HOTEL-001", result.hotelId());
        assertEquals(TARGET, result.targetCheckInDate());
        assertEquals(AS_OF.toInstant(), result.asOf().toInstant());
        assertEquals(.16093123456789123, result.riskScore());
        assertEquals(RiskMlClient.MODEL_VERSION, result.modelVersion());
        assertEquals(RiskRequest.FEATURE_SET_VERSION, result.featureSetVersion());
    }

    @Test
    void acceptsAnEquivalentUtcPredictionInstantInRequestAndResponse() {
        RiskRequest request = new RiskRequest("HOTEL-001", TARGET,
                OffsetDateTime.parse("2026-11-09T00:00:00Z"), RiskRequest.FEATURE_SET_VERSION, FEATURES);
        server.expect(requestTo(ENDPOINT)).andExpect(content().json(
                REQUEST_JSON.replace("2026-11-09T09:00+09:00", "2026-11-09T00:00Z"), JsonCompareMode.STRICT))
                .andRespond(withSuccess(REPLY, MediaType.APPLICATION_JSON));
        assertEquals(AS_OF.toInstant(), client.assess(request).asOf().toInstant());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void failsClosedBeforeHttpWhenTokenIsNotConfigured(String token) {
        client = new RiskMlClient(restClient, token);
        RiskMlException error = assertThrows(RiskMlException.class, () -> client.assess(request()));
        assertEquals(Kind.NO_SERVICE_TOKEN, error.kind());
        assertNull(error.httpStatus());
    }

    @Test
    void treatsNullTokenAsUnconfigured() {
        client = new RiskMlClient(restClient, null);
        assertEquals(Kind.NO_SERVICE_TOKEN,
                assertThrows(RiskMlException.class, () -> client.assess(request())).kind());
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsInvalidLocalInputsBeforeMakingAnyHttpRequest(RiskRequest request) {
        RiskMlException error = assertThrows(RiskMlException.class, () -> client.assess(request));
        assertEquals(Kind.INVALID_REQUEST, error.kind());
        assertNull(error.httpStatus());
        assertNull(error.getCause());
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of((Object) null),
                Arguments.of(new RiskRequest(" ", TARGET, AS_OF, RiskRequest.FEATURE_SET_VERSION, FEATURES)),
                Arguments.of(new RiskRequest("HOTEL-001", TARGET, AS_OF.plusSeconds(1), RiskRequest.FEATURE_SET_VERSION, FEATURES)),
                Arguments.of(new RiskRequest("HOTEL-001", TARGET, AS_OF, "risk-features-0.1", FEATURES)),
                Arguments.of(new RiskRequest("HOTEL-001", null, AS_OF, RiskRequest.FEATURE_SET_VERSION, FEATURES)),
                Arguments.of(new RiskRequest("HOTEL-001", TARGET, AS_OF, RiskRequest.FEATURE_SET_VERSION, null)),
                Arguments.of(withFeatures(new Features(Double.NaN, 1, .4, .2, .1, false))),
                Arguments.of(withFeatures(new Features(.05, -1, .4, .2, .1, false))),
                Arguments.of(withFeatures(new Features(.05, 1, Double.POSITIVE_INFINITY, .2, .1, false))),
                Arguments.of(withFeatures(new Features(.05, 1, 10.1, .2, .1, false))),
                Arguments.of(withFeatures(new Features(.05, 1, .4, 1.1, .1, false))),
                Arguments.of(withFeatures(new Features(.05, 1, .4, .2, -.1, false))));
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void rejectsIncorrectIdentitiesVersionsShapesTypesAndNonfiniteScores(String body) {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        RiskMlException error = assertThrows(RiskMlException.class, () -> client.assess(request()));
        assertEquals(Kind.INVALID_RESPONSE, error.kind());
        assertEquals(200, error.httpStatus());
        assertNull(error.getCause());
    }

    static Stream<String> invalidResponses() {
        return Stream.of("", "null", "[]", "{", "{\"data\":" + REPLY + "}", REPLY + "{}",
                REPLY.replace("\"hotelId\":\"HOTEL-001\"", "\"hotelId\":\"HOTEL-001\",\"extra\":true"),
                REPLY.replace("\"hotelId\":\"HOTEL-001\",", ""),
                REPLY.replace("HOTEL-001", "HOTEL-002"),
                REPLY.replace("\"hotelId\":\"HOTEL-001\"", "\"hotelId\":1"),
                REPLY.replace("2026-11-10", "2026-11-11"),
                REPLY.replace("2026-11-09T09:00:00+09:00", "2026-11-09T09:00:01+09:00"),
                REPLY.replace("2026-11-09T09:00:00+09:00", "2026-11-09T09:00:00"),
                REPLY.replace("2026-11-09T09:00:00+09:00", "2026-11-09T09:00:00+09:00:00"),
                REPLY.replace("risk-features-0.2", "risk-features-0.1"),
                REPLY.replace("process-synthetic-lightgbm-0.2-84d386281d22", "unapproved-model"),
                REPLY.replace("0.16093123456789123", "\"0.16093123456789123\""),
                REPLY.replace("0.16093123456789123", "true"),
                REPLY.replace("0.16093123456789123", "null"),
                REPLY.replace("0.16093123456789123", "-0.01"),
                REPLY.replace("0.16093123456789123", "1.01"),
                REPLY.replace("0.16093123456789123", "1e309"),
                REPLY.replace("0.16093123456789123", "NaN"),
                REPLY.replace("\"riskScore\":0.16093123456789123", "\"riskScore\":0.2,\"riskScore\":0.3"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "application/problem+json", "application/*"})
    void rejectsWrongSuccessContentType(String type) {
        server.expect(requestTo(ENDPOINT)).andRespond(withRawStatus(200).header("Content-Type", type).body(REPLY));
        assertEquals(Kind.INVALID_RESPONSE,
                assertThrows(RiskMlException.class, () -> client.assess(request())).kind());
    }

    @Test
    void rejectsMissingSuccessContentType() {
        server.expect(requestTo(ENDPOINT)).andRespond(withRawStatus(200).body(REPLY));
        assertEquals(Kind.INVALID_RESPONSE,
                assertThrows(RiskMlException.class, () -> client.assess(request())).kind());
    }

    @ParameterizedTest
    @MethodSource("upstreamErrors")
    void preservesOnlyWhitelistedProblemMetadataAndNeverFallsBackToZero(
            int status, Kind kind, String code) {
        String problem = """
                {"type":"about:blank","title":"Failure","status":%d,
                 "detail":"private path and token=%s","code":"%s","traceId":"%s"}
                """.formatted(status, TOKEN, code, TRACE);
        server.expect(requestTo(ENDPOINT)).andRespond(withRawStatus(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).header("X-Request-ID", TRACE).body(problem));

        RiskMlException error = assertThrows(RiskMlException.class, () -> client.assess(request()));

        assertEquals(kind, error.kind());
        assertEquals(status, error.httpStatus());
        assertEquals(code, error.code());
        assertEquals(TRACE, error.traceId());
        assertFalse(error.toString().contains(TOKEN));
        assertFalse(error.toString().contains("private path"));
        assertNull(error.getCause());
    }

    static Stream<Arguments> upstreamErrors() {
        return Stream.of(Arguments.of(400, Kind.REQUEST_REJECTED, "INVALID_RISK_REQUEST"),
                Arguments.of(401, Kind.AUTHENTICATION, "UNAUTHENTICATED_SERVICE"),
                Arguments.of(422, Kind.REQUEST_REJECTED, "FEATURE_SCHEMA_UNSUPPORTED"),
                Arguments.of(422, Kind.REQUEST_REJECTED, "INVALID_FEATURE_VALUES"),
                Arguments.of(503, Kind.UNAVAILABLE, "MODEL_UNAVAILABLE"),
                Arguments.of(500, Kind.HTTP_ERROR, "INTERNAL_ERROR"));
    }

    @Test
    void discardsUntrustedErrorMetadataAndDoesNotRetainItsBody() {
        String problem = """
                {"type":"about:blank","status":503,"code":"token=%s","traceId":"%s"}
                """.formatted(TOKEN, TOKEN);
        server.expect(requestTo(ENDPOINT)).andRespond(withRawStatus(503)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).header("X-Request-ID", TOKEN).body(problem));
        RiskMlException error = assertThrows(RiskMlException.class, () -> client.assess(request()));
        assertEquals(Kind.UNAVAILABLE, error.kind());
        assertEquals(503, error.httpStatus());
        assertNull(error.code());
        assertNull(error.traceId());
        assertNull(error.getCause());
        assertFalse(error.toString().contains(TOKEN));
    }

    @Test
    void preservesHttpFailureWhenProblemBodyCannotBeDecoded() {
        server.expect(requestTo(ENDPOINT)).andRespond(withRawStatus(503)
                .contentType(MediaType.TEXT_HTML).body("<html>private upstream failure</html>"));
        RiskMlException error = assertThrows(RiskMlException.class, () -> client.assess(request()));
        assertEquals(Kind.UNAVAILABLE, error.kind());
        assertEquals(503, error.httpStatus());
        assertNull(error.code());
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 503})
    void capsSuccessfulAndErrorBodiesAtSixtyFourKiB(int status) {
        server.expect(requestTo(ENDPOINT)).andRespond(withRawStatus(status)
                .contentType(MediaType.APPLICATION_JSON).body("x".repeat(64 * 1024 + 1)));
        RiskMlException error = assertThrows(RiskMlException.class, () -> client.assess(request()));
        assertEquals(Kind.RESPONSE_TOO_LARGE, error.kind());
        assertEquals(status, error.httpStatus());
        assertNull(error.code());
    }

    @Test
    void doesNotTreatRedirectAsSuccessfulInferenceOrRetryIt() {
        server.expect(requestTo(ENDPOINT)).andRespond(withRawStatus(302)
                .header("Location", "http://other.invalid/private"));
        RiskMlException error = assertThrows(RiskMlException.class, () -> client.assess(request()));
        assertEquals(Kind.HTTP_ERROR, error.kind());
        assertEquals(302, error.httpStatus());
    }

    @Test
    void reportsTimeoutWithoutRetainingUnderlyingDetailsOrRetrying() {
        server.expect(requestTo(ENDPOINT)).andRespond(withException(new SocketTimeoutException(TOKEN)));
        RiskMlException error = assertThrows(RiskMlException.class, () -> client.assess(request()));
        assertEquals(Kind.TIMEOUT, error.kind());
        assertNull(error.httpStatus());
        assertNull(error.getCause());
        assertFalse(error.toString().contains(TOKEN));
    }

    @Test
    void reportsConnectionFailureWithoutAFabricatedScore() {
        server.expect(requestTo(ENDPOINT)).andRespond(withException(new ConnectException(TOKEN)));
        RiskMlException error = assertThrows(RiskMlException.class, () -> client.assess(request()));
        assertEquals(Kind.CONNECTION_FAILURE, error.kind());
        assertNull(error.httpStatus());
        assertNull(error.getCause());
    }

    private static RiskRequest request() {
        return withFeatures(FEATURES);
    }

    private static RiskRequest withFeatures(Features features) {
        return new RiskRequest("HOTEL-001", TARGET, AS_OF, RiskRequest.FEATURE_SET_VERSION, features);
    }
}
