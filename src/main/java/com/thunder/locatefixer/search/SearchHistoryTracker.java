package com.thunder.locatefixer.search;

import com.thunder.locatefixer.api.LocatorRequest;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Small bounded in-memory history used by the adaptive planner; persistent discoveries remain separate. */
public final class SearchHistoryTracker {
    private static final int MAX_TARGETS = 2048;
    private static final Object DEFAULT_SCOPE = new Object();
    private final Map<Key, Stats> history = new ConcurrentHashMap<>();

    public SearchPlanningContext contextFor(LocatorRequest request) {
        return contextFor(request, DEFAULT_SCOPE);
    }

    public SearchPlanningContext contextFor(LocatorRequest request, Object worldScope) {
        Stats stats = history.get(Key.of(request, worldScope));
        if (stats == null) {
            return SearchPlanningContext.empty();
        }
        return new SearchPlanningContext(stats.successDistance, stats.failedRadius,
                stats.estimatedCost, stats.rareTarget);
    }

    public void recordSuccess(LocatorRequest request, int distance, int estimatedCost) {
        recordSuccess(request, distance, estimatedCost, DEFAULT_SCOPE);
    }

    public synchronized void recordSuccess(LocatorRequest request, int distance, int estimatedCost, Object worldScope) {
        history.compute(Key.of(request, worldScope), (key, previous) -> {
            int samples = previous == null ? 1 : Math.min(64, previous.samples + 1);
            int oldDistance = previous == null ? distance : previous.successDistance;
            int average = Math.max(1, (oldDistance * (samples - 1) + Math.max(1, distance)) / samples);
            return new Stats(average, 0, samples, estimatedCost, average > request.maxRadius() / 3);
        });
        trim();
    }

    public void recordFailure(LocatorRequest request, int failedRadius, int estimatedCost) {
        recordFailure(request, failedRadius, estimatedCost, DEFAULT_SCOPE);
    }

    public synchronized void recordFailure(LocatorRequest request, int failedRadius, int estimatedCost, Object worldScope) {
        history.compute(Key.of(request, worldScope), (key, previous) -> new Stats(
                previous == null ? 0 : previous.successDistance,
                Math.max(failedRadius, previous == null ? 0 : previous.failedRadius),
                previous == null ? 0 : previous.samples,
                estimatedCost,
                previous != null && previous.rareTarget));
        trim();
    }

    public int size() {
        return history.size();
    }

    public void clear() {
        history.clear();
    }

    private void trim() {
        if (history.size() <= MAX_TARGETS) {
            return;
        }
        history.keySet().stream()
                .sorted(Comparator.comparing(Key::stableValue))
                .limit(history.size() - MAX_TARGETS)
                .forEach(history::remove);
    }

    private record Key(Object worldScope, String dimension, String type, String target,
                       int x, int y, int z, int maxRadius) {
        static Key of(LocatorRequest request, Object worldScope) {
            return new Key(java.util.Objects.requireNonNull(worldScope), request.dimensionId(), request.targetType().name(), request.targetId(),
                    request.origin().getX(), request.origin().getY(), request.origin().getZ(), request.maxRadius());
        }

        String stableValue() {
            return dimension + "|" + type + "|" + target + "|" + x + "|" + y + "|" + z + "|" + maxRadius;
        }
    }

    private record Stats(int successDistance,
                         int failedRadius,
                         int samples,
                         int estimatedCost,
                         boolean rareTarget) {
    }
}
