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
}
