package com.csro.backend.quotes;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.csro.backend.quotes.QuoteIntegrationException.Kind;
import com.csro.backend.quotes.QuoteIntegrationException.Step;

/** Shared bounded transport for the two existing quote contracts; exactly one HTTP call, no retries. */
final class QuoteHttpTransport {
    static final int MAX_BODY_BYTES = 256 * 1024;
    private final RestClient client;
    private final String token;
    private final Step step;

    QuoteHttpTransport(RestClient client, String token, Step step) {
        this.client = client; this.token = token; this.step = step;
    }

    static RestClient restClient(String baseUrl) {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    <T> T post(String path, Object wire, Function<Reply, T> decode) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw failure(Kind.TRANSACTION_ACTIVE, null, null, null);
        if (token == null || token.isBlank()) throw failure(Kind.NO_SERVICE_TOKEN, null, null, null);
        String requestJson = QuoteContracts.JSON.writeValueAsString(wire);
        try {
            return client.post().uri(path).headers(headers -> headers.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)
                    .body(requestJson.getBytes(StandardCharsets.UTF_8)).exchange((sent, response) -> {
                        int status = response.getStatusCode().value();
                        String trace = safeTrace(response.getHeaders().getFirst("X-Request-ID"));
                        byte[] bytes;
                        try {
                            bytes = response.getBody() == null ? new byte[0] : response.getBody().readNBytes(MAX_BODY_BYTES + 1);
                        } catch (IOException error) {
                            throw failure(timeout(error) ? Kind.TIMEOUT : Kind.INVALID_RESPONSE, status, null, trace);
                        }
                        if (bytes.length > MAX_BODY_BYTES) throw failure(Kind.RESPONSE_TOO_LARGE, status, null, trace);
                        if (status != 200) throw upstream(status, bytes, trace);
                        MediaType type = response.getHeaders().getContentType();
                        if (type == null || !"application".equalsIgnoreCase(type.getType()) || !"json".equalsIgnoreCase(type.getSubtype()))
                            throw failure(Kind.INVALID_RESPONSE, status, null, trace);
                        try {
                            return decode.apply(new Reply(bytes, requestJson));
                        } catch (QuoteIntegrationException error) {
                            throw error;
                        } catch (RuntimeException error) {
                            throw failure(Kind.INVALID_RESPONSE, status, null, trace);
                        }
                    });
        } catch (QuoteIntegrationException error) {
            throw error;
        } catch (ResourceAccessException error) {
            throw failure(timeout(error) ? Kind.TIMEOUT : Kind.CONNECTION_FAILURE, null, null, null);
        } catch (RestClientException | IllegalArgumentException error) {
            throw failure(Kind.INVALID_RESPONSE, null, null, null);
        }
    }

    QuoteIntegrationException invalidRequest() { return failure(Kind.INVALID_REQUEST, null, null, null); }

    private QuoteIntegrationException upstream(int status, byte[] bytes, String headerTrace) {
        String code = null;
        String trace = headerTrace;
        try {
            Object root = QuoteContracts.JSON.readValue(bytes, Object.class);
            if (root instanceof Map<?, ?> body && "about:blank".equals(body.get("type"))
                    && QuoteContracts.amount(body.get("status")) == status) {
                Object candidate = body.get("code");
                if (candidate instanceof String value && knownCodes(status).contains(value)) code = value;
                String bodyTrace = body.get("traceId") instanceof String value ? safeTrace(value) : null;
                if (bodyTrace != null) trace = headerTrace == null || headerTrace.equals(bodyTrace) ? bodyTrace : null;
            }
        } catch (RuntimeException invalidBody) {
            // Retain the observed HTTP failure without propagating untrusted error text.
        }
        Kind kind = switch (status) {
            case 401 -> Kind.AUTHENTICATION;
            case 400, 422 -> Kind.REQUEST_REJECTED;
            case 503 -> Kind.UNAVAILABLE;
            default -> Kind.HTTP_ERROR;
        };
        return failure(kind, status, code, trace);
    }

    private Set<String> knownCodes(int status) {
        return switch (status) {
            case 400 -> Set.of(step == Step.API_028 ? "INVALID_SEARCH_REQUEST" : "INVALID_RANKING_REQUEST");
            case 401 -> Set.of("UNAUTHENTICATED_SERVICE");
            case 422 -> step == Step.API_028 ? Set.of("INVALID_STAY_DATES", "INVALID_OCCUPANCY")
                    : Set.of("QUOTE_STAY_MISMATCH", "CURRENCY_MISMATCH");
            case 503 -> Set.of(step == Step.API_028 ? "SUPPLIER_UNAVAILABLE" : "AGENT_UNAVAILABLE");
            case 500 -> Set.of("INTERNAL_ERROR");
            default -> Set.of();
        };
    }

    private String safeTrace(String value) {
        return value != null && value.matches("[A-Za-z0-9-]{1,100}") && !value.contains(token) ? value : null;
    }

    private QuoteIntegrationException failure(Kind kind, Integer status, String code, String trace) {
        return new QuoteIntegrationException(step, kind, status, code, trace);
    }

    private static boolean timeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) return true;
        return false;
    }

    record Reply(byte[] bytes, String requestJson) {}
}
