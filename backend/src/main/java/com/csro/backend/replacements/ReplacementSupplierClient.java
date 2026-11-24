package com.csro.backend.replacements;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.csro.backend.replacements.ReplacementSupplierException.Kind;

/**
 * Strict API-029/030 boundary. One invocation makes one HTTP request and never retries.
 * Only an authenticated, contract-shaped rejection establishes non-creation; every
 * other error remains uncertain for the durable worker to reconcile using the same BRQ.
 */
@Component
public class ReplacementSupplierClient {
    static final int MAX_BODY_BYTES = 256 * 1024;
    private static final String CREATE_PATH = "/mock-supplier/v1/replacement-bookings";
    private static final String LOOKUP_PATH = "/mock-supplier/v1/replacement-booking-requests/{requestId}";
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> COMMON = Set.of("requestId", "status", "supplierBookingId", "quoteId",
            "propertyId", "confirmedPrice", "confirmedAt");
    private static final Set<String> FAILURE_CODES = Set.of("QUOTE_NOT_FOUND", "PRICE_CHANGED", "OUT_OF_STOCK",
            "QUOTE_EXPIRED", "QUOTE_ALREADY_BOOKED", "QUOTE_STAY_MISMATCH");
    private static final Set<String> PROBLEM_FIELDS = Set.of("type", "title", "status", "detail", "code", "traceId");
    private final RestClient http;
    private final String token;

    @Autowired
    public ReplacementSupplierClient(Environment environment) {
        this(restClient(environment.getProperty("booking.reconfirmation.mock.base-url", "http://127.0.0.1:8001")),
                environment.getProperty("booking.reconfirmation.mock.service-token", ""));
    }

    public ReplacementSupplierClient(RestClient http, String token) {
        this.http = http;
        this.token = token;
    }

    public Outcome create(FrozenRequest request) { return exchange(request, true); }
    public Outcome lookup(FrozenRequest request) { return exchange(request, false); }

    private Outcome exchange(FrozenRequest request, boolean create) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw failure(Kind.TRANSACTION_ACTIVE, null, null);
        if (token == null || token.isBlank()) throw failure(Kind.NO_SERVICE_TOKEN, null, null);
        validate(request);
        try {
            var sent = http.method(create ? HttpMethod.POST : HttpMethod.GET)
                    .uri(create ? CREATE_PATH : LOOKUP_PATH, request.requestId())
                    .headers(headers -> headers.setBearerAuth(token))
                    .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON);
            if (create) {
                sent.contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", request.requestId())
                        .body(wireJson(request).getBytes(StandardCharsets.UTF_8));
            }
            return sent.exchange((outgoing, response) -> {
                int status = response.getStatusCode().value();
                byte[] bytes;
                try {
                    bytes = response.getBody() == null ? new byte[0] : response.getBody().readNBytes(MAX_BODY_BYTES + 1);
                } catch (IOException unreadable) {
                    throw failure(timeout(unreadable) ? Kind.TIMEOUT : Kind.UNKNOWN, status, null);
                }
                if (bytes.length > MAX_BODY_BYTES) throw failure(Kind.RESPONSE_TOO_LARGE, status, null);
                try {
                    JsonNode body = JSON.readTree(bytes);
                    if (body == null || !body.isObject()) throw malformed();
                    MediaType type = response.getHeaders().getContentType();
                    if (status == (create ? 201 : 200)) {
                        require(isType(type, MediaType.APPLICATION_JSON));
                        return readOutcome(body, request, create, new String(bytes, StandardCharsets.UTF_8));
                    }
                    require(isType(type, MediaType.APPLICATION_PROBLEM_JSON));
                    return readProblem(body, request, create, status, new String(bytes, StandardCharsets.UTF_8));
                } catch (ReplacementSupplierException safe) {
                    throw safe;
                } catch (RuntimeException invalid) {
                    throw failure(Kind.UNKNOWN, status, null);
                }
            });
        } catch (ReplacementSupplierException safe) {
            throw safe;
        } catch (ResourceAccessException unavailable) {
            throw failure(timeout(unavailable) ? Kind.TIMEOUT : Kind.CONNECTION_FAILURE, null, null);
        } catch (RestClientException | IllegalArgumentException invalid) {
            throw failure(Kind.UNKNOWN, null, null);
        }
    }

    /** Exact outbound JSON is also available for the immutable business request snapshot. */
    public static String wireJson(FrozenRequest request) {
        validate(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("quoteId", request.quoteId());
        body.put("expectedTotalAmount", request.expectedAmount());
        body.put("currency", request.currency());
        body.put("guestReference", request.guestReference());
        return JSON.writeValueAsString(body);
    }

    private Outcome readOutcome(JsonNode body, FrozenRequest request, boolean create, String raw) {
        String status = text(body, "status", 20);
        Set<String> fields = new HashSet<>(COMMON);
        if (create) fields.add("stay");
        if ("FAILED".equals(status)) {
            fields.add("failureCode"); fields.add("failedAt");
            if (body.has("currentTotalAmount") || body.has("currency")) {
                fields.add("currentTotalAmount"); fields.add("currency");
            }
        }
        exact(body, fields);
        require(text(body, "requestId", 100).equals(request.requestId())
                && text(body, "quoteId", 80).equals(request.quoteId())
                && text(body, "propertyId", 40).equals(request.propertyId()));
        if (create) {
            require("CONFIRMED".equals(status));
            exact(body.path("stay"), Set.of("checkInDate", "checkOutDate", "adults", "rooms"));
            JsonNode stay = body.path("stay");
            require(date(stay, "checkInDate").equals(request.stay().checkInDate())
                    && date(stay, "checkOutDate").equals(request.stay().checkOutDate())
                    && amount(stay, "adults") == request.stay().adults()
                    && amount(stay, "rooms") == request.stay().rooms());
        }
        if ("CONFIRMED".equals(status)) {
            String booking = text(body, "supplierBookingId", 80);
            JsonNode price = body.path("confirmedPrice");
            exact(price, Set.of("totalAmount", "currency"));
            require(amount(price, "totalAmount") == request.expectedAmount()
                    && text(price, "currency", 3).equals(request.currency()));
            return new Outcome(status, booking, request.expectedAmount(), time(body, "confirmedAt"),
                    null, null, null, raw);
        }
        require(!create && ("PROCESSING".equals(status) || "FAILED".equals(status)));
        requireNull(body, "supplierBookingId", "confirmedPrice", "confirmedAt");
        if ("PROCESSING".equals(status)) return new Outcome(status, null, null, null, null, null, null, raw);
        String code = text(body, "failureCode", 60);
        require(FAILURE_CODES.contains(code));
        Long current = changedPrice(body, code, request.expectedAmount());
        return new Outcome("FAILED", null, null, null, code, current, time(body, "failedAt"), raw);
    }

    private Outcome readProblem(JsonNode body, FrozenRequest request, boolean create, int status, String raw) {
        Set<String> fields = new HashSet<>(PROBLEM_FIELDS);
        if (body.has("currentTotalAmount") || body.has("currency")) {
            fields.add("currentTotalAmount"); fields.add("currency");
        }
        exact(body, fields);
        require("about:blank".equals(text(body, "type", 100)) && amount(body, "status") == status);
        text(body, "title", 200); text(body, "detail", 2000);
        String trace = text(body, "traceId", 100);
        require(trace.matches("[A-Za-z0-9._:-]{1,100}"));
        String code = text(body, "code", 60);
        Long current = changedPrice(body, code, request.expectedAmount());
        if (!create && status == 404 && "BOOKING_REQUEST_NOT_FOUND".equals(code))
            throw failure(Kind.REQUEST_NOT_FOUND, status, code);
        boolean rejected = create && switch (status) {
            case 404 -> "QUOTE_NOT_FOUND".equals(code);
            case 409 -> Set.of("PRICE_CHANGED", "OUT_OF_STOCK", "QUOTE_EXPIRED", "QUOTE_ALREADY_BOOKED").contains(code);
            case 422 -> "QUOTE_STAY_MISMATCH".equals(code);
            default -> false;
        };
        if (!rejected) throw failure(Kind.UNKNOWN, status, knownCode(code) ? code : null);
        // API-029 ProblemDetail has no supplier failure time. Do not invent one.
        return new Outcome("FAILED", null, null, null, code, current, null, raw);
    }

    private static Long changedPrice(JsonNode body, String code, long expectedAmount) {
        boolean included = body.has("currentTotalAmount") || body.has("currency");
        if (!included) return null;
        require("PRICE_CHANGED".equals(code));
        long current = amount(body, "currentTotalAmount");
        require(current != expectedAmount && "KRW".equals(text(body, "currency", 3)));
        return current;
    }

    private static boolean knownCode(String code) {
        return FAILURE_CODES.contains(code) || Set.of("BOOKING_REQUEST_NOT_FOUND", "IDEMPOTENCY_KEY_REUSED",
                "UNAUTHENTICATED_SERVICE", "INVALID_BOOKING_REQUEST", "SUPPLIER_UNAVAILABLE", "INTERNAL_ERROR").contains(code);
    }

    private static RestClient restClient(String baseUrl) {
        HttpClient transport = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    private static void validate(FrozenRequest request) {
        if (request == null || !safeId(request.requestId(), 80) || !safeId(request.quoteId(), 80)
                || !safeId(request.propertyId(), 40) || !safeId(request.guestReference(), 80)
                || request.expectedAmount() < 0 || !"KRW".equals(request.currency()) || request.stay() == null
                || request.stay().checkInDate() == null || request.stay().checkOutDate() == null
                || !request.stay().checkOutDate().isAfter(request.stay().checkInDate())
                || request.stay().adults() < 1 || request.stay().rooms() < 1)
            throw failure(Kind.INVALID_REQUEST, null, null);
    }

    private static boolean safeId(String value, int maximum) {
        return value != null && value.length() <= maximum && value.matches("[A-Za-z0-9._:-]+") && !value.isBlank();
    }
    private static boolean isType(MediaType supplied, MediaType required) {
        return supplied != null && required.getType().equalsIgnoreCase(supplied.getType())
                && required.getSubtype().equalsIgnoreCase(supplied.getSubtype());
    }
    private static void exact(JsonNode body, Set<String> fields) {
        require(body.isObject() && body.size() == fields.size());
        for (String field : body.propertyNames()) require(fields.contains(field));
    }
    private static String text(JsonNode body, String field, int maximum) {
        JsonNode value = body.path(field);
        require(value.isString());
        String text = value.stringValue();
        require(!text.isBlank() && text.length() <= maximum && text.chars().noneMatch(Character::isISOControl));
        return text;
    }
    private static long amount(JsonNode body, String field) {
        JsonNode value = body.path(field);
        require(value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0);
        return value.longValue();
    }
    private static LocalDate date(JsonNode body, String field) { return LocalDate.parse(text(body, field, 10)); }
    private static OffsetDateTime time(JsonNode body, String field) {
        String value = text(body, field, 64);
        require(value.matches(".*(?:Z|[+-][0-9]{2}:[0-9]{2})$"));
        return OffsetDateTime.parse(value);
    }
    private static void requireNull(JsonNode body, String... fields) {
        for (String field : fields) require(body.has(field) && body.get(field).isNull());
    }
    private static void require(boolean valid) { if (!valid) throw malformed(); }
    private static IllegalArgumentException malformed() { return new IllegalArgumentException("Invalid supplier contract"); }
    private static boolean timeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) return true;
        return false;
    }
    private static ReplacementSupplierException failure(Kind kind, Integer status, String code) {
        return new ReplacementSupplierException(kind, status, code);
    }

    public record Stay(LocalDate checkInDate, LocalDate checkOutDate, int adults, int rooms) {}
    public record FrozenRequest(String requestId, String quoteId, String propertyId, Stay stay,
                                long expectedAmount, String currency, String guestReference) {}
    public record Outcome(String status, String supplierBookingId, Long confirmedAmount, OffsetDateTime confirmedAt,
                          String failureCode, Long currentAmount, OffsetDateTime failedAt, String rawJson) {}
}
