package com.csro.backend.assist;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 상담사 안내 초안 Agent 호출 한 번. 재시도하지 않으며 결과는 제안일 뿐 업무 명령이 아니다. */
@Component
public class AssistAgentClient {
    static final String PATH = "/internal/v1/counselor-assist";
    static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final int MAX_BODY_BYTES = 256 * 1024;
    private final RestClient client;
    private final String token;

    /** 견적 비교와 같은 Agent 서버를 쓰므로 기존 설정을 그대로 읽는다. 초안 생성이 길 수 있어 읽기 제한만 25초다. */
    @Autowired
    public AssistAgentClient(
            @Value("${quote.ranking.agent.base-url:http://127.0.0.1:8000}") String baseUrl,
            @Value("${quote.ranking.agent.service-token:}") String token) {
        this(restClient(baseUrl), token);
    }

    AssistAgentClient(RestClient client, String token) {
        this.client = client;
        this.token = token;
    }

    private static RestClient restClient(String baseUrl) {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(25));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** factsJson은 Spring이 업무 DB에서 만든 구조화된 사실이다. 고객·호텔 원문은 담지 않는다. */
    public Draft generate(String caseId, String kind, String factsJson) {
        if (TransactionSynchronizationManager.isActualTransactionActive() || token == null || token.isBlank())
            throw new Unavailable();
        try {
            var wire = new LinkedHashMap<String, Object>();
            wire.put("caseId", caseId);
            wire.put("kind", kind);
            wire.put("facts", JSON.readValue(factsJson, Object.class));
            byte[] request = JSON.writeValueAsString(wire).getBytes(StandardCharsets.UTF_8);
            return client.post().uri(PATH).headers(headers -> headers.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)
                    .body(request).exchange((sent, response) -> {
                        MediaType type = response.getHeaders().getContentType();
                        if (response.getStatusCode().value() != 200 || type == null
                                || !"application".equalsIgnoreCase(type.getType()) || !"json".equalsIgnoreCase(type.getSubtype()))
                            throw new Unavailable();
                        byte[] bytes;
                        try {
                            bytes = response.getBody() == null ? new byte[0] : response.getBody().readNBytes(MAX_BODY_BYTES + 1);
                        } catch (IOException unreadable) {
                            throw new Unavailable();
                        }
                        if (bytes.length > MAX_BODY_BYTES) throw new Unavailable();
                        return read(bytes, caseId, kind);
                    });
        } catch (Unavailable safe) {
            throw safe;
        } catch (RuntimeException failure) {
            // 응답 본문·토큰·예외 원문을 남기지 않는다.
            throw new Unavailable();
        }
    }

    private static Draft read(byte[] bytes, String caseId, String kind) {
        if (!(JSON.readValue(bytes, Object.class) instanceof Map<?, ?> body)
                || !caseId.equals(body.get("caseId")) || !kind.equals(body.get("kind"))) throw new Unavailable();
        Object source = body.get("source");
        Object model = body.get("model");
        Object draft = body.get("customerMessageDraft");
        boolean llm = "LLM".equals(source);
        if (!(llm || "RULES".equals(source)) || !body.containsKey("model") || !body.containsKey("customerMessageDraft")
                || (llm ? !(model instanceof String) : model != null)) throw new Unavailable();
        return new Draft((String) source, llm ? text(model, 80) : null, text(body.get("summary"), 400),
                texts(body.get("nextActions"), 5, 200), draft == null ? null : text(draft, 800),
                texts(body.get("warnings"), 10, 300));
    }

    private static String text(Object value, int limit) {
        if (!(value instanceof String text) || text.isBlank() || text.indexOf(0) >= 0
                || text.codePointCount(0, text.length()) > limit) throw new Unavailable();
        return text;
    }

    private static List<String> texts(Object value, int maxItems, int limit) {
        if (!(value instanceof List<?> rows) || rows.size() > maxItems) throw new Unavailable();
        var items = new ArrayList<String>();
        for (Object row : rows) items.add(text(row, limit));
        return List.copyOf(items);
    }

    public record Draft(String source, String model, String summary, List<String> nextActions,
            String customerMessageDraft, List<String> warnings) {}

    /** Agent에 연결할 수 없거나 응답이 계약과 다를 때. 원인·응답 본문은 보존하지 않는다. */
    public static final class Unavailable extends ResponseStatusException {
        Unavailable() {
            super(HttpStatus.SERVICE_UNAVAILABLE, "ASSIST_UNAVAILABLE");
        }
    }
}
