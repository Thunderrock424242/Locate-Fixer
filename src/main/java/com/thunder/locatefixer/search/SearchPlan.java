package com.thunder.locatefixer.search;

import java.util.List;

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
}
