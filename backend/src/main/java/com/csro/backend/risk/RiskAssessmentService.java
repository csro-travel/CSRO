package com.csro.backend.risk;

import java.math.BigDecimal;
import java.time.*;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/** Common observed-history preparation, API-023 prediction and assessment storage entry point. */
@Service
public class RiskAssessmentService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Optional<RiskObservations> observations;
    private final RiskAssessmentStore store;
    private final RiskMlClient ml;
    private final String modelVersion;

    RiskAssessmentService(Optional<RiskObservations> observations, RiskAssessmentStore store,
            RiskMlClient ml, String modelVersion) {
        this.observations = observations;
        this.store = store;
        this.ml = ml;
        this.modelVersion = modelVersion;
    }

    /** Canonical configuration and the explicit constructor used by the common live tests. */
    @Autowired
    public RiskAssessmentService(Optional<RiskObservations> observations, RiskAssessmentStore store,
            @Value("${ml.risk.base-url:http://127.0.0.1:8002}") String baseUrl,
            @Value("${ml.risk.service-token:}") String token,
            @Value("${ml.risk.model-version:process-synthetic-lightgbm-0.2-84d386281d22}") String modelVersion) {
        this(observations, store, new RiskMlClient(baseUrl, token), modelVersion);
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
        if (hotel == null || hotel.isBlank() || target == null || target.getYear() < 1
                || target.getYear() > 9999 || target.equals(LocalDate.of(1, 1, 1))) {
            throw new IllegalArgumentException("Invalid risk preparation target");
        }
        var cutoff = target.minusDays(1).atTime(9, 0).atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime();
        var history = observations.flatMap(source -> source.completeHistory(hotel, cutoff))
                .orElseThrow(() -> new IllegalStateException("Complete point-in-time risk observations unavailable"));
        var prepared = new RiskProjection(hotel, target, cutoff, history).prepare();
        request(prepared.input()).validate();
        return prepared;
    }

    Prediction predict(RiskObservations.Input input) {
        var request = request(input);
        request.validate();
        var reply = ml.assess(request);
        // The currently shipped transport and frozen model contract both require this exact version.
        if (!RiskMlClient.MODEL_VERSION.equals(modelVersion) || reply == null || !request.hotelId().equals(reply.hotelId())
                || !request.targetCheckInDate().equals(reply.targetCheckInDate())
                || reply.asOf() == null || !request.asOf().isEqual(reply.asOf())
                || !modelVersion.equals(reply.modelVersion())
                || !request.featureSetVersion().equals(reply.featureSetVersion())
                || !Double.isFinite(reply.riskScore()) || reply.riskScore() < 0 || reply.riskScore() > 1) {
            throw new RiskMlException(RiskMlException.Kind.INVALID_RESPONSE, null, null, null);
        }
        return new Prediction(BigDecimal.valueOf(reply.riskScore()), reply.modelVersion());
    }

    static RiskRequest request(RiskObservations.Input input) {
        if (input == null) throw new IllegalArgumentException("Risk input is required");
        return JSON.convertValue(input, RiskRequest.class);
    }

    static RiskObservations.Input input(RiskRequest request) {
        request.validate();
        var f = request.features();
        return new RiskObservations.Input(request.hotelId(), request.targetCheckInDate(), request.asOf().toString(),
                request.featureSetVersion(), Map.of(
                    "pastFailureRate90d", f.pastFailureRate90d(),
                    "targetConfirmedBookingCount", f.targetConfirmedBookingCount(),
                    "bookingVolumeGrowth7d", f.bookingVolumeGrowth7d(),
                    "reconfirmationOverdueRate7d", f.reconfirmationOverdueRate7d(),
                    "inventoryUnavailableRate7d", f.inventoryUnavailableRate7d(),
                    "isPeakDay", f.isPeakDay()));
    }
}
