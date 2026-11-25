package com.csro.backend.hotelanalysis;

import static com.csro.backend.hotelanalysis.HotelReplyAnalysisContracts.*;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** One bounded API-024 call. Valid results remain unverified hotel claims, never business commands. */
@Component
public class HotelReplyAnalysisClient {
    private static final String PATH = "/internal/v1/hotel-reply-extractions";
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final Set<String> REQUEST_FIELDS = Set.of("evidenceId", "bookingId", "hotelId", "stayDates",
            "originalMessage", "locale", "messageOccurredAt", "hotelTimeZone");
    private static final Set<String> RESPONSE_FIELDS = Set.of("evidenceId", "analysisStatus", "modelVersion", "claims",
            "unresolvedStayDates", "requiresHumanVerification", "warnings");
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final String baseUrl;
    private final String token;
    private volatile RestClient client;

    /** Configuration is checked only when explicitly invoking analysis; starting Spring does not call the Agent. */
    @Autowired
    public HotelReplyAnalysisClient(
            @Value("${hotel.analysis.agent.base-url:http://127.0.0.1:8000}") String baseUrl,
            @Value("${hotel.analysis.agent.service-token:}") String token) {
        this.baseUrl = baseUrl;
        this.token = token;
    }

    HotelReplyAnalysisClient(RestClient client, String token) {
        this.baseUrl = null;
        this.client = client;
        this.token = token;
    }

    public Analysis analyze(Input input) {
        String request = requestJson(input);
        if (token == null || token.isBlank()
                || token.codePoints().anyMatch(value -> value < 33 || value > 126)) {
            throw failure("AGENT_NOT_CONFIGURED", null);
        }
        RestClient http = transport();
        try {
            return http.post().uri(PATH).headers(headers -> headers.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)
                    .body(request.getBytes(StandardCharsets.UTF_8)).exchange((sent, response) -> {
                        int status = response.getStatusCode().value();
                        if (status != 200) throw upstream(status);
                        MediaType type;
                        try {
                            type = response.getHeaders().getContentType();
                        } catch (IllegalArgumentException invalid) {
                            throw failure("INVALID_AGENT_RESPONSE", status);
                        }
                        if (type == null || !"application".equalsIgnoreCase(type.getType())
                                || !"json".equalsIgnoreCase(type.getSubtype())) {
                            throw failure("INVALID_AGENT_RESPONSE", status);
                        }
                        byte[] bytes;
                        try {
                            var stream = response.getBody();
                            bytes = stream == null ? new byte[0] : stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                        } catch (IOException invalid) {
                            throw failure(isTimeout(invalid) ? "AGENT_TIMEOUT" : "INVALID_AGENT_RESPONSE", status);
                        }
                        if (bytes.length > MAX_RESPONSE_BYTES) throw failure("INVALID_AGENT_RESPONSE", status);
                        return readAnalysis(bytes, input, request);
                    });
        } catch (HotelReplyAnalysisException safe) {
            throw safe;
        } catch (ResourceAccessException inaccessible) {
            throw failure(isTimeout(inaccessible) ? "AGENT_TIMEOUT" : "AGENT_CONNECTION_FAILED", null);
        } catch (RestClientException | IllegalArgumentException invalid) {
            throw failure("INVALID_AGENT_RESPONSE", null);
        }
    }

    private RestClient transport() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    try {
                        var uri = URI.create(baseUrl);
                        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                                || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                                || uri.getRawFragment() != null || uri.getPort() > 65535) {
                            throw new IllegalArgumentException();
                        }
                        var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                                .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
                        var factory = new JdkClientHttpRequestFactory(http);
                        factory.setReadTimeout(Duration.ofSeconds(5));
                        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
                    } catch (IllegalArgumentException | NullPointerException invalid) {
                        throw failure("AGENT_NOT_CONFIGURED", null);
                    }
                }
            }
        }
        return client;
    }

    /** Canonical eight-field snapshot, including an explicit null occurrence time. Source text is unchanged. */
    public static String requestJson(Input input) {
        validateInput(input);
        return JSON.writeValueAsString(wireInput(input));
    }

    /** Rechecks snapshots against typed values before storage, including internal/test callers. No HTTP occurs here. */
    public static void validateAnalysis(Input input, Analysis analysis) {
        validateInput(input);
        if (analysis == null || analysis.requestJson() == null || analysis.responseJson() == null) {
            throw failure("INVALID_AGENT_RESPONSE", null);
        }
        try {
            Object request = JSON.readValue(analysis.requestJson(), Object.class);
            if (!(request instanceof Map<?, ?> map) || !map.keySet().equals(REQUEST_FIELDS)
                    || !wireInput(input).equals(map)) throw failure("INVALID_AGENT_RESPONSE", null);
            byte[] bytes = analysis.responseJson().getBytes(StandardCharsets.UTF_8);
            Analysis parsed = readAnalysis(bytes, input, analysis.requestJson());
            if (!parsed.equals(analysis)) throw failure("INVALID_AGENT_RESPONSE", null);
        } catch (JacksonException | IllegalArgumentException invalid) {
            throw failure("INVALID_AGENT_RESPONSE", null);
        }
    }

    private static Map<String, Object> wireInput(Input input) {
        var body = new LinkedHashMap<String, Object>();
        body.put("evidenceId", input.evidenceId());
        body.put("bookingId", input.bookingId());
        body.put("hotelId", input.hotelId());
        body.put("stayDates", input.stayDates().stream().map(LocalDate::toString).toList());
        body.put("originalMessage", input.originalMessage());
        body.put("locale", input.locale());
        body.put("messageOccurredAt", input.messageOccurredAt() == null ? null : input.messageOccurredAt().toString());
        body.put("hotelTimeZone", input.hotelTimeZone());
        return body;
    }

    private static void validateInput(Input input) {
        if (input == null || !nonblank(input.evidenceId()) || !nonblank(input.bookingId())
                || !nonblank(input.hotelId()) || input.stayDates() == null || input.stayDates().isEmpty()
                || input.stayDates().stream().anyMatch(day -> day == null || day.getYear() < 1 || day.getYear() > 9999)
                || new HashSet<>(input.stayDates()).size() != input.stayDates().size()
                || !nonblank(input.originalMessage())
                || input.originalMessage().codePointCount(0, input.originalMessage().length()) > 10_000
                || !"ko-KR".equals(input.locale()) || input.hotelTimeZone() == null
                || !ZoneId.getAvailableZoneIds().contains(input.hotelTimeZone())
                || (input.messageOccurredAt() != null && (input.messageOccurredAt().getYear() < 1
                    || input.messageOccurredAt().getYear() > 9999
                    || input.messageOccurredAt().getOffset().getTotalSeconds() % 60 != 0))) {
            throw failure("INVALID_ANALYSIS_REQUEST", null);
        }
    }

    private static Analysis readAnalysis(byte[] bytes, Input input, String request) {
        try {
            if (bytes == null || bytes.length > MAX_RESPONSE_BYTES) throw failure("INVALID_AGENT_RESPONSE", 200);
            String raw = strictUtf8(bytes);
            Object root = JSON.readValue(raw, Object.class);
            if (!(root instanceof Map<?, ?> body) || !body.keySet().containsAll(RESPONSE_FIELDS)
                    || !safeJsonStrings(body) || !input.evidenceId().equals(body.get("evidenceId"))
                    || !Boolean.TRUE.equals(body.get("requiresHumanVerification"))) {
                throw failure("INVALID_AGENT_RESPONSE", 200);
            }
            String status = text(body.get("analysisStatus"));
            String model = text(body.get("modelVersion"));
            if (!("EXTRACTED".equals(status) || "AMBIGUOUS".equals(status)) || !nonblank(model)
                    || model.codePointCount(0, model.length()) > 80
                    || !(body.get("claims") instanceof List<?> claimRows)
                    || !(body.get("unresolvedStayDates") instanceof List<?> unresolvedRows)
                    || !(body.get("warnings") instanceof List<?> warningRows)) {
                throw failure("INVALID_AGENT_RESPONSE", 200);
            }
            var allowed = new HashSet<>(input.stayDates());
            var covered = new HashSet<LocalDate>();
            var claims = new ArrayList<Claim>();
            for (Object row : claimRows) {
                if (!(row instanceof Map<?, ?> claim)) throw failure("INVALID_AGENT_RESPONSE", 200);
                LocalDate day = date(claim.get("stayDate"));
                String provision = text(claim.get("reportedProvisionStatus"));
                String quote = text(claim.get("supportingText"));
                if (!allowed.contains(day) || !covered.add(day)
                        || !("AVAILABLE".equals(provision) || "UNAVAILABLE".equals(provision))
                        || !nonblank(quote) || !input.originalMessage().contains(quote)) {
                    throw failure("INVALID_AGENT_RESPONSE", 200);
                }
                claims.add(new Claim(day, provision, quote));
            }
            var unresolved = new ArrayList<LocalDate>();
            for (Object row : unresolvedRows) {
                LocalDate day = date(row);
                if (!allowed.contains(day) || !covered.add(day)) throw failure("INVALID_AGENT_RESPONSE", 200);
                unresolved.add(day);
            }
            var warnings = new ArrayList<String>();
            for (Object row : warningRows) {
                if (!(row instanceof String warning)) throw failure("INVALID_AGENT_RESPONSE", 200);
                warnings.add(warning);
            }
            if (!covered.equals(allowed) || claims.isEmpty() == "EXTRACTED".equals(status)) {
                throw failure("INVALID_AGENT_RESPONSE", 200);
            }
            return new Analysis(input.evidenceId(), status, model, claims, unresolved, true, warnings, request, raw);
        } catch (JacksonException | CharacterCodingException | DateTimeException | IllegalArgumentException invalid) {
            throw failure("INVALID_AGENT_RESPONSE", 200);
        }
    }

    private static LocalDate date(Object value) {
        if (!(value instanceof String date) || !date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw new IllegalArgumentException();
        }
        LocalDate parsed = LocalDate.parse(date);
        if (parsed.getYear() < 1) throw new IllegalArgumentException();
        return parsed;
    }

    private static String text(Object value) {
        return value instanceof String text ? text : null;
    }

    private static boolean nonblank(String value) {
        return value != null && safeText(value)
                && value.codePoints().anyMatch(point -> !Character.isWhitespace(point) && !Character.isSpaceChar(point));
    }

    private static boolean safeText(String value) {
        if (value.indexOf(0) >= 0) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (++index == value.length() || !Character.isLowSurrogate(value.charAt(index))) return false;
            } else if (Character.isLowSurrogate(character)) return false;
        }
        return true;
    }

    /** Unknown response fields remain in the snapshot; only their representability in PostgreSQL JSON is checked. */
    private static boolean safeJsonStrings(Object value) {
        if (value instanceof String text) return safeText(text);
        if (value instanceof List<?> list) return list.stream().allMatch(HotelReplyAnalysisClient::safeJsonStrings);
        if (value instanceof Map<?, ?> map) return map.entrySet().stream()
                .allMatch(entry -> safeText((String) entry.getKey()) && safeJsonStrings(entry.getValue()));
        return true;
    }

    private static String strictUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static HotelReplyAnalysisException upstream(int status) {
        return failure(switch (status) {
            case 400, 422 -> "INVALID_ANALYSIS_REQUEST";
            case 401 -> "AGENT_UNAUTHENTICATED";
            case 503 -> "AGENT_UNAVAILABLE";
            default -> "AGENT_HTTP_ERROR";
        }, status);
    }

    private static boolean isTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) return true;
        }
        return false;
    }

    private static HotelReplyAnalysisException failure(String code, Integer status) {
        return new HotelReplyAnalysisException(code, status);
    }
}
