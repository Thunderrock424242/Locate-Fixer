package com.thunder.locatefixer.search;

import com.thunder.locatefixer.api.LocatorRequest;
import com.thunder.locatefixer.api.LocatorTargetType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/**
 * Converts legacy configured rings into a bounded plan. The planner never exceeds the
 * configured maximum and falls back to the exact configured rings when no hints exist.
 */
public final class AdaptiveSearchPlanner {
    public SearchPlan plan(LocatorRequest request,
                           int[] configuredRings,
                           SearchPlanningContext context,
                           double biomeRadiusMultiplier,
                           double biomeStepMultiplier) {
        int[] validRings = Arrays.stream(configuredRings)
                .filter(radius -> radius > 0 && radius <= request.maxRadius())
                .distinct()
                .sorted()
                .toArray();
        if (validRings.length == 0) {
            validRings = new int[]{request.maxRadius()};
        }

        boolean hasHints = context.previousSuccessDistance() > 0
                || context.previouslyFailedRadius() > 0
                || context.rareTarget();
        // History describes cost and useful bounds, not proof that nearer regions
        // can be omitted. Even an exact-origin failed biome sample is incomplete.
        TreeSet<Integer> selected = new TreeSet<>();
        for (int radius : validRings) {
            selected.add(radius);
        }
        if (context.previousSuccessDistance() > 0
                && context.previousSuccessDistance() <= request.maxRadius()) {
            selected.add(context.previousSuccessDistance());
        }
        selected.add(request.maxRadius());

        List<SearchStage> stages = new ArrayList<>();
        for (int radius : selected) {
            double radiusMultiplier = request.targetType() == LocatorTargetType.BIOME
                    ? biomeRadiusMultiplier : 1.0D;
            double stepMultiplier = request.targetType() == LocatorTargetType.BIOME
                    ? biomeStepMultiplier : 1.0D;
            String reason = hasHints ? "adaptive escalation" : "configured fallback";
            stages.add(new SearchStage(radius, radiusMultiplier, stepMultiplier, reason));
        }
        return new SearchPlan(stages, request.maxRadius(), hasHints);
    }
}
