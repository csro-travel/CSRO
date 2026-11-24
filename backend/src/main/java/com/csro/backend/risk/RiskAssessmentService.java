package com.csro.backend.risk;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.*;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

@Service
public class RiskAssessmentService {
    private final Optional<RiskObservations> observations;
    private final RiskAssessmentStore store;
    private final RestClient ml;
    private final String token;
    private final String modelVersion;

    public RiskAssessmentService(Optional<RiskObservations> observations, RiskAssessmentStore store,
            @Value("${ml.risk.base-url:http://127.0.0.1:8002}") String baseUrl,
            @Value("${ml.risk.service-token:}") String token,
            @Value("${ml.risk.model-version:process-synthetic-lightgbm-0.2-84d386281d22}") String modelVersion) {
        this.observations = observations;
        this.store = store;
        this.token = token;
        this.modelVersion = modelVersion;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        ml = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public record Assessment(long id, BigDecimal modelScore, RiskObservations.Prepared prepared) {}

    @Transactional(propagation = Propagation.NEVER)
    public Assessment assess(String hotel, LocalDate target) {
        var prepared = prepare(hotel, target);
        var prediction = predict(prepared.input());
        long id = store.save(prepared.input(), OffsetDateTime.parse(prepared.input().asOf()),
                prediction.score(), prediction.modelVersion());
        return new Assessment(id, prediction.score(), prepared);
    }

    record Prediction(BigDecimal score, String modelVersion) {}

    RiskObservations.Prepared prepare(String hotel, LocalDate target) {
        var cutoff = target.minusDays(1).atTime(9, 0).atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime();
        var history = observations.flatMap(source -> source.completeHistory(hotel, cutoff))
                .orElseThrow(() -> new IllegalStateException("Complete point-in-time risk observations unavailable"));
        return new RiskProjection(hotel, target, cutoff, history).prepare();
    }

    Prediction predict(RiskObservations.Input input) {
        String hotel = input.hotelId();
        LocalDate target = input.targetCheckInDate();
        OffsetDateTime cutoff = OffsetDateTime.parse(input.asOf());
        if (token.isBlank()) throw new IllegalStateException("Missing ML service token");
        var reply = ml.post().uri("/internal/v1/risk-assessments").headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(input).retrieve().toEntity(JsonNode.class);
        var body = reply.getBody();
        if (reply.getStatusCode().value() != 200 || body == null
                || !hotel.equals(body.path("hotelId").asString(""))
                || !target.toString().equals(body.path("targetCheckInDate").asString(""))
                || !cutoff.isEqual(OffsetDateTime.parse(body.required("asOf").asString()))
                || !input.featureSetVersion().equals(body.path("featureSetVersion").asString(""))
                || modelVersion.isBlank() || !modelVersion.equals(body.path("modelVersion").asString(""))
                || !body.path("riskScore").isNumber())
            throw new IllegalStateException("Invalid API-023 response");
        BigDecimal score = body.get("riskScore").decimalValue();
        if (score.signum() < 0 || score.compareTo(BigDecimal.ONE) > 0)
            throw new IllegalStateException("Invalid API-023 score");
        return new Prediction(score, modelVersion);
    }
}
