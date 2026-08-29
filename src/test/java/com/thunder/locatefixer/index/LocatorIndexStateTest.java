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

    private static LocatorResult result(String id, BlockPos position) {
        return new LocatorResult(LocatorTargetType.STRUCTURE, id, "minecraft:overworld",
                position, "locatefixer:vanilla", "test", Instant.now(), true, true, Map.of());
    }
}
