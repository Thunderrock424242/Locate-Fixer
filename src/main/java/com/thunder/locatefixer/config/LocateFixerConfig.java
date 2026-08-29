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
        SERVER.adaptiveSearchEnabled.set(snapshot.adaptiveSearchEnabled());
        SERVER.queueMaxPending.set(clamp(snapshot.queueMaxPending(), 1, 1024));
        SERVER.searchTimeoutSeconds.set(clamp(snapshot.searchTimeoutSeconds(), 5, 3600));
        SERVER.cacheMaxEntries.set(clamp(snapshot.cacheMaxEntries(), 16, 65536));
        SERVER.persistentIndexEnabled.set(snapshot.persistentIndexEnabled());
        SERVER.persistentIndexMaxEntries.set(clamp(snapshot.persistentIndexMaxEntries(), 64, 100000));
        SERVER.persistentIndexExpiryDays.set(clamp(snapshot.persistentIndexExpiryDays(), 1, 3650));
        SERVER.persistentIndexVerificationMinutes.set(clamp(snapshot.persistentIndexVerificationMinutes(), 1, 10080));
        SERVER.teleportPreloadRadiusChunks.set(clamp(snapshot.teleportPreloadRadiusChunks(), 0, 8));
        SERVER.teleportCountdownSeconds.set(clamp(snapshot.teleportCountdownSeconds(), 0, 60));
        SERVER.teleportCountdownEnabled.set(snapshot.teleportCountdownEnabled());
        SERVER.teleportTimeoutSeconds.set(clamp(snapshot.teleportTimeoutSeconds(), 5, 300));
        SERVER.safeHorizontalRadius.set(clamp(snapshot.safeHorizontalRadius(), 1, 32));
        SERVER.safeVerticalRange.set(clamp(snapshot.safeVerticalRange(), 4, 128));
        SERVER.allowWaterLanding.set(snapshot.allowWaterLanding());
        SERVER.allowLavaLanding.set(snapshot.allowLavaLanding());
        SERVER.allowFireLanding.set(snapshot.allowFireLanding());
        SERVER.allowPowderSnowLanding.set(snapshot.allowPowderSnowLanding());
        SERVER.returnPointEnabled.set(snapshot.returnPointEnabled());
        SERVER.enableBiomeSpyCompatibility.set(snapshot.enableBiomeSpyCompatibility());
        SERVER.enableCompassCompatibility.set(snapshot.enableCompassCompatibility());
        SERVER.enableAsyncLocatorConflictMode.set(snapshot.enableAsyncLocatorConflictMode());
        SERVER.benchmarkEnabled.set(snapshot.benchmarkEnabled());
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
                SERVER.enableCommandErrorFixer.get(),
                SERVER.adaptiveSearchEnabled.get(),
                SERVER.queueMaxPending.get(),
                SERVER.searchTimeoutSeconds.get(),
                SERVER.cacheMaxEntries.get(),
                SERVER.persistentIndexEnabled.get(),
                SERVER.persistentIndexMaxEntries.get(),
                SERVER.persistentIndexExpiryDays.get(),
                SERVER.persistentIndexVerificationMinutes.get(),
                SERVER.teleportPreloadRadiusChunks.get(),
                SERVER.teleportCountdownSeconds.get(),
                SERVER.teleportCountdownEnabled.get(),
                SERVER.teleportTimeoutSeconds.get(),
                SERVER.safeHorizontalRadius.get(),
                SERVER.safeVerticalRange.get(),
                SERVER.allowWaterLanding.get(),
                SERVER.allowLavaLanding.get(),
                SERVER.allowFireLanding.get(),
                SERVER.allowPowderSnowLanding.get(),
                SERVER.returnPointEnabled.get(),
                SERVER.enableBiomeSpyCompatibility.get(),
                SERVER.enableCompassCompatibility.get(),
                SERVER.enableAsyncLocatorConflictMode.get(),
                SERVER.benchmarkEnabled.get()
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
        public final Value<Boolean> adaptiveSearchEnabled = new Value<>(true);
        public final Value<Integer> queueMaxPending = new Value<>(32);
        public final Value<Integer> searchTimeoutSeconds = new Value<>(120);
        public final Value<Integer> cacheMaxEntries = new Value<>(512);
        public final Value<Boolean> persistentIndexEnabled = new Value<>(true);
        public final Value<Integer> persistentIndexMaxEntries = new Value<>(8192);
        public final Value<Integer> persistentIndexExpiryDays = new Value<>(90);
        public final Value<Integer> persistentIndexVerificationMinutes = new Value<>(30);
        public final Value<Integer> teleportPreloadRadiusChunks = new Value<>(1);
        public final Value<Integer> teleportCountdownSeconds = new Value<>(5);
        public final Value<Boolean> teleportCountdownEnabled = new Value<>(true);
        public final Value<Integer> teleportTimeoutSeconds = new Value<>(30);
        public final Value<Integer> safeHorizontalRadius = new Value<>(8);
        public final Value<Integer> safeVerticalRange = new Value<>(48);
        public final Value<Boolean> allowWaterLanding = new Value<>(false);
        public final Value<Boolean> allowLavaLanding = new Value<>(false);
        public final Value<Boolean> allowFireLanding = new Value<>(false);
        public final Value<Boolean> allowPowderSnowLanding = new Value<>(false);
        public final Value<Boolean> returnPointEnabled = new Value<>(false);
        public final Value<Boolean> enableBiomeSpyCompatibility = new Value<>(true);
        public final Value<Boolean> enableCompassCompatibility = new Value<>(true);
        public final Value<Boolean> enableAsyncLocatorConflictMode = new Value<>(true);
        public final Value<Boolean> benchmarkEnabled = new Value<>(false);

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
            boolean enableCommandErrorFixer,
            boolean adaptiveSearchEnabled,
            int queueMaxPending,
            int searchTimeoutSeconds,
            int cacheMaxEntries,
            boolean persistentIndexEnabled,
            int persistentIndexMaxEntries,
            int persistentIndexExpiryDays,
            int persistentIndexVerificationMinutes,
            int teleportPreloadRadiusChunks,
            int teleportCountdownSeconds,
            boolean teleportCountdownEnabled,
            int teleportTimeoutSeconds,
            int safeHorizontalRadius,
            int safeVerticalRange,
            boolean allowWaterLanding,
            boolean allowLavaLanding,
            boolean allowFireLanding,
            boolean allowPowderSnowLanding,
            boolean returnPointEnabled,
            boolean enableBiomeSpyCompatibility,
            boolean enableCompassCompatibility,
            boolean enableAsyncLocatorConflictMode,
            boolean benchmarkEnabled
    ) {
        public Snapshot(List<Integer> locateRings,
                        int locateThreadCount,
                        long cacheDurationMinutes,
                        int cacheChunkGranularity,
                        double biomeSampleRadiusMultiplier,
                        double biomeSampleStepMultiplier,
                        int poiSearchRadius,
                        boolean enableFeatureLocateCommand,
                        boolean enableNearestCommand,
                        boolean enableCommandErrorFixer) {
            this(locateRings, locateThreadCount, cacheDurationMinutes, cacheChunkGranularity,
                    biomeSampleRadiusMultiplier, biomeSampleStepMultiplier, poiSearchRadius,
                    enableFeatureLocateCommand, enableNearestCommand, enableCommandErrorFixer,
                    true, 32, 120, 512, true, 8192, 90, 30,
                    1, 5, true, 30, 8, 48,
                    false, false, false, false, false,
                    true, true, true, false);
        }

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
