package com.thunder.locatefixer.search;

import com.thunder.locatefixer.api.LocatorRequest;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Small bounded in-memory history used by the adaptive planner; persistent discoveries remain separate. */
public final class SearchHistoryTracker {
    private static final int MAX_TARGETS = 2048;
    private final Map<Key, Stats> history = new ConcurrentHashMap<>();

    public SearchPlanningContext contextFor(LocatorRequest request) {
        Stats stats = history.get(Key.of(request));
        if (stats == null) {
            return SearchPlanningContext.empty();
        }
        return new SearchPlanningContext(stats.successDistance, stats.failedRadius,
                stats.estimatedCost, stats.rareTarget);
    }

    public void recordSuccess(LocatorRequest request, int distance, int estimatedCost) {
        history.compute(Key.of(request), (key, previous) -> {
            int samples = previous == null ? 1 : Math.min(64, previous.samples + 1);
            int oldDistance = previous == null ? distance : previous.successDistance;
            int average = Math.max(1, (oldDistance * (samples - 1) + Math.max(1, distance)) / samples);
            return new Stats(average, 0, samples, estimatedCost, average > request.maxRadius() / 3);
        });
        trim();
    }

    public void recordFailure(LocatorRequest request, int failedRadius, int estimatedCost) {
        history.compute(Key.of(request), (key, previous) -> new Stats(
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

    private record Key(String dimension, String type, String target) {
        static Key of(LocatorRequest request) {
            return new Key(request.dimensionId(), request.targetType().name(), request.targetId());
        }

        String stableValue() {
            return dimension + "|" + type + "|" + target;
        }
    }

    private record Stats(int successDistance,
                         int failedRadius,
                         int samples,
                         int estimatedCost,
                         boolean rareTarget) {
    }
}
