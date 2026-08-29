package com.thunder.locatefixer.search;

/** Optional historical hints. Zero values mean that no corresponding history is known. */
public record SearchPlanningContext(
        int previousSuccessDistance,
        int previouslyFailedRadius,
        int estimatedCost,
        boolean rareTarget
) {
    public static SearchPlanningContext empty() {
        return new SearchPlanningContext(0, 0, 50, false);
    }
}
