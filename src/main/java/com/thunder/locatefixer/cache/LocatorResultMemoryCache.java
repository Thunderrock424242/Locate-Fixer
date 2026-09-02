package com.thunder.locatefixer.cache;

import com.thunder.locatefixer.api.LocatorRequest;
import com.thunder.locatefixer.api.LocatorResult;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded short-lived cache for third-party provider results. */
public final class LocatorResultMemoryCache {
    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();

    public Optional<LocatorResult> find(LocatorRequest request, int chunkGranularity, long maxAgeMs) {
        Key key = Key.of(request, chunkGranularity);
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (maxAgeMs <= 0L || System.currentTimeMillis() - entry.storedAtMs() > maxAgeMs) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        LocatorResult result = entry.result();
        if (result.targetType() != request.targetType()
                || !result.targetId().equals(request.targetId())
                || !result.dimensionId().equals(request.dimensionId())) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        if (horizontalDistanceSq(request.origin(), result.position())
                > (long) request.maxRadius() * request.maxRadius()) {
            return Optional.empty();
        }
        return Optional.of(result);
    }

    public void put(LocatorRequest request, LocatorResult result, int chunkGranularity, int maxEntries) {
        if (result.targetType() != request.targetType()
                || !result.targetId().equals(request.targetId())
                || !result.dimensionId().equals(request.dimensionId())) {
            throw new IllegalArgumentException("Cannot cache a result outside its request scope");
        }
        int safeMaxEntries = Math.max(16, maxEntries);
        if (entries.size() >= safeMaxEntries) {
            Key oldest = null;
            long oldestTimestamp = Long.MAX_VALUE;
            for (Map.Entry<Key, Entry> candidate : entries.entrySet()) {
                if (candidate.getValue().storedAtMs() < oldestTimestamp) {
                    oldestTimestamp = candidate.getValue().storedAtMs();
                    oldest = candidate.getKey();
                }
            }
            if (oldest != null) {
                entries.remove(oldest);
            }
        }
        entries.put(Key.of(request, chunkGranularity), new Entry(result, System.currentTimeMillis()));
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
    }

    private static long horizontalDistanceSq(BlockPos first, BlockPos second) {
        long dx = (long) second.getX() - first.getX();
        long dz = (long) second.getZ() - first.getZ();
        return dx * dx + dz * dz;
    }

    private record Entry(LocatorResult result, long storedAtMs) {
    }

    private record Key(String dimensionId,
                       String targetType,
                       String targetId,
                       int coarseChunkX,
                       int coarseChunkZ) {
        private static Key of(LocatorRequest request, int chunkGranularity) {
            int safeGranularity = Math.max(1, chunkGranularity);
            int chunkX = request.origin().getX() >> 4;
            int chunkZ = request.origin().getZ() >> 4;
            return new Key(request.dimensionId(), request.targetType().name(), request.targetId(),
                    Math.floorDiv(chunkX, safeGranularity), Math.floorDiv(chunkZ, safeGranularity));
        }
    }
}
