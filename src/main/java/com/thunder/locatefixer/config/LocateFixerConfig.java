package com.thunder.locatefixer.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;
import java.util.function.Supplier;

public final class LocateFixerConfig {
    public static final ModConfigSpec SERVER_SPEC;
    public static final ServerConfig SERVER;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        SERVER = new ServerConfig(builder);
        SERVER_SPEC = builder.build();
    }

    private LocateFixerConfig() {
    }

    public static final class ServerConfig {
        public final ModConfigSpec.ConfigValue<List<? extends Integer>> locateRings;
        public final ModConfigSpec.IntValue locateThreadCount;
        public final ModConfigSpec.LongValue cacheDurationMinutes;
        public final ModConfigSpec.IntValue cacheChunkGranularity;
        public final ModConfigSpec.DoubleValue biomeSampleRadiusMultiplier;
        public final ModConfigSpec.DoubleValue biomeSampleStepMultiplier;
        public final ModConfigSpec.IntValue poiSearchRadius;

        private ServerConfig(ModConfigSpec.Builder builder) {
            builder.push("locate");
            locateRings = builder
                    .comment("Ordered list of radii (in blocks) used when scanning for structures/biomes.")
                    .defineList("locateRings", List.of(6400, 16000, 32000, 64000, 128000, 256000),
                            defaultLocateRingSupplier(),
                            o -> o instanceof Integer && (Integer) o > 0);
            locateThreadCount = builder
                    .comment("Number of asynchronous locate worker threads. Use 1 to force sequential processing.")
                    .defineInRange("locateThreadCount", 1, 1, 8);
            cacheDurationMinutes = builder
                    .comment("How long (in minutes) locate results remain cached before expiring.")
                    .defineInRange("cacheDurationMinutes", 30L, 1L, 240L);
            cacheChunkGranularity = builder
                    .comment("Granularity (in chunks) used when caching locate results. Higher values share results across wider areas.")
                    .defineInRange("cacheChunkGranularity", 8, 1, 128);
            biomeSampleRadiusMultiplier = builder
                    .comment("Multiplier applied to the computed biome sample radius to reduce sampling frequency.")
                    .defineInRange("biomeSampleRadiusMultiplier", 1.5D, 1.0D, 8.0D);
            biomeSampleStepMultiplier = builder
                    .comment("Multiplier applied to the computed biome sample step to reduce sampling frequency.")
                    .defineInRange("biomeSampleStepMultiplier", 1.75D, 1.0D, 8.0D);
            builder.pop();

            builder.push("poi");
            poiSearchRadius = builder
                    .comment("Radius (in blocks) used when searching for points of interest.")
                    .defineInRange("poiSearchRadius", 256, 16, 4096);
            builder.pop();
        }
        private static Supplier<Integer> defaultLocateRingSupplier() {
            return () -> 6400;
        }
    }
}
