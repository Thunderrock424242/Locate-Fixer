package com.thunder.locatefixer.index;

import com.thunder.locatefixer.api.LocatorResult;
import com.thunder.locatefixer.api.LocatorTargetType;
import com.thunder.locatefixer.config.LocateFixerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Per-world SavedData owner stored as world/data/locatefixer_index.dat. */
public final class WorldLocatorIndex extends SavedData {
    public static final String FILE_ID = "locatefixer_index";
    private final LocatorIndexState state;

    public WorldLocatorIndex() {
        this(new LocatorIndexState());
    }

    private WorldLocatorIndex(LocatorIndexState state) {
        this.state = state;
    }

    public static WorldLocatorIndex get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage()
                .computeIfAbsent(WorldLocatorIndex::load, WorldLocatorIndex::new, FILE_ID);
    }

    private static WorldLocatorIndex load(CompoundTag tag) {
        return new WorldLocatorIndex(LocatorIndexState.load(tag));
    }

    public Optional<LocatorResult> findNearest(LocatorTargetType type,
                                               String targetId,
                                               String dimensionId,
                                               BlockPos origin,
                                               int maxRadius) {
        long expiryMs = TimeUnit.DAYS.toMillis(LocateFixerConfig.SERVER.persistentIndexExpiryDays.get());
        long verificationMs = TimeUnit.MINUTES.toMillis(
                LocateFixerConfig.SERVER.persistentIndexVerificationMinutes.get());
        return state.findNearest(type, targetId, dimensionId, origin, maxRadius, expiryMs, verificationMs)
                .map(LocatorIndexEntry::toResult);
    }

    public void record(LocatorResult result) {
        if (LocateFixerConfig.SERVER.persistentIndexEnabled.get()
                && state.add(result, LocateFixerConfig.SERVER.persistentIndexMaxEntries.get())) {
            setDirty();
        }
    }

    public int size() {
        return state.size();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        return state.save(tag);
    }
}
