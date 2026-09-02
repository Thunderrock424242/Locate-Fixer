package com.thunder.locatefixer.search;

import java.util.Objects;

/** User-facing locate messages whose wording must match the work actually performed. */
public final class LocateSearchMessages {
    private LocateSearchMessages() {
    }

    public static String stage(String kind, int radiusBlocks, int stage, int totalStages) {
        Objects.requireNonNull(kind, "kind");
        if (radiusBlocks <= 0) {
            throw new IllegalArgumentException("radiusBlocks must be positive");
        }
        if (totalStages <= 0 || stage <= 0 || stage > totalStages) {
            throw new IllegalArgumentException("stage must be within totalStages");
        }

        String action = stage == 1 ? "Searching for " + kind : "Extending " + kind + " search";
        return "🔍 " + action + " up to " + radiusBlocks
                + " blocks [stage " + stage + "/" + totalStages + "]";
    }

    public static String biomeUnavailable(String target, String dimension) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(dimension, "dimension");
        return "❌ Biome target '" + target + "' is registered, but is not available from the active biome source in '"
                + dimension + "'. No radius search was run. Check the biome/worldgen mod's placement configuration, "
                + "then reload the world.";
    }
}
