package com.thunder.locatefixer.index;

import com.thunder.locatefixer.LocateFixerMod;
import com.thunder.locatefixer.api.LocatorResult;
import com.thunder.locatefixer.api.LocatorTargetType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** NBT serialization and bounded query logic shared by the 1.20 and 1.21 SavedData wrappers. */
public final class LocatorIndexState {
    public static final int SCHEMA_VERSION = 1;
    private static final int HARD_LOAD_LIMIT = 100_000;
    private final Map<String, LocatorIndexEntry> entries = new LinkedHashMap<>();

    public static LocatorIndexState load(CompoundTag root) {
        LocatorIndexState state = new LocatorIndexState();
        int schemaVersion = root.contains("schemaVersion", Tag.TAG_INT)
                ? root.getInt("schemaVersion") : 0;
        if (schemaVersion > SCHEMA_VERSION) {
            LocateFixerMod.LOGGER.warn("[LocateUnbound] Locator index schema {} is newer than supported schema {}; ignoring it.",
                    schemaVersion, SCHEMA_VERSION);
            return state;
        }
        ListTag list = root.getList("entries", Tag.TAG_COMPOUND);
        int skipped = 0;
        for (int i = 0; i < Math.min(list.size(), HARD_LOAD_LIMIT); i++) {
            try {
                CompoundTag tag = list.getCompound(i);
                LocatorIndexEntry entry = new LocatorIndexEntry(
                        LocatorTargetType.valueOf(tag.getString("targetType")),
                        tag.getString("targetId"), tag.getString("dimension"),
                        new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")),
                        tag.getString("source"), tag.getLong("discoveredAt"),
                        tag.getLong("verifiedAt"), tag.getBoolean("generated"),
                        tag.getBoolean("verified"), tag.getString("backend"));
                state.entries.put(entry.deduplicationKey(), entry);
            } catch (RuntimeException corruptEntry) {
                skipped++;
            }
        }
        if (skipped > 0) {
            LocateFixerMod.LOGGER.warn("[LocateUnbound] Skipped {} invalid locator index entries.", skipped);
        }
        return state;
    }

    public CompoundTag save(CompoundTag root) {
        root.putInt("schemaVersion", SCHEMA_VERSION);
        ListTag list = new ListTag();
        for (LocatorIndexEntry entry : entries.values()) {
            CompoundTag tag = new CompoundTag();
            tag.putString("targetType", entry.targetType().name());
            tag.putString("targetId", entry.targetId());
            tag.putString("dimension", entry.dimensionId());
            tag.putInt("x", entry.position().getX());
            tag.putInt("y", entry.position().getY());
            tag.putInt("z", entry.position().getZ());
            tag.putString("source", entry.discoverySource());
            tag.putLong("discoveredAt", entry.discoveredAtMs());
            tag.putLong("verifiedAt", entry.lastVerifiedAtMs());
            tag.putBoolean("generated", entry.generated());
            tag.putBoolean("verified", entry.verified());
            tag.putString("backend", entry.backendId());
            list.add(tag);
        }
        root.put("entries", list);
        return root;
    }

    public boolean add(LocatorResult result, int maxEntries) {
        LocatorIndexEntry entry = LocatorIndexEntry.fromResult(result);
        LocatorIndexEntry previous = entries.put(entry.deduplicationKey(), entry);
        trim(Math.max(64, maxEntries));
        return !entry.equals(previous);
    }

    public Optional<LocatorIndexEntry> findNearest(LocatorTargetType targetType,
                                                   String targetId,
                                                   String dimensionId,
                                                   BlockPos origin,
                                                   int maxRadius,
                                                   long expiryMs,
                                                   long verificationMs) {
        long now = System.currentTimeMillis();
        long maxDistanceSq = (long) maxRadius * maxRadius;
        LocatorIndexEntry best = null;
        long bestDistanceSq = Long.MAX_VALUE;
        for (LocatorIndexEntry entry : entries.values()) {
            if (entry.targetType() != targetType
                    || !entry.targetId().equals(targetId)
                    || !entry.dimensionId().equals(dimensionId)) continue;
            long timestamp = entry.lastVerifiedAtMs() > 0 ? entry.lastVerifiedAtMs() : entry.discoveredAtMs();
            if (timestamp <= 0 || now - timestamp > expiryMs) continue;
            if (entry.targetType() != LocatorTargetType.STRUCTURE
                    && entry.targetType() != LocatorTargetType.BIOME
                    && now - timestamp > verificationMs) continue;
            long distanceSq = horizontalDistanceSq(origin, entry.position());
            if (distanceSq <= maxDistanceSq && distanceSq < bestDistanceSq) {
                best = entry;
                bestDistanceSq = distanceSq;
            }
        }
        return Optional.ofNullable(best);
    }

    public int size() {
        return entries.size();
    }

    private void trim(int maxEntries) {
        if (entries.size() <= maxEntries) return;
        List<LocatorIndexEntry> oldest = new ArrayList<>(entries.values());
        oldest.sort(Comparator.comparingLong(LocatorIndexEntry::discoveredAtMs));
        int removeCount = entries.size() - maxEntries;
        for (int i = 0; i < removeCount && i < oldest.size(); i++) {
            entries.remove(oldest.get(i).deduplicationKey());
        }
    }

    private static long horizontalDistanceSq(BlockPos first, BlockPos second) {
        long dx = (long) second.getX() - first.getX();
        long dz = (long) second.getZ() - first.getZ();
        return dx * dx + dz * dz;
    }
}
