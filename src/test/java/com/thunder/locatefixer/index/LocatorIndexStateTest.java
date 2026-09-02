package com.thunder.locatefixer.index;

import com.thunder.locatefixer.api.LocatorResult;
import com.thunder.locatefixer.api.LocatorTargetType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocatorIndexStateTest {
    @Test
    void roundTripsAndDeduplicatesEntries() {
        LocatorIndexState state = new LocatorIndexState();
        LocatorResult result = result("minecraft:village", new BlockPos(120, 70, -80));
        assertTrue(state.add(result, 128));
        state.add(result, 128);

        LocatorIndexState loaded = LocatorIndexState.load(state.save(new CompoundTag()));

        assertEquals(1, loaded.size());
        assertTrue(loaded.findNearest(LocatorTargetType.STRUCTURE, "minecraft:village",
                "minecraft:overworld", BlockPos.ZERO, 1000,
                TimeUnit.DAYS.toMillis(1), TimeUnit.MINUTES.toMillis(30)).isPresent());
    }

    @Test
    void skipsMalformedEntriesWithoutDiscardingValidData() {
        LocatorIndexState state = new LocatorIndexState();
        state.add(result("minecraft:village", new BlockPos(10, 64, 10)), 128);
        CompoundTag root = state.save(new CompoundTag());
        ListTag entries = root.getList("entries", net.minecraft.nbt.Tag.TAG_COMPOUND);
        CompoundTag malformed = new CompoundTag();
        malformed.putString("targetType", "NOT_A_REAL_TYPE");
        entries.add(malformed);

        LocatorIndexState loaded = LocatorIndexState.load(root);

        assertEquals(1, loaded.size());
    }

    @Test
    void neverServesUnverifiedOrLegacyPredictiveFeatureEntries() {
        LocatorIndexState unverifiedState = new LocatorIndexState();
        unverifiedState.add(result(LocatorTargetType.FEATURE, "minecraft:ore_diamond",
                new BlockPos(10, 20, 10), "biome-generation-capability", false), 128);

        LocatorIndexState legacyState = new LocatorIndexState();
        legacyState.add(result(LocatorTargetType.FEATURE, "minecraft:ore_diamond",
                new BlockPos(20, 20, 20), "biome-generation-settings", true), 128);

        assertFalse(findFeature(unverifiedState).isPresent());
        assertFalse(findFeature(legacyState).isPresent());
    }

    private static LocatorResult result(String id, BlockPos position) {
        return result(LocatorTargetType.STRUCTURE, id, position, "test", true);
    }

    private static LocatorResult result(LocatorTargetType targetType, String id, BlockPos position,
                                        String discoverySource, boolean verified) {
        return new LocatorResult(targetType, id, "minecraft:overworld",
                position, "locatefixer:vanilla", discoverySource, Instant.now(), true, verified, Map.of());
    }

    private static java.util.Optional<LocatorIndexEntry> findFeature(LocatorIndexState state) {
        return state.findNearest(LocatorTargetType.FEATURE, "minecraft:ore_diamond",
                "minecraft:overworld", BlockPos.ZERO, 1000,
                TimeUnit.DAYS.toMillis(1), TimeUnit.MINUTES.toMillis(30));
    }
}
