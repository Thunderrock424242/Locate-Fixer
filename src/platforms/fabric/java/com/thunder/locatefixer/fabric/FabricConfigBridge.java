package com.thunder.locatefixer.fabric;

import com.thunder.locatefixer.LocateFixerMod;
import com.thunder.locatefixer.LocateRuntime;
import com.thunder.locatefixer.config.LocateFixerConfig;
import com.thunder.locatefixer.util.AsyncLocateHandler;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class FabricConfigBridge {
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("locatefixer-server.toml");
    private static final LocateFixerConfig.Snapshot DEFAULTS = new LocateFixerConfig.Snapshot(
            List.of(6400, 16000, 32000, 64000, 128000, 256000),
            1, 30L, 8, 1.5D, 1.75D, 256, false, false, true
    );

    private FabricConfigBridge() {
    }

    static synchronized void load() {
        try {
            ensureConfigExists();
            Map<String, String> values = parse(Files.readAllLines(CONFIG_PATH, StandardCharsets.UTF_8));
            LocateFixerConfig.apply(new LocateFixerConfig.Snapshot(
                    parseRings(values.get("locateRings"), DEFAULTS.locateRings()),
                    parseInt(values.get("locateThreadCount"), DEFAULTS.locateThreadCount()),
                    parseLong(values.get("cacheDurationMinutes"), DEFAULTS.cacheDurationMinutes()),
                    parseInt(values.get("cacheChunkGranularity"), DEFAULTS.cacheChunkGranularity()),
                    parseDouble(values.get("biomeSampleRadiusMultiplier"), DEFAULTS.biomeSampleRadiusMultiplier()),
                    parseDouble(values.get("biomeSampleStepMultiplier"), DEFAULTS.biomeSampleStepMultiplier()),
                    parseInt(values.get("poiSearchRadius"), DEFAULTS.poiSearchRadius()),
                    parseBoolean(values.get("enableFeatureLocateCommand"), DEFAULTS.enableFeatureLocateCommand()),
                    parseBoolean(values.get("enableNearestCommand"), DEFAULTS.enableNearestCommand()),
                    parseBoolean(values.get("enableCommandErrorFixer"), DEFAULTS.enableCommandErrorFixer()),
                    parseBoolean(values.get("adaptiveSearchEnabled"), DEFAULTS.adaptiveSearchEnabled()),
                    parseInt(values.get("queueMaxPending"), DEFAULTS.queueMaxPending()),
                    parseInt(values.get("searchTimeoutSeconds"), DEFAULTS.searchTimeoutSeconds()),
                    parseInt(values.get("cacheMaxEntries"), DEFAULTS.cacheMaxEntries()),
                    parseBoolean(values.get("persistentIndexEnabled"), DEFAULTS.persistentIndexEnabled()),
                    parseInt(values.get("persistentIndexMaxEntries"), DEFAULTS.persistentIndexMaxEntries()),
                    parseInt(values.get("persistentIndexExpiryDays"), DEFAULTS.persistentIndexExpiryDays()),
                    parseInt(values.get("persistentIndexVerificationMinutes"), DEFAULTS.persistentIndexVerificationMinutes()),
                    parseInt(values.get("teleportPreloadRadiusChunks"), DEFAULTS.teleportPreloadRadiusChunks()),
                    parseInt(values.get("teleportCountdownSeconds"), DEFAULTS.teleportCountdownSeconds()),
                    parseBoolean(values.get("teleportCountdownEnabled"), DEFAULTS.teleportCountdownEnabled()),
                    parseInt(values.get("teleportTimeoutSeconds"), DEFAULTS.teleportTimeoutSeconds()),
                    parseInt(values.get("safeHorizontalRadius"), DEFAULTS.safeHorizontalRadius()),
                    parseInt(values.get("safeVerticalRange"), DEFAULTS.safeVerticalRange()),
                    parseBoolean(values.get("allowWaterLanding"), DEFAULTS.allowWaterLanding()),
                    parseBoolean(values.get("allowLavaLanding"), DEFAULTS.allowLavaLanding()),
                    parseBoolean(values.get("allowFireLanding"), DEFAULTS.allowFireLanding()),
                    parseBoolean(values.get("allowPowderSnowLanding"), DEFAULTS.allowPowderSnowLanding()),
                    parseBoolean(values.get("returnPointEnabled"), DEFAULTS.returnPointEnabled()),
                    parseBoolean(values.get("enableBiomeSpyCompatibility"), DEFAULTS.enableBiomeSpyCompatibility()),
                    parseBoolean(values.get("enableCompassCompatibility"), DEFAULTS.enableCompassCompatibility()),
                    parseBoolean(values.get("enableAsyncLocatorConflictMode"), DEFAULTS.enableAsyncLocatorConflictMode()),
                    parseBoolean(values.get("benchmarkEnabled"), DEFAULTS.benchmarkEnabled())
            ));
            AsyncLocateHandler.reloadConfig();
            LocateRuntime.reloadConfig();
        } catch (Exception exception) {
            LocateFixerMod.LOGGER.error("[LocateUnbound] Could not load Fabric config '{}'; using the last valid values.",
                    CONFIG_PATH, exception);
        }
    }

    private static void ensureConfigExists() throws IOException {
        if (Files.exists(CONFIG_PATH)) {
            return;
        }
        Files.createDirectories(CONFIG_PATH.getParent());
        Files.writeString(CONFIG_PATH, defaultToml(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> parse(List<String> lines) {
        Map<String, String> values = new HashMap<>();
        for (String rawLine : lines) {
            String line = rawLine;
            int comment = line.indexOf('#');
            if (comment >= 0) {
                line = line.substring(0, comment);
            }
            int separator = line.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if (!key.isEmpty() && !value.isEmpty()) {
                values.put(key, value);
            }
        }
        return values;
    }

    private static List<Integer> parseRings(String raw, List<Integer> fallback) {
        if (raw == null || !raw.startsWith("[") || !raw.endsWith("]")) {
            return fallback;
        }
        String body = raw.substring(1, raw.length() - 1).trim();
        if (body.isEmpty()) {
            return fallback;
        }
        List<Integer> rings = new ArrayList<>();
        for (String part : body.split(",")) {
            try {
                int radius = Integer.parseInt(part.trim());
                if (radius > 0) {
                    rings.add(radius);
                }
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return rings.isEmpty() ? fallback : List.copyOf(rings);
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return raw == null ? fallback : Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long parseLong(String raw, long fallback) {
        try {
            return raw == null ? fallback : Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double parseDouble(String raw, double fallback) {
        try {
            return raw == null ? fallback : Double.parseDouble(raw);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean parseBoolean(String raw, boolean fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw.equalsIgnoreCase("true")) {
            return true;
        }
        if (raw.equalsIgnoreCase("false")) {
            return false;
        }
        return fallback;
    }

    private static String defaultToml() {
        return """
                # Locate Unbound server configuration (the filename stays stable for compatibility)
                enableNearestCommand = false

                [search]
                locateRings = [6400, 16000, 32000, 64000, 128000, 256000]
                locateThreadCount = 1
                biomeSampleRadiusMultiplier = 1.5
                biomeSampleStepMultiplier = 1.75
                adaptiveSearchEnabled = true
                enableFeatureLocateCommand = false

                [queue]
                queueMaxPending = 32
                searchTimeoutSeconds = 120

                [cache]
                cacheDurationMinutes = 30
                cacheChunkGranularity = 8
                cacheMaxEntries = 512

                [index]
                persistentIndexEnabled = true
                persistentIndexMaxEntries = 8192
                persistentIndexExpiryDays = 90
                persistentIndexVerificationMinutes = 30

                [teleport]
                teleportPreloadRadiusChunks = 1
                teleportCountdownSeconds = 5
                teleportCountdownEnabled = true
                teleportTimeoutSeconds = 30
                safeHorizontalRadius = 8
                safeVerticalRange = 48
                allowWaterLanding = false
                allowLavaLanding = false
                allowFireLanding = false
                allowPowderSnowLanding = false
                returnPointEnabled = false

                [integrations]
                enableBiomeSpyCompatibility = true
                enableCompassCompatibility = true
                enableAsyncLocatorConflictMode = true

                [benchmark]
                benchmarkEnabled = false

                [commands]
                enableCommandErrorFixer = true

                [poi]
                poiSearchRadius = 256
                """;
    }
}
