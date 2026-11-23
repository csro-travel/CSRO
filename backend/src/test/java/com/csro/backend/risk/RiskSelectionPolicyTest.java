package com.csro.backend.risk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RiskSelectionPolicyTest {

    static Stream<Arguments> approvedThresholds() {
        return Stream.of(
                Arguments.of(RiskSelectionPolicy.F1_BASELINE, 0.22344968880463367),
                Arguments.of(RiskSelectionPolicy.RECALL_50_F1, 0.08083838778859916),
                Arguments.of(RiskSelectionPolicy.MAX_OBSERVED_RECALL, 0.008907415636863826));
    }

    @ParameterizedTest
    @MethodSource("approvedThresholds")
    void includesApprovedThresholdAndUsesTheOriginalScore(RiskSelectionPolicy policy, double threshold) {
        assertFalse(policy.selects(Math.nextDown(threshold)));
        assertTrue(policy.selects(threshold));
        assertTrue(policy.selects(Math.nextUp(threshold)));
    }

    @Test
    void databaseStorageRoundingMustNotDecideSelection() {
        double rawScore = 0.08083838778859916;
        double storedScore = BigDecimal.valueOf(rawScore).setScale(4, RoundingMode.HALF_UP).doubleValue();

        assertTrue(RiskSelectionPolicy.RECALL_50_F1.selects(rawScore));
        assertFalse(RiskSelectionPolicy.RECALL_50_F1.selects(storedScore));
    }

    @Test
    void rejectsInvalidScoresRatherThanCreatingAFallbackDecision() {
        for (RiskSelectionPolicy policy : RiskSelectionPolicy.values()) {
            for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY,
                    Double.NEGATIVE_INFINITY, -Double.MIN_VALUE, Math.nextUp(1.0)}) {
                assertThrows(IllegalArgumentException.class, () -> policy.selects(value));
            }
        }
    }

    @Test
    void preservesDifferentDecisionsForTheSameRealModelFixtureScores() {
        double higherSignalScore = 0.16093129796741762;
        double lowerSignalScore = 0.008501510369138389;

        assertFalse(RiskSelectionPolicy.F1_BASELINE.selects(higherSignalScore));
        assertTrue(RiskSelectionPolicy.RECALL_50_F1.selects(higherSignalScore));
        assertTrue(RiskSelectionPolicy.MAX_OBSERVED_RECALL.selects(higherSignalScore));
        for (RiskSelectionPolicy policy : RiskSelectionPolicy.values()) {
            assertFalse(policy.selects(lowerSignalScore));
        }
    }
}
