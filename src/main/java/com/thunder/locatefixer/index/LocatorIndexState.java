package com.thunder.locatefixer.index;

import com.thunder.locatefixer.LocateFixerMod;
import com.thunder.locatefixer.api.LocatorResult;
import com.thunder.locatefixer.api.LocatorTargetType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** NBT serialization and bounded query logic shared by the 1.20 and 1.21 SavedData wrappers. */
public final class LocatorIndexState {
    public static final int SCHEMA_VERSION = 2;
    private static final int HARD_LOAD_LIMIT = 100_000;
    private static final int CELL_SIZE = 512;
    private final Map<String, LocatorIndexEntry> entries = new LinkedHashMap<>();
    private final Map<Scope, NavigableMap<Integer, NavigableMap<Integer, Map<String, LocatorIndexEntry>>>> spatial = new LinkedHashMap<>();
    private CompoundTag futureSchemaData;

    public static LocatorIndexState load(CompoundTag root) {
        LocatorIndexState state = new LocatorIndexState();
        int schemaVersion = root.contains("schemaVersion", Tag.TAG_INT)
                ? root.getInt("schemaVersion") : 0;
        if (schemaVersion > SCHEMA_VERSION) {
            LocateFixerMod.LOGGER.warn("[LocateFixer] Locator index schema {} is newer than supported schema {}; ignoring it.",
                    schemaVersion, SCHEMA_VERSION);
            state.futureSchemaData = root.copy();
            return state;
        }
        ListTag list = root.getList("entries", Tag.TAG_COMPOUND);
        int skipped = 0;
        for (int i = 0; i < Math.min(list.size(), HARD_LOAD_LIMIT); i++) {
            try {
                CompoundTag tag = list.getCompound(i);
                if (!tag.contains("x", Tag.TAG_INT) || !tag.contains("y", Tag.TAG_INT)
                        || !tag.contains("z", Tag.TAG_INT)) throw new IllegalArgumentException("Missing coordinates");
                LocatorIndexEntry entry = new LocatorIndexEntry(
                        LocatorTargetType.valueOf(tag.getString("targetType")),
                        tag.getString("targetId"), tag.getString("dimension"),
                        new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")),
                        tag.getString("source"), tag.getLong("discoveredAt"),
                        tag.getLong("verifiedAt"), tag.getBoolean("generated"),
                        tag.getBoolean("verified"), tag.getString("backend"));
                state.insert(entry);
            } catch (RuntimeException corruptEntry) {
                skipped++;
            }
        }
        if (skipped > 0) {
            LocateFixerMod.LOGGER.warn("[LocateFixer] Skipped {} invalid locator index entries.", skipped);
        }
        return state;
    }

    public CompoundTag save(CompoundTag root) {
        if (futureSchemaData != null) return futureSchemaData.copy();
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
        if (futureSchemaData != null) return false;
        LocatorIndexEntry entry = LocatorIndexEntry.fromResult(result);
        LocatorIndexEntry previous = entries.get(entry.deduplicationKey());
        // Batch re-verifications of an unchanged discovery; SavedData writes once
        // at the normal world-save boundary, rather than on every locate hit.
        if (previous != null && previous.verified() == entry.verified()
                && previous.generated() == entry.generated()
                && previous.discoverySource().equals(entry.discoverySource())
                && entry.lastVerifiedAtMs() - previous.lastVerifiedAtMs() < 60_000L) {
            return false;
        }
        insert(entry);
        trim(Math.max(1, Math.min(HARD_LOAD_LIMIT, maxEntries)));
        return true;
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
        if (maxRadius <= 0 || expiryMs <= 0 || verificationMs <= 0) return Optional.empty();
        NavigableMap<Integer, NavigableMap<Integer, Map<String, LocatorIndexEntry>>> columns =
                spatial.get(new Scope(targetType, targetId, dimensionId));
        if (columns == null) return Optional.empty();
        int minX = cell((long) origin.getX() - maxRadius);
        int maxX = cell((long) origin.getX() + maxRadius);
        int minZ = cell((long) origin.getZ() - maxRadius);
        int maxZ = cell((long) origin.getZ() + maxRadius);
        for (var column : columns.subMap(minX, true, maxX, true).values()) {
          for (var bucket : column.subMap(minZ, true, maxZ, true).values()) {
           for (LocatorIndexEntry entry : bucket.values()) {
            // Older development builds could persist predictive feature-biome
            // results as verified. Preserve the data file but never serve those
            // records as exact discoveries.
            if (!entry.verified() || "biome-generation-settings".equals(entry.discoverySource())) continue;
            long timestamp = entry.lastVerifiedAtMs() > 0 ? entry.lastVerifiedAtMs() : entry.discoveredAtMs();
            if (timestamp <= 0 || now - timestamp > expiryMs) continue;
            if (now < timestamp || now - timestamp > verificationMs) continue;
            long distanceSq = horizontalDistanceSq(origin, entry.position());
            if (distanceSq <= maxDistanceSq && distanceSq < bestDistanceSq) {
                best = entry;
                bestDistanceSq = distanceSq;
            }
           }
          }
        }
        return Optional.ofNullable(best);
    }

    public int size() {
        return entries.size();
    }

    private void trim(int maxEntries) {
        while (entries.size() > maxEntries) {
            remove(entries.values().iterator().next());
        }
    }

    public boolean invalidate() {
        if (futureSchemaData != null || entries.isEmpty()) return false;
        entries.clear();
        spatial.clear();
        return true;
    }

    /** Apply the current configured bound once when SavedData is loaded. */
    public boolean applyLimit(int maximum) {
        if (futureSchemaData != null) return false;
        int previous = entries.size();
        trim(Math.max(1, Math.min(HARD_LOAD_LIMIT, maximum)));
        return entries.size() != previous;
    }

    private void insert(LocatorIndexEntry entry) {
        LocatorIndexEntry previous = entries.get(entry.deduplicationKey());
        if (previous != null) remove(previous);
        entries.put(entry.deduplicationKey(), entry);
        spatial.computeIfAbsent(scope(entry), ignored -> new TreeMap<>())
                .computeIfAbsent(cell(entry.position().getX()), ignored -> new TreeMap<>())
                .computeIfAbsent(cell(entry.position().getZ()), ignored -> new LinkedHashMap<>())
                .put(entry.deduplicationKey(), entry);
    }

    private void remove(LocatorIndexEntry entry) {
        entries.remove(entry.deduplicationKey());
        var columns = spatial.get(scope(entry));
        var rows = columns.get(cell(entry.position().getX()));
        var bucket = rows.get(cell(entry.position().getZ()));
        bucket.remove(entry.deduplicationKey());
        if (bucket.isEmpty()) rows.remove(cell(entry.position().getZ()));
        if (rows.isEmpty()) columns.remove(cell(entry.position().getX()));
        if (columns.isEmpty()) spatial.remove(scope(entry));
    }

    private static int cell(long coordinate) {
        return (int) Math.floorDiv(coordinate, CELL_SIZE);
    }

    private static Scope scope(LocatorIndexEntry entry) {
        return new Scope(entry.targetType(), entry.targetId(), entry.dimensionId());
    }

    private record Scope(LocatorTargetType type, String targetId, String dimensionId) {}

    private static long horizontalDistanceSq(BlockPos first, BlockPos second) {
        return com.thunder.locatefixer.search.LocateDistances.horizontalSquared(first, second);
    }
}
