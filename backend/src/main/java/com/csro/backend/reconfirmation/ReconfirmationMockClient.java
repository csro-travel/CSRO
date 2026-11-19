package com.csro.backend.reconfirmation;

import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import com.csro.backend.reconfirmation.BookingReconfirmationException.Kind;
import com.csro.backend.reconfirmation.BookingReconfirmationException.Step;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.EvidenceBody;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.HotelOutcome;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.HotelPending;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.HotelReply;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.SupplierBooking;

@Component
class ReconfirmationMockClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient client;
    private final String token;

    @Autowired
    ReconfirmationMockClient(
            @Value("${booking.reconfirmation.mock.base-url:http://127.0.0.1:8001}") String baseUrl,
            @Value("${booking.reconfirmation.mock.service-token:}") String token
    ) {
        this(RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory()).build(), token);
    }

    ReconfirmationMockClient(RestClient client, String token) {
        this.client = client;
        this.token = token;
    }

    private static JdkClientHttpRequestFactory requestFactory() {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));
        return factory;
    }

    Received<SupplierBooking> getSupplierBooking(String supplierBookingId) {
        return exchange(Step.API_026,
                client.get().uri("/mock-supplier/v1/bookings/{supplierBookingId}", supplierBookingId),
                (status, response, localMock) -> {
                    if (status != 200) {
                        throw failure(Step.API_026, status, response);
                    }
                    return read(Step.API_026, status, response, SupplierBooking.class, localMock, null);
                });
    }

    Received<HotelOutcome> reconfirm(HotelRequest request) {
        byte[] requestBytes = JSON.writeValueAsBytes(request);
        String requestJson = new String(requestBytes, StandardCharsets.UTF_8);
        return exchange(Step.API_027,
                client.post().uri("/mock-hotel/v1/reconfirmations")
                        .contentType(MediaType.APPLICATION_JSON).body(requestBytes),
                (status, response, localMock) -> switch (status) {
                    case 200 -> read(Step.API_027, status, response, HotelReply.class, localMock, requestJson);
                    case 202 -> read(Step.API_027, status, response, HotelPending.class, localMock, requestJson);
                    default -> throw failure(Step.API_027, status, response);
                });
    }

    private <T> Received<T> exchange(Step step, RestClient.RequestHeadersSpec<?> request, ReplyReader<T> reader) {
        if (!StringUtils.hasText(token)) {
            throw new BookingReconfirmationException(step, Kind.NO_SERVICE_TOKEN, null, null, null);
        }
        try {
            return request.headers(h -> h.setBearerAuth(token))
                    .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)
                    .exchange((sent, response) -> {
                        String host = sent.getURI().getHost();
                        boolean localMock = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)
                                || "[::1]".equals(host);
                        return reader.read(response.getStatusCode().value(), response, localMock);
                    });
        } catch (ResourceAccessException ex) {
            Kind kind = isTimeout(ex) ? Kind.TIMEOUT : Kind.CONNECTION_FAILURE;
            throw new BookingReconfirmationException(step, kind, null, null, null);
        }
    }

    private static <T> Received<T> read(Step step, int status,
                                       RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response,
                                       Class<? extends T> type, boolean localMock, String requestJson) {
        try {
            byte[] bytes = response.bodyTo(byte[].class);
            OffsetDateTime receivedAt = OffsetDateTime.now(ZoneOffset.UTC);
            T body = bytes == null ? null : JSON.readValue(bytes, type);
            if (body != null) {
                return new Received<>(body, new EvidenceBody(new String(bytes, StandardCharsets.UTF_8),
                        receivedAt, localMock, requestJson));
            }
        } catch (RestClientException | JacksonException ex) {
            throw new BookingReconfirmationException(step, isTimeout(ex) ? Kind.TIMEOUT : Kind.INVALID_RESPONSE,
                    status, null, response.getHeaders().getFirst("X-Request-ID"));
        }
        throw new BookingReconfirmationException(step, Kind.INVALID_RESPONSE, status, null,
                response.getHeaders().getFirst("X-Request-ID"));
    }

    private static BookingReconfirmationException failure(
            Step step, int status, RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response
    ) {
        Kind kind = switch (status) {
            case 401 -> Kind.AUTHENTICATION;
            case 404 -> Kind.NOT_FOUND;
            case 422 -> Kind.REQUEST_MISMATCH;
            case 503 -> Kind.UNAVAILABLE;
            default -> Kind.HTTP_ERROR;
        };
        String traceId = response.getHeaders().getFirst("X-Request-ID");
        Problem problem;
        try {
            problem = response.bodyTo(Problem.class);
        } catch (RestClientException ex) {
            // Preserve the observed status when the upstream error body is not decodable.
            return new BookingReconfirmationException(step, isTimeout(ex) ? Kind.TIMEOUT : kind,
                    status, null, traceId);
        }
        return new BookingReconfirmationException(step, kind, status, problem == null ? null : problem.code(),
                problem == null || problem.traceId() == null ? traceId : problem.traceId());
    }

    private static boolean isTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    @FunctionalInterface
    private interface ReplyReader<T> {
        Received<T> read(int status, RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response,
                         boolean localMock);
    }

    record Received<T>(T body, EvidenceBody evidence) {}

    record HotelRequest(String reconfirmationRequestId, String supplierBookingId,
                        String propertyId, List<String> stayDates) {}

    private record Problem(String type, String title, int status, String detail, String code, String traceId) {}
}
