package com.csro.backend.risk;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import com.csro.backend.risk.RiskMlException.Kind;

/** API-023 HTTP client: one request, verified identity, no retries or fallback score. */
public class RiskMlClient {

    public static final String MODEL_VERSION = "process-synthetic-lightgbm-0.2-84d386281d22";
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final String PATH = "/internal/v1/risk-assessments";
    private static final Set<String> RESPONSE_FIELDS = Set.of("hotelId", "targetCheckInDate", "asOf",
            "riskScore", "modelVersion", "featureSetVersion");
    private static final Pattern TRACE_ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern OFFSET_TIMESTAMP = Pattern.compile(
            "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]{1,6})?)?(?:Z|[+-][0-9]{2}:[0-9]{2})");
    private static final Map<Integer, Set<String>> ERROR_CODES = Map.of(
            400, Set.of("INVALID_RISK_REQUEST"),
            401, Set.of("UNAUTHENTICATED_SERVICE"),
            422, Set.of("FEATURE_SCHEMA_UNSUPPORTED", "INVALID_FEATURE_VALUES"),
            503, Set.of("MODEL_UNAVAILABLE"),
            500, Set.of("INTERNAL_ERROR"));
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private final RestClient client;
    private final String token;

    public RiskMlClient(String baseUrl, String token) {
        this(RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory()).build(), token);
    }

    RiskMlClient(RestClient client, String token) {
        this.client = client;
        this.token = token;
    }

    private static JdkClientHttpRequestFactory requestFactory() {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(5));
        return factory;
    }

    public Assessment assess(RiskRequest request) {
        if (request == null) {
            throw failure(Kind.INVALID_REQUEST, null, null, null);
        }
        try {
            request.validate();
        } catch (IllegalArgumentException ex) {
            throw failure(Kind.INVALID_REQUEST, null, null, null);
        }
        if (token == null || token.isBlank()) {
            throw failure(Kind.NO_SERVICE_TOKEN, null, null, null);
        }
        byte[] requestBytes = JSON.writeValueAsBytes(wireRequest(request));
        try {
            return client.post().uri(PATH).headers(headers -> headers.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)
                    .body(requestBytes).exchange((sent, response) -> {
                        int status = response.getStatusCode().value();
                        String trace = safeTrace(response.getHeaders().getFirst("X-Request-ID"));
                        byte[] bytes;
                        try {
                            bytes = limitedBody(response.getBody());
                        } catch (IOException ex) {
                            throw failure(isTimeout(ex) ? Kind.TIMEOUT : Kind.INVALID_RESPONSE, status, null, trace);
                        }
                        if (bytes.length > MAX_BODY_BYTES) {
                            throw failure(Kind.RESPONSE_TOO_LARGE, status, null, trace);
                        }
                        if (status != 200) {
                            throw upstreamFailure(status, bytes, trace);
                        }
                        MediaType type = response.getHeaders().getContentType();
                        if (type == null || !"application".equalsIgnoreCase(type.getType())
                                || !"json".equalsIgnoreCase(type.getSubtype())) {
                            throw failure(Kind.INVALID_RESPONSE, status, null, trace);
                        }
                        return readAssessment(bytes, request, trace);
                    });
        } catch (RiskMlException ex) {
            throw ex;
        } catch (ResourceAccessException ex) {
            throw failure(isTimeout(ex) ? Kind.TIMEOUT : Kind.CONNECTION_FAILURE, null, null, null);
        } catch (RestClientException | IllegalArgumentException ex) {
            throw failure(Kind.INVALID_RESPONSE, null, null, null);
        }
    }

    private static Map<String, Object> wireRequest(RiskRequest request) {
        RiskRequest.Features features = request.features();
        return Map.of("hotelId", request.hotelId(), "targetCheckInDate", request.targetCheckInDate().toString(),
                "asOf", request.asOf().toString(), "featureSetVersion", request.featureSetVersion(),
                "features", Map.of("pastFailureRate90d", features.pastFailureRate90d(),
                        "targetConfirmedBookingCount", features.targetConfirmedBookingCount(),
                        "bookingVolumeGrowth7d", features.bookingVolumeGrowth7d(),
                        "reconfirmationOverdueRate7d", features.reconfirmationOverdueRate7d(),
                        "inventoryUnavailableRate7d", features.inventoryUnavailableRate7d(),
                        "isPeakDay", features.isPeakDay()));
    }

    private static byte[] limitedBody(InputStream stream) throws IOException {
        return stream == null ? new byte[0] : stream.readNBytes(MAX_BODY_BYTES + 1);
    }

    private static Assessment readAssessment(byte[] bytes, RiskRequest request, String trace) {
        try {
            Object root = JSON.readValue(bytes, Object.class);
            if (!(root instanceof Map<?, ?> body) || !body.keySet().equals(RESPONSE_FIELDS)) {
                throw failure(Kind.INVALID_RESPONSE, 200, null, trace);
            }
            String hotel = text(body.get("hotelId"));
            String target = text(body.get("targetCheckInDate"));
            String asOf = text(body.get("asOf"));
            String model = text(body.get("modelVersion"));
            String featureVersion = text(body.get("featureSetVersion"));
            Object rawScore = body.get("riskScore");
            if (hotel == null || !request.hotelId().equals(hotel)
                    || !request.targetCheckInDate().toString().equals(target)
                    || asOf == null || !OFFSET_TIMESTAMP.matcher(asOf).matches()
                    || !MODEL_VERSION.equals(model) || !RiskRequest.FEATURE_SET_VERSION.equals(featureVersion)
                    || !(rawScore instanceof Number number)) {
                throw failure(Kind.INVALID_RESPONSE, 200, null, trace);
            }
            double score = number.doubleValue();
            OffsetDateTime observedAt = OffsetDateTime.parse(asOf);
            if (!Double.isFinite(score) || score < 0 || score > 1
                    || !observedAt.toInstant().equals(request.asOf().toInstant())) {
                throw failure(Kind.INVALID_RESPONSE, 200, null, trace);
            }
            return new Assessment(hotel, LocalDate.parse(target), observedAt, score, model, featureVersion);
        } catch (JacksonException | DateTimeParseException ex) {
            throw failure(Kind.INVALID_RESPONSE, 200, null, trace);
        }
    }

    private RiskMlException upstreamFailure(int status, byte[] bytes, String headerTrace) {
        String code = null;
        String trace = headerTrace;
        try {
            Object root = JSON.readValue(bytes, Object.class);
            if (root instanceof Map<?, ?> body && "about:blank".equals(body.get("type"))
                    && body.get("status") instanceof Number number && number.intValue() == status
                    && number.doubleValue() == status) {
                String candidate = text(body.get("code"));
                if (candidate != null && ERROR_CODES.getOrDefault(status, Set.of()).contains(candidate)) {
                    code = candidate;
                }
                String bodyTrace = safeTrace(text(body.get("traceId")));
                if (bodyTrace != null) {
                    trace = headerTrace == null || headerTrace.equals(bodyTrace) ? bodyTrace : null;
                }
            }
        } catch (JacksonException ex) {
            // Preserve only the observed HTTP status and an already-validated header trace.
        }
        Kind kind = switch (status) {
            case 401 -> Kind.AUTHENTICATION;
            case 400, 422 -> Kind.REQUEST_REJECTED;
            case 503 -> Kind.UNAVAILABLE;
            default -> Kind.HTTP_ERROR;
        };
        return failure(kind, status, code, trace);
    }

    private String safeTrace(String value) {
        return value != null && TRACE_ID.matcher(value).matches() && !value.contains(token) ? value : null;
    }

    private static String text(Object value) {
        return value instanceof String string ? string : null;
    }

    private static boolean isTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static RiskMlException failure(Kind kind, Integer status, String code, String trace) {
        return new RiskMlException(kind, status, code, trace);
    }

    public record Assessment(String hotelId, LocalDate targetCheckInDate, OffsetDateTime asOf,
                             double riskScore, String modelVersion, String featureSetVersion) {}
}
