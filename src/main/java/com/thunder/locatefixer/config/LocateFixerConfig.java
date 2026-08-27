package com.thunder.locatefixer.config;

import java.util.List;
import java.util.Objects;

public final class LocateFixerConfig {
    public static final ServerConfig SERVER = new ServerConfig();

    private LocateFixerConfig() {
    }

    public static void apply(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        SERVER.locateRings.set(snapshot.locateRings());
        SERVER.locateThreadCount.set(clamp(snapshot.locateThreadCount(), 1, 8));
        SERVER.cacheDurationMinutes.set(clamp(snapshot.cacheDurationMinutes(), 1L, 240L));
        SERVER.cacheChunkGranularity.set(clamp(snapshot.cacheChunkGranularity(), 1, 128));
        SERVER.biomeSampleRadiusMultiplier.set(clamp(snapshot.biomeSampleRadiusMultiplier(), 1.0D, 8.0D));
        SERVER.biomeSampleStepMultiplier.set(clamp(snapshot.biomeSampleStepMultiplier(), 1.0D, 8.0D));
        SERVER.poiSearchRadius.set(clamp(snapshot.poiSearchRadius(), 16, 4096));
        SERVER.enableFeatureLocateCommand.set(snapshot.enableFeatureLocateCommand());
        SERVER.enableNearestCommand.set(snapshot.enableNearestCommand());
        SERVER.enableCommandErrorFixer.set(snapshot.enableCommandErrorFixer());
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                SERVER.locateRings.get(),
                SERVER.locateThreadCount.get(),
                SERVER.cacheDurationMinutes.get(),
                SERVER.cacheChunkGranularity.get(),
                SERVER.biomeSampleRadiusMultiplier.get(),
                SERVER.biomeSampleStepMultiplier.get(),
                SERVER.poiSearchRadius.get(),
                SERVER.enableFeatureLocateCommand.get(),
                SERVER.enableNearestCommand.get(),
                SERVER.enableCommandErrorFixer.get()
        );
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static final class ServerConfig {
        public final Value<List<Integer>> locateRings = new Value<>(
                List.of(6400, 16000, 32000, 64000, 128000, 256000), List::copyOf);
        public final Value<Integer> locateThreadCount = new Value<>(1);
        public final Value<Long> cacheDurationMinutes = new Value<>(30L);
        public final Value<Integer> cacheChunkGranularity = new Value<>(8);
        public final Value<Double> biomeSampleRadiusMultiplier = new Value<>(1.5D);
        public final Value<Double> biomeSampleStepMultiplier = new Value<>(1.75D);
        public final Value<Integer> poiSearchRadius = new Value<>(256);
        public final Value<Boolean> enableFeatureLocateCommand = new Value<>(false);
        public final Value<Boolean> enableNearestCommand = new Value<>(false);
        public final Value<Boolean> enableCommandErrorFixer = new Value<>(true);

        private ServerConfig() {
        }
    }

    public static final class Value<T> {
        private final java.util.function.UnaryOperator<T> copier;
        private volatile T value;

        public Value(T value) {
            this(value, java.util.function.UnaryOperator.identity());
        }

        public Value(T value, java.util.function.UnaryOperator<T> copier) {
            this.copier = Objects.requireNonNull(copier, "copier");
            set(value);
        }

        public T get() {
            return copier.apply(value);
        }

        public void set(T value) {
            this.value = copier.apply(Objects.requireNonNull(value, "value"));
        }
    }

    public record Snapshot(
            List<Integer> locateRings,
            int locateThreadCount,
            long cacheDurationMinutes,
            int cacheChunkGranularity,
            double biomeSampleRadiusMultiplier,
            double biomeSampleStepMultiplier,
            int poiSearchRadius,
            boolean enableFeatureLocateCommand,
            boolean enableNearestCommand,
            boolean enableCommandErrorFixer
    ) {
        public Snapshot {
            locateRings = locateRings == null || locateRings.isEmpty()
                    ? List.of(6400, 16000, 32000, 64000, 128000, 256000)
                    : locateRings.stream().filter(radius -> radius != null && radius > 0).toList();
            if (locateRings.isEmpty()) {
                locateRings = List.of(6400, 16000, 32000, 64000, 128000, 256000);
            }
        }
    }
}
