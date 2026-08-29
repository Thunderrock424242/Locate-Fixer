package com.thunder.locatefixer.search;

import com.thunder.locatefixer.api.LocatorRequest;
import com.thunder.locatefixer.api.LocatorTargetType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
        Set<Integer> selected = new LinkedHashSet<>();
        int minimumUsefulRadius = Math.max(0, context.previouslyFailedRadius());

        if (context.previousSuccessDistance() > 0) {
            minimumUsefulRadius = Math.max(minimumUsefulRadius,
                    Math.max(1, context.previousSuccessDistance() / 2));
        }
        if (context.rareTarget() && validRings.length > 2) {
            minimumUsefulRadius = Math.max(minimumUsefulRadius, validRings[1]);
        }

        for (int radius : validRings) {
            if (radius > minimumUsefulRadius) {
                selected.add(radius);
            }
        }
        selected.add(validRings[validRings.length - 1]);

        List<SearchStage> stages = new ArrayList<>();
        for (int radius : selected) {
            double radiusMultiplier = request.targetType() == LocatorTargetType.BIOME
                    ? biomeRadiusMultiplier : 1.0D;
            double stepMultiplier = request.targetType() == LocatorTargetType.BIOME
                    ? biomeStepMultiplier : 1.0D;
            String reason = radius <= context.previouslyFailedRadius()
                    ? "verification boundary"
                    : hasHints ? "adaptive escalation" : "configured fallback";
            stages.add(new SearchStage(radius, radiusMultiplier, stepMultiplier, reason));
        }
        return new SearchPlan(stages, validRings[validRings.length - 1], hasHints);
    }
}
