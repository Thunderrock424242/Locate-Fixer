package com.thunder.locatefixer.forge;

import com.thunder.locatefixer.config.LocateFixerConfig;
import com.thunder.locatefixer.LocateRuntime;
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
        private final ForgeConfigSpec.BooleanValue adaptiveSearchEnabled;
        private final ForgeConfigSpec.IntValue queueMaxPending;
        private final ForgeConfigSpec.IntValue searchTimeoutSeconds;
        private final ForgeConfigSpec.IntValue cacheMaxEntries;
        private final ForgeConfigSpec.BooleanValue persistentIndexEnabled;
        private final ForgeConfigSpec.IntValue persistentIndexMaxEntries;
        private final ForgeConfigSpec.IntValue persistentIndexExpiryDays;
        private final ForgeConfigSpec.IntValue persistentIndexVerificationMinutes;
        private final ForgeConfigSpec.IntValue teleportPreloadRadiusChunks;
        private final ForgeConfigSpec.IntValue teleportCountdownSeconds;
        private final ForgeConfigSpec.BooleanValue teleportCountdownEnabled;
        private final ForgeConfigSpec.IntValue teleportTimeoutSeconds;
        private final ForgeConfigSpec.IntValue safeHorizontalRadius;
        private final ForgeConfigSpec.IntValue safeVerticalRange;
        private final ForgeConfigSpec.BooleanValue allowWaterLanding;
        private final ForgeConfigSpec.BooleanValue allowLavaLanding;
        private final ForgeConfigSpec.BooleanValue allowFireLanding;
        private final ForgeConfigSpec.BooleanValue allowPowderSnowLanding;
        private final ForgeConfigSpec.BooleanValue returnPointEnabled;
        private final ForgeConfigSpec.BooleanValue enableBiomeSpyCompatibility;
        private final ForgeConfigSpec.BooleanValue enableCompassCompatibility;
        private final ForgeConfigSpec.BooleanValue enableAsyncLocatorConflictMode;
        private final ForgeConfigSpec.BooleanValue benchmarkEnabled;

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
