package com.thunder.locatefixer.search;

import com.thunder.locatefixer.api.LocatorRequest;
import com.thunder.locatefixer.api.LocatorTargetType;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchHistoryTrackerTest {
    @Test
    void failureEvidenceIsConfinedToExactOriginIncludingHeight() {
        SearchHistoryTracker history = new SearchHistoryTracker();
        history.recordFailure(request(BlockPos.ZERO), 1000, 50);
        assertEquals(1000, history.contextFor(request(BlockPos.ZERO)).previouslyFailedRadius());
        assertEquals(0, history.contextFor(request(new BlockPos(1, 0, 0))).previouslyFailedRadius());
        assertEquals(0, history.contextFor(request(new BlockPos(0, 64, 0))).previouslyFailedRadius());
    }

    @Test
    void identicalDimensionAndOriginDoNotShareHistoryAcrossWorldScopes() {
        SearchHistoryTracker history = new SearchHistoryTracker();
        Object first = new Object();
        Object reloaded = new Object();
        history.recordFailure(request(BlockPos.ZERO), 1000, 50, first);
        assertEquals(1000, history.contextFor(request(BlockPos.ZERO), first).previouslyFailedRadius());
        assertEquals(0, history.contextFor(request(BlockPos.ZERO), reloaded).previouslyFailedRadius());
    }

    private static LocatorRequest request(BlockPos origin) {
        return LocatorRequest.create("test", LocatorTargetType.BIOME, "minecraft:lush_caves",
                "minecraft:overworld", origin, 1000);
    }
}
