package com.thunder.locatefixer.search;

import com.thunder.locatefixer.api.LocatorRequest;
import com.thunder.locatefixer.api.LocatorTargetType;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveSearchPlannerTest {
    private final AdaptiveSearchPlanner planner = new AdaptiveSearchPlanner();

    @Test
    void preservesConfiguredRingsWithoutHistory() {
        LocatorRequest request = LocatorRequest.create("test", LocatorTargetType.STRUCTURE,
                "minecraft:village", "minecraft:overworld", BlockPos.ZERO, 64_000);

        SearchPlan plan = planner.plan(request, new int[]{6400, 16000, 32000, 64000},
                SearchPlanningContext.empty(), 1.5D, 1.75D);

        assertArrayEquals(new int[]{6400, 16000, 32000, 64000},
                plan.stages().stream().mapToInt(SearchStage::radius).toArray());
        assertTrue(!plan.adaptive());
    }

    @Test
    void skipsRadiiAlreadyCoveredByFailureHistory() {
        LocatorRequest request = LocatorRequest.create("test", LocatorTargetType.BIOME,
                "minecraft:mushroom_fields", "minecraft:overworld", BlockPos.ZERO, 64_000);

        SearchPlan plan = planner.plan(request, new int[]{6400, 16000, 32000, 64000},
                new SearchPlanningContext(0, 16_000, 70, true), 1.5D, 1.75D);

        assertArrayEquals(new int[]{32000, 64000},
                plan.stages().stream().mapToInt(SearchStage::radius).toArray());
        assertEquals(1.5D, plan.stages().get(0).sampleRadiusMultiplier());
        assertTrue(plan.adaptive());
    }
}
