package com.csro.backend.risk;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Repository
class RiskAssessmentStore {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    RiskAssessmentStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public long save(RiskObservations.Input input, OffsetDateTime cutoff, BigDecimal score, String model) {
        long hotel = jdbc.sql("SELECT id FROM hotels WHERE hotel_code=:code FOR UPDATE")
                .param("code", input.hotelId()).query(Long.class).single();
        String snapshot = json.writeValueAsString(input.features());
        // Serialize exact replays; keep distinct model versions and input snapshots auditable.
        var existing = jdbc.sql("""
                SELECT id FROM risk_assessments
                WHERE hotel_id=:hotel AND target_check_in_date=:target AND as_of=:cutoff
                  AND model_version=:model AND feature_set_version=:version
                  AND feature_snapshot=CAST(:snapshot AS jsonb) AND risk_score=round(:score,4)
                ORDER BY id LIMIT 1
                """).param("hotel", hotel).param("target", input.targetCheckInDate()).param("cutoff", cutoff)
                .param("model", model).param("version", input.featureSetVersion()).param("score", score)
                .param("snapshot", snapshot).query(Long.class).optional();
        if (existing.isPresent()) return existing.get();
        return jdbc.sql("""
                INSERT INTO risk_assessments (hotel_id,target_check_in_date,as_of,risk_score,
                                              model_version,feature_set_version,feature_snapshot)
                VALUES (:hotel,:target,:cutoff,:score,:model,:version,CAST(:snapshot AS jsonb))
                RETURNING id
                """).param("hotel", hotel).param("target", input.targetCheckInDate()).param("cutoff", cutoff)
                .param("model", model).param("version", input.featureSetVersion()).param("score", score)
                .param("snapshot", snapshot).query(Long.class).single();
    }
}
