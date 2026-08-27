package com.thunder.locatefixer.neoforge;

import com.thunder.locatefixer.config.LocateFixerConfig;
import com.thunder.locatefixer.util.AsyncLocateHandler;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

final class NeoForgeConfigBridge {
    private static final ModConfigSpec SPEC;
    private static final Values VALUES;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        VALUES = new Values(builder);
        SPEC = builder.build();
    }

    private NeoForgeConfigBridge() {
    }

    static void register(IEventBus modBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, SPEC);
        modBus.addListener(NeoForgeConfigBridge::onLoad);
        modBus.addListener(NeoForgeConfigBridge::onReload);
    }

    private static void onLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) {
            sync();
        }
    }

    private static void onReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            sync();
        }
    }

    private static void sync() {
        LocateFixerConfig.apply(new LocateFixerConfig.Snapshot(
                VALUES.locateRings.get().stream().map(Number::intValue).toList(),
                VALUES.locateThreadCount.get(),
                VALUES.cacheDurationMinutes.get(),
                VALUES.cacheChunkGranularity.get(),
                VALUES.biomeSampleRadiusMultiplier.get(),
                VALUES.biomeSampleStepMultiplier.get(),
                VALUES.poiSearchRadius.get(),
                VALUES.enableFeatureLocateCommand.get(),
                VALUES.enableNearestCommand.get(),
                VALUES.enableCommandErrorFixer.get()
        ));
        AsyncLocateHandler.reloadConfig();
    }

    private static final class Values {
        private final ModConfigSpec.ConfigValue<List<? extends Integer>> locateRings;
        private final ModConfigSpec.IntValue locateThreadCount;
        private final ModConfigSpec.LongValue cacheDurationMinutes;
        private final ModConfigSpec.IntValue cacheChunkGranularity;
        private final ModConfigSpec.DoubleValue biomeSampleRadiusMultiplier;
        private final ModConfigSpec.DoubleValue biomeSampleStepMultiplier;
        private final ModConfigSpec.IntValue poiSearchRadius;
        private final ModConfigSpec.BooleanValue enableFeatureLocateCommand;
        private final ModConfigSpec.BooleanValue enableNearestCommand;
        private final ModConfigSpec.BooleanValue enableCommandErrorFixer;

        private Values(ModConfigSpec.Builder builder) {
            builder.push("locate");
            locateRings = builder.comment("Ordered radii in blocks used for locate searches.")
                    .defineList("locateRings", List.of(6400, 16000, 32000, 64000, 128000, 256000),
                            () -> 6400, value -> value instanceof Integer integer && integer > 0);
            locateThreadCount = builder.defineInRange("locateThreadCount", 1, 1, 8);
            cacheDurationMinutes = builder.defineInRange("cacheDurationMinutes", 30L, 1L, 240L);
            cacheChunkGranularity = builder.defineInRange("cacheChunkGranularity", 8, 1, 128);
            biomeSampleRadiusMultiplier = builder.defineInRange("biomeSampleRadiusMultiplier", 1.5D, 1.0D, 8.0D);
            biomeSampleStepMultiplier = builder.defineInRange("biomeSampleStepMultiplier", 1.75D, 1.0D, 8.0D);
            enableFeatureLocateCommand = builder.define("enableFeatureLocateCommand", false);
            builder.pop();

            enableNearestCommand = builder.define("enableNearestCommand", false);
            builder.push("commands");
            enableCommandErrorFixer = builder.define("enableCommandErrorFixer", true);
            builder.pop();
            builder.push("poi");
            poiSearchRadius = builder.defineInRange("poiSearchRadius", 256, 16, 4096);
            builder.pop();
        }
    }
}
