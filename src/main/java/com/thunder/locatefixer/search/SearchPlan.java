package com.thunder.locatefixer.search;

import java.util.List;
import java.util.ArrayList;
import java.util.TreeMap;

/** Immutable, inspectable plan selected before invoking a locator backend. */
public record SearchPlan(List<SearchStage> stages, int maxRadius, boolean adaptive) {
    public SearchPlan {
        stages = List.copyOf(stages);
        if (stages.isEmpty()) {
            throw new IllegalArgumentException("A search plan needs at least one stage");
        }
        if (maxRadius <= 0) {
            throw new IllegalArgumentException("maxRadius must be positive");
        }
    }

    /** A discovery only adds a verification boundary; all nearer and fallback stages remain. */
    public SearchPlan withCandidateRadius(int radius) {
        if (radius <= 0 || radius > maxRadius) return this;
        TreeMap<Integer, SearchStage> ordered = new TreeMap<>();
        for (SearchStage stage : stages) ordered.put(stage.radius(), stage);
        SearchStage first = stages.get(0);
        ordered.putIfAbsent(radius, new SearchStage(radius, first.sampleRadiusMultiplier(),
                first.sampleStepMultiplier(), "indexed candidate verification"));
        return new SearchPlan(new ArrayList<>(ordered.values()), maxRadius, true);
    }
}
