package com.thunder.locatefixer.cache;

import com.thunder.locatefixer.api.LocatorRequest;
import com.thunder.locatefixer.api.LocatorResult;
import com.thunder.locatefixer.api.LocatorTargetType;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocatorResultMemoryCacheTest {
    @Test
    void scopesHitsByDimensionTargetOriginAndRadius() {
        LocatorResultMemoryCache cache = new LocatorResultMemoryCache();
        LocatorRequest request = request("minecraft:overworld", 1000);
        LocatorResult result = new LocatorResult(LocatorTargetType.CUSTOM, "mymod:target",
                "minecraft:overworld", new BlockPos(50, 70, 50), "mymod:index", "test",
                Instant.now(), true, false, Map.of());

        cache.put(request, result, 8, 16);

        assertTrue(cache.find(request, 8, 60_000L).isPresent());
        assertTrue(cache.find(request("minecraft:the_nether", 1000), 8, 60_000L).isEmpty());
        assertTrue(cache.find(request("minecraft:overworld", 10), 8, 60_000L).isEmpty());
        assertEquals(1, cache.size());
    }

    private static LocatorRequest request(String dimensionId, int maxRadius) {
        return LocatorRequest.create("test", LocatorTargetType.CUSTOM, "mymod:target",
                dimensionId, BlockPos.ZERO, maxRadius);
    }

    @Test
    void nearbyOriginsAndDifferentHeightsCannotReuseNearestResults() {
        LocatorResultMemoryCache cache = new LocatorResultMemoryCache();
        LocatorRequest original = request("minecraft:overworld", 1000);
        LocatorResult result = new LocatorResult(LocatorTargetType.CUSTOM, "mymod:target",
                "minecraft:overworld", new BlockPos(50, 70, 50), "mymod:index", "test",
                Instant.now(), true, false, Map.of());
        cache.put(original, result, 8, 16);
        LocatorRequest nearby = LocatorRequest.create("test", original.targetType(), original.targetId(),
                original.dimensionId(), new BlockPos(1, 0, 0), original.maxRadius());
        LocatorRequest above = LocatorRequest.create("test", original.targetType(), original.targetId(),
                original.dimensionId(), new BlockPos(0, 64, 0), original.maxRadius());
        assertTrue(cache.find(nearby, 8, 60_000L).isEmpty());
        assertTrue(cache.find(above, 8, 60_000L).isEmpty());
    }

    @Test
    void separatesLiveWorldAndRegistryScopesAndEnforcesExactEntryLimit() {
        LocatorResultMemoryCache cache = new LocatorResultMemoryCache();
        Object firstWorld = new Object();
        Object secondWorld = new Object();
        LocatorRequest request = request("minecraft:overworld", 1000);
        LocatorResult result = new LocatorResult(LocatorTargetType.CUSTOM, "mymod:target",
                "minecraft:overworld", BlockPos.ZERO, "mymod:index", "test", Instant.now(), true, true, Map.of());
        cache.put(request, result, 8, 1, firstWorld);
        assertTrue(cache.find(request, 8, 60_000L, firstWorld).isPresent());
        assertTrue(cache.find(request, 8, 60_000L, secondWorld).isEmpty());
        cache.put(request, result, 8, 1, secondWorld);
        assertEquals(1, cache.size());
        assertTrue(cache.find(request, 8, 60_000L, firstWorld).isEmpty());
    }

    @Test
    void extremeProviderCoordinatesCannotOverflowIntoAnInRangeCacheHit() {
        LocatorResultMemoryCache cache = new LocatorResultMemoryCache();
        LocatorRequest request = LocatorRequest.create("test", LocatorTargetType.CUSTOM, "mymod:target",
                "minecraft:overworld", new BlockPos(30_000_000, 64, 30_000_000), 1000);
        LocatorResult invalid = new LocatorResult(LocatorTargetType.CUSTOM, "mymod:target",
                "minecraft:overworld", new BlockPos(Integer.MIN_VALUE, 64, Integer.MIN_VALUE),
                "mymod:index", "test", Instant.now(), true, true, Map.of());
        cache.put(request, invalid, 8, 16);
        assertTrue(cache.find(request, 8, 60_000L).isEmpty());
    }
}
