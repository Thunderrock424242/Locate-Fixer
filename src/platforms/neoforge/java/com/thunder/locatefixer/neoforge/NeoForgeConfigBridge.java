package com.thunder.locatefixer.neoforge;

import com.thunder.locatefixer.config.LocateFixerConfig;
import com.thunder.locatefixer.LocateRuntime;
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
                VALUES.enableCommandErrorFixer.get(),
                VALUES.adaptiveSearchEnabled.get(),
                VALUES.queueMaxPending.get(),
                VALUES.searchTimeoutSeconds.get(),
                VALUES.cacheMaxEntries.get(),
                VALUES.persistentIndexEnabled.get(),
                VALUES.persistentIndexMaxEntries.get(),
                VALUES.persistentIndexExpiryDays.get(),
                VALUES.persistentIndexVerificationMinutes.get(),
                VALUES.teleportPreloadRadiusChunks.get(),
                VALUES.teleportCountdownSeconds.get(),
                VALUES.teleportCountdownEnabled.get(),
                VALUES.teleportTimeoutSeconds.get(),
                VALUES.safeHorizontalRadius.get(),
                VALUES.safeVerticalRange.get(),
                VALUES.allowWaterLanding.get(),
                VALUES.allowLavaLanding.get(),
                VALUES.allowFireLanding.get(),
                VALUES.allowPowderSnowLanding.get(),
                VALUES.returnPointEnabled.get(),
                VALUES.enableBiomeSpyCompatibility.get(),
                VALUES.enableCompassCompatibility.get(),
                VALUES.enableAsyncLocatorConflictMode.get(),
                VALUES.benchmarkEnabled.get()
        ));
        AsyncLocateHandler.reloadConfig();
        LocateRuntime.reloadConfig();
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
        private final ModConfigSpec.BooleanValue adaptiveSearchEnabled;
        private final ModConfigSpec.IntValue queueMaxPending;
        private final ModConfigSpec.IntValue searchTimeoutSeconds;
        private final ModConfigSpec.IntValue cacheMaxEntries;
        private final ModConfigSpec.BooleanValue persistentIndexEnabled;
        private final ModConfigSpec.IntValue persistentIndexMaxEntries;
        private final ModConfigSpec.IntValue persistentIndexExpiryDays;
        private final ModConfigSpec.IntValue persistentIndexVerificationMinutes;
        private final ModConfigSpec.IntValue teleportPreloadRadiusChunks;
        private final ModConfigSpec.IntValue teleportCountdownSeconds;
        private final ModConfigSpec.BooleanValue teleportCountdownEnabled;
        private final ModConfigSpec.IntValue teleportTimeoutSeconds;
        private final ModConfigSpec.IntValue safeHorizontalRadius;
        private final ModConfigSpec.IntValue safeVerticalRange;
        private final ModConfigSpec.BooleanValue allowWaterLanding;
        private final ModConfigSpec.BooleanValue allowLavaLanding;
        private final ModConfigSpec.BooleanValue allowFireLanding;
        private final ModConfigSpec.BooleanValue allowPowderSnowLanding;
        private final ModConfigSpec.BooleanValue returnPointEnabled;
        private final ModConfigSpec.BooleanValue enableBiomeSpyCompatibility;
        private final ModConfigSpec.BooleanValue enableCompassCompatibility;
        private final ModConfigSpec.BooleanValue enableAsyncLocatorConflictMode;
        private final ModConfigSpec.BooleanValue benchmarkEnabled;

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
            adaptiveSearchEnabled = builder.define("adaptiveSearchEnabled", true);
            enableFeatureLocateCommand = builder.define("enableFeatureLocateCommand", false);
            builder.pop();

            builder.push("queue");
            queueMaxPending = builder.defineInRange("maxPending", 32, 1, 1024);
            searchTimeoutSeconds = builder.defineInRange("searchTimeoutSeconds", 120, 5, 3600);
            builder.pop();

            builder.push("cache");
            cacheMaxEntries = builder.defineInRange("maxEntries", 512, 16, 65536);
            builder.pop();

            builder.push("index");
            persistentIndexEnabled = builder.define("enabled", true);
            persistentIndexMaxEntries = builder.defineInRange("maxEntries", 8192, 64, 100000);
            persistentIndexExpiryDays = builder.defineInRange("expiryDays", 90, 1, 3650);
            persistentIndexVerificationMinutes = builder.defineInRange("verificationMinutes", 30, 1, 10080);
            builder.pop();

            builder.push("teleport");
            teleportPreloadRadiusChunks = builder.defineInRange("preloadRadiusChunks", 1, 0, 8);
            teleportCountdownSeconds = builder.defineInRange("countdownSeconds", 5, 0, 60);
            teleportCountdownEnabled = builder.define("countdownEnabled", true);
            teleportTimeoutSeconds = builder.defineInRange("timeoutSeconds", 30, 5, 300);
            safeHorizontalRadius = builder.defineInRange("safeHorizontalRadius", 8, 1, 32);
            safeVerticalRange = builder.defineInRange("safeVerticalRange", 48, 4, 128);
            allowWaterLanding = builder.define("allowWaterLanding", false);
            allowLavaLanding = builder.define("allowLavaLanding", false);
            allowFireLanding = builder.define("allowFireLanding", false);
            allowPowderSnowLanding = builder.define("allowPowderSnowLanding", false);
            returnPointEnabled = builder.define("returnPointEnabled", false);
            builder.pop();

            builder.push("integrations");
            enableBiomeSpyCompatibility = builder.define("biomeSpy", true);
            enableCompassCompatibility = builder.define("compassMods", true);
            enableAsyncLocatorConflictMode = builder.define("asyncLocatorConflictMode", true);
            builder.pop();

            builder.push("benchmark");
            benchmarkEnabled = builder.define("enabled", false);
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
