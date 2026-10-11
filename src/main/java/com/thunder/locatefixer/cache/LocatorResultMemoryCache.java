package com.thunder.locatefixer.cache;

import com.thunder.locatefixer.api.LocatorRequest;
import com.thunder.locatefixer.api.LocatorResult;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashMap;

/** Bounded short-lived cache for third-party provider results. */
public final class LocatorResultMemoryCache {
    private static final Object DEFAULT_SCOPE = new Object();
    private final Map<Key, Entry> entries = new LinkedHashMap<>(16, 0.75F, true);

    public synchronized Optional<LocatorResult> find(LocatorRequest request, int chunkGranularity, long maxAgeMs) {
        return find(request, chunkGranularity, maxAgeMs, DEFAULT_SCOPE);
    }

    public synchronized Optional<LocatorResult> find(LocatorRequest request, int chunkGranularity,
                                                      long maxAgeMs, Object worldScope) {
        Key key = Key.of(request, worldScope);
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (maxAgeMs <= 0L || System.nanoTime() - entry.storedAtNanos()
                > java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(maxAgeMs)) {
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

    public synchronized void put(LocatorRequest request, LocatorResult result, int chunkGranularity, int maxEntries) {
        put(request, result, chunkGranularity, maxEntries, DEFAULT_SCOPE);
    }

    public synchronized void put(LocatorRequest request, LocatorResult result, int chunkGranularity,
                                  int maxEntries, Object worldScope) {
        if (result.targetType() != request.targetType()
                || !result.targetId().equals(request.targetId())
                || !result.dimensionId().equals(request.dimensionId())) {
            throw new IllegalArgumentException("Cannot cache a result outside its request scope");
        }
        entries.put(Key.of(request, worldScope), new Entry(result, System.nanoTime()));
        int safeMaxEntries = Math.max(1, maxEntries);
        while (entries.size() > safeMaxEntries) entries.remove(entries.keySet().iterator().next());
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void clear() {
        entries.clear();
    }

    private static long horizontalDistanceSq(BlockPos first, BlockPos second) {
        return com.thunder.locatefixer.search.LocateDistances.horizontalSquared(first, second);
    }

    private record Entry(LocatorResult result, long storedAtNanos) {
    }

    private record Key(Object worldScope, String dimensionId,
                       String targetType,
                       String targetId,
                       int x, int y, int z, int maxRadius) {
        private static Key of(LocatorRequest request, Object worldScope) {
            // The nearest result can change within a chunk and at another height.
            // Keep the legacy granularity parameter for provider API compatibility.
            return new Key(java.util.Objects.requireNonNull(worldScope), request.dimensionId(), request.targetType().name(), request.targetId(),
                    request.origin().getX(), request.origin().getY(), request.origin().getZ(), request.maxRadius());
        }
    }
}
