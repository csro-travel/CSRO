package com.csro.backend.risk;

/** Fixed Spring selection policies; these thresholds are not model probabilities or guarantees. */
public enum RiskSelectionPolicy {
    F1_BASELINE(0.22344968880463367),
    RECALL_50_F1(0.08083838778859916),
    MAX_OBSERVED_RECALL(0.008907415636863826);

    private final double threshold;

    RiskSelectionPolicy(double threshold) {
        this.threshold = threshold;
    }

    public double threshold() {
        return threshold;
    }

    /** Compare the original model score before PostgreSQL's numeric(5,4) rounding. */
    public boolean selects(double rawRiskScore) {
        if (!Double.isFinite(rawRiskScore) || rawRiskScore < 0 || rawRiskScore > 1) {
            throw new IllegalArgumentException("Risk score must be finite and within [0,1]");
        }
        return rawRiskScore >= threshold;
    }
}
