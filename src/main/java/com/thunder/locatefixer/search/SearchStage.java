package com.thunder.locatefixer.search;

/** One bounded step in an adaptive search plan. */
public record SearchStage(
        int radius,
        double sampleRadiusMultiplier,
        double sampleStepMultiplier,
        String reason
) {
    public SearchStage {
        if (radius <= 0) {
            throw new IllegalArgumentException("radius must be positive");
        }
        if (sampleRadiusMultiplier <= 0.0D || sampleStepMultiplier <= 0.0D) {
            throw new IllegalArgumentException("sample multipliers must be positive");
        }
        reason = reason == null ? "configured fallback" : reason;
    }
}
