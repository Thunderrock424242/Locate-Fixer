package com.thunder.locatefixer.forge;

import com.thunder.locatefixer.config.LocateFixerConfig;
import com.thunder.locatefixer.util.AsyncLocateHandler;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import java.util.List;

final class ForgeConfigBridge {
    private static final ForgeConfigSpec SPEC;
    private static final Values VALUES;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        VALUES = new Values(builder);
        SPEC = builder.build();
    }

    private ForgeConfigBridge() {
    }

    static void register(IEventBus modBus) {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SPEC);
        modBus.addListener(ForgeConfigBridge::onLoad);
        modBus.addListener(ForgeConfigBridge::onReload);
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
        private final ForgeConfigSpec.ConfigValue<List<? extends Integer>> locateRings;
        private final ForgeConfigSpec.IntValue locateThreadCount;
        private final ForgeConfigSpec.LongValue cacheDurationMinutes;
        private final ForgeConfigSpec.IntValue cacheChunkGranularity;
        private final ForgeConfigSpec.DoubleValue biomeSampleRadiusMultiplier;
        private final ForgeConfigSpec.DoubleValue biomeSampleStepMultiplier;
        private final ForgeConfigSpec.IntValue poiSearchRadius;
        private final ForgeConfigSpec.BooleanValue enableFeatureLocateCommand;
        private final ForgeConfigSpec.BooleanValue enableNearestCommand;
        private final ForgeConfigSpec.BooleanValue enableCommandErrorFixer;

        private Values(ForgeConfigSpec.Builder builder) {
            builder.push("locate");
            locateRings = builder.comment("Ordered radii in blocks used for locate searches.")
                    .defineList("locateRings", List.of(6400, 16000, 32000, 64000, 128000, 256000),
                            value -> value instanceof Integer integer && integer > 0);
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
