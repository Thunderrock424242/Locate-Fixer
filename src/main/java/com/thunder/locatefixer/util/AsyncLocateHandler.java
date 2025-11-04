package com.thunder.locatefixer.util;

import com.mojang.datafixers.util.Pair;
import com.thunder.locatefixer.mixin.LocateCommandAccessor;
import com.thunder.locatefixer.mixin.LocateCommandInvoker;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceOrTagArgument;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.thunder.locatefixer.locatefixer.LOGGER;

public class AsyncLocateHandler {

    private static final int[] DEFAULT_RINGS = {6400, 16000, 32000, 64000, 128000, 256000};
    private static final ThreadFactory THREAD_FACTORY = buildThreadFactory();

    private static volatile LocateSettings SETTINGS = loadSettings();
    private static volatile ExecutorService LOCATE_EXECUTOR = buildExecutor(SETTINGS.threadCount());

    private static final ConcurrentMap<LocateCacheKey, LocateCacheEntry<Structure>> STRUCTURE_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<LocateCacheKey, LocateCacheEntry<Biome>> BIOME_CACHE = new ConcurrentHashMap<>();

    public static void locateStructureAsync(CommandSourceStack source, ResourceOrTagKeyArgument.Result<Structure> structure, BlockPos origin, ServerLevel level) {
        final LocateSettings settings = SETTINGS;
        CompletableFuture.runAsync(() -> {
            try {
                int[] rings = settings.rings();
                if (rings.length == 0) {
                    level.getServer().execute(() ->
                            source.sendFailure(Component.literal("❌ No locate search radii configured.")));
                    return;
                }

                LocateCacheKey cacheKey = LocateCacheKey.of(level, structure.asPrintable(), origin, settings.cacheGranularity());
                LocateCacheEntry<Structure> cacheEntry = getValidCacheEntry(STRUCTURE_CACHE, cacheKey, settings.cacheDurationMs());

                if (cacheEntry != null) {
                    BlockPos cachedPos = cacheEntry.pos();
                    int distance = horizontalDistance(origin, cachedPos);
                    if (distance <= settings.maxRadius()) {
                        Holder<Structure> holder = cacheEntry.holder();
                        level.getServer().execute(() -> {
                            source.sendSuccess(() -> Component.literal("✅ Using cached locate result."), false);
                            LocateResultHelper.sendResult(source, "commands.locate.structure.success", holder, origin, cachedPos, false);
                        });
                        return;
                    }
                }

                Registry<Structure> registry = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
                HolderSet<Structure> holders = LocateCommandInvoker.invokeGetHolders(structure, registry)
                        .orElseThrow(() -> LocateCommandAccessor.getStructureInvalid().create(structure.asPrintable()));

                int startIndex = findStartIndex(cacheEntry, origin, rings);

                for (int i = startIndex; i < rings.length; i++) {
                    int scanRadius = rings[i];
                    level.getServer().execute(() -> source.sendSuccess(() ->
                            Component.literal("🔍 Scanning up to " + scanRadius + " blocks..."), false));
                    LOGGER.info("[LocateFixer] Scanning for structure up to {} blocks", scanRadius);

                    Pair<BlockPos, Holder<Structure>> result = level.getChunkSource().getGenerator()
                            .findNearestMapStructure(level, holders, origin, scanRadius, false);

                    if (result != null) {
                        BlockPos pos = result.getFirst();
                        Holder<Structure> holder = result.getSecond();

                        STRUCTURE_CACHE.put(cacheKey, new LocateCacheEntry<>(pos, holder, System.currentTimeMillis()));

                        level.getServer().execute(() ->
                                LocateResultHelper.sendResult(source, "commands.locate.structure.success", holder, origin, pos, false)
                        );
                        return;
                    }
                }

                level.getServer().execute(() ->
                        source.sendFailure(Component.literal("❌ Structure not found within " + settings.maxRadius() + " blocks.")));

            } catch (Exception e) {
                level.getServer().execute(() ->
                        source.sendFailure(Component.literal("LocateFixer error (structure): " + e.getMessage())));
            }
        }, LOCATE_EXECUTOR);
    }

    public static void locateBiomeAsync(CommandSourceStack source, ResourceOrTagArgument.Result<Biome> biome, BlockPos origin, ServerLevel level) {
        final LocateSettings settings = SETTINGS;
        CompletableFuture.runAsync(() -> {
            try {
                int[] rings = settings.rings();
                if (rings.length == 0) {
                    level.getServer().execute(() ->
                            source.sendFailure(Component.literal("❌ No locate search radii configured.")));
                    return;
                }

                LocateCacheKey cacheKey = LocateCacheKey.of(level, biome.asPrintable(), origin, settings.cacheGranularity());
                LocateCacheEntry<Biome> cacheEntry = getValidCacheEntry(BIOME_CACHE, cacheKey, settings.cacheDurationMs());

                if (cacheEntry != null) {
                    BlockPos cachedPos = cacheEntry.pos();
                    int distance = horizontalDistance(origin, cachedPos);
                    if (distance <= settings.maxRadius()) {
                        Holder<Biome> holder = cacheEntry.holder();
                        level.getServer().execute(() -> {
                            source.sendSuccess(() -> Component.literal("✅ Using cached locate result."), false);
                            LocateResultHelper.sendResult(source, "commands.locate.biome.success", holder, origin, cachedPos, true);
                        });
                        return;
                    }
                }

                int startIndex = findStartIndex(cacheEntry, origin, rings);

                for (int i = startIndex; i < rings.length; i++) {
                    int scanRadius = rings[i];
                    level.getServer().execute(() -> source.sendSuccess(() ->
                            Component.literal("🔍 Scanning up to " + scanRadius + " blocks..."), false));
                    LOGGER.info("[LocateFixer] Scanning for biome up to {} blocks", scanRadius);

                    Pair<BlockPos, Holder<Biome>> result = level.findClosestBiome3d(biome, origin, scanRadius, computeSampleRadius(scanRadius, settings), computeSampleStep(scanRadius, settings));
                    if (result != null) {
                        BlockPos pos = result.getFirst();
                        Holder<Biome> holder = result.getSecond();

                        BIOME_CACHE.put(cacheKey, new LocateCacheEntry<>(pos, holder, System.currentTimeMillis()));

                        level.getServer().execute(() ->
                                LocateResultHelper.sendResult(source, "commands.locate.biome.success", holder, origin, pos, true)
                        );
                        return;
                    }
                }

                level.getServer().execute(() ->
                        source.sendFailure(Component.literal("❌ Biome not found within " + settings.maxRadius() + " blocks.")));

            } catch (Exception e) {
                level.getServer().execute(() ->
                        source.sendFailure(Component.literal("LocateFixer error (biome): " + e.getMessage())));
            }
        }, LOCATE_EXECUTOR);
    }

    public static void locatePoiAsync(CommandSourceStack source, ResourceOrTagArgument.Result<PoiType> poiType, BlockPos origin, ServerLevel level) {
        final LocateSettings settings = SETTINGS;
        CompletableFuture.runAsync(() -> {
            try {
                int poiRadius = settings.poiSearchRadius();
                LOGGER.info("[LocateFixer] Scanning for POI within {} blocks", poiRadius);
                level.getServer().execute(() -> source.sendSuccess(() ->
                        Component.literal("🔍 Scanning for POI up to " + poiRadius + " blocks..."), false));

                Optional<Pair<Holder<PoiType>, BlockPos>> result = level.getPoiManager()
                        .findClosestWithType(poiType, origin, poiRadius, PoiManager.Occupancy.ANY);

                if (result.isPresent()) {
                    Pair<Holder<PoiType>, BlockPos> found = result.get();
                    BlockPos pos = found.getSecond();
                    Holder<PoiType> holder = found.getFirst();

                    level.getServer().execute(() ->
                            LocateResultHelper.sendResult(source, "commands.locate.poi.success", holder, origin, pos, false)
                    );
                } else {
                    level.getServer().execute(() ->
                            source.sendFailure(Component.literal("❌ POI not found within " + poiRadius + " blocks."))
                    );
                }

            } catch (Exception e) {
                level.getServer().execute(() ->
                        source.sendFailure(Component.literal("LocateFixer error (POI): " + e.getMessage())));
            }
        }, LOCATE_EXECUTOR);
    }

    private static <T> LocateCacheEntry<T> getValidCacheEntry(ConcurrentMap<LocateCacheKey, LocateCacheEntry<T>> cache, LocateCacheKey key, long cacheDurationMs) {
        LocateCacheEntry<T> entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() - entry.timestamp() > cacheDurationMs) {
            cache.remove(key, entry);
            return null;
        }
        return entry;
    }

    private static int findStartIndex(LocateCacheEntry<?> entry, BlockPos origin, int[] rings) {
        if (entry == null) {
            return 0;
        }
        int distance = horizontalDistance(origin, entry.pos());
        for (int i = 0; i < rings.length; i++) {
            if (rings[i] >= distance) {
                return i;
            }
        }
        return 0;
    }

    private static int computeSampleRadius(int searchRadius, LocateSettings settings) {
        int computed = Math.max(16, searchRadius / 256);
        computed = Math.min(96, computed);
        double scaled = computed * settings.biomeSampleRadiusMultiplier();
        return (int) Math.max(16, Math.min(256, Math.round(scaled)));
    }

    private static int computeSampleStep(int searchRadius, LocateSettings settings) {
        int computed = Math.max(24, searchRadius / 192);
        computed = Math.min(128, computed);
        double scaled = computed * settings.biomeSampleStepMultiplier();
        return (int) Math.max(16, Math.min(256, Math.round(scaled)));
    }

    private static int horizontalDistance(BlockPos origin, BlockPos target) {
        int dx = target.getX() - origin.getX();
        int dz = target.getZ() - origin.getZ();
        return (int) Math.sqrt((double) dx * dx + (double) dz * dz);
    }

    public static void reloadConfig() {
        LocateSettings newSettings = loadSettings();
        synchronized (AsyncLocateHandler.class) {
            int previousThreads = SETTINGS.threadCount();
            SETTINGS = newSettings;
            if (LOCATE_EXECUTOR == null) {
                LOCATE_EXECUTOR = buildExecutor(newSettings.threadCount());
            } else if (previousThreads != newSettings.threadCount()) {
                ExecutorService oldExecutor = LOCATE_EXECUTOR;
                LOCATE_EXECUTOR = buildExecutor(newSettings.threadCount());
                oldExecutor.shutdown();
            }
        }
        LOGGER.info("[LocateFixer] Reloaded locate settings: {} rings, {}ms cache, {} thread(s).",
                newSettings.rings().length, newSettings.cacheDurationMs(), newSettings.threadCount());
    }

    private static LocateSettings loadSettings() {
        List<? extends Integer> configuredRings = com.thunder.locatefixer.config.LocateFixerConfig.SERVER.locateRings.get();
        List<Integer> ringList = new ArrayList<>();
        for (Integer ring : configuredRings) {
            if (ring != null && ring > 0) {
                ringList.add(ring);
            }
        }
        if (ringList.isEmpty()) {
            for (int ring : DEFAULT_RINGS) {
                ringList.add(ring);
            }
        }
        Collections.sort(ringList);
        int[] rings = ringList.stream().mapToInt(Integer::intValue).toArray();

        long cacheDurationMs = TimeUnit.MINUTES.toMillis(Math.max(1L, com.thunder.locatefixer.config.LocateFixerConfig.SERVER.cacheDurationMinutes.get()));
        int cacheGranularity = Math.max(1, com.thunder.locatefixer.config.LocateFixerConfig.SERVER.cacheChunkGranularity.get());
        int poiRadius = Math.max(16, com.thunder.locatefixer.config.LocateFixerConfig.SERVER.poiSearchRadius.get());
        int threadCount = Math.max(1, com.thunder.locatefixer.config.LocateFixerConfig.SERVER.locateThreadCount.get());
        double radiusMultiplier = Math.max(1.0D, com.thunder.locatefixer.config.LocateFixerConfig.SERVER.biomeSampleRadiusMultiplier.get());
        double stepMultiplier = Math.max(1.0D, com.thunder.locatefixer.config.LocateFixerConfig.SERVER.biomeSampleStepMultiplier.get());

        return new LocateSettings(rings, poiRadius, cacheDurationMs, cacheGranularity, threadCount, radiusMultiplier, stepMultiplier);
    }

    private static ExecutorService buildExecutor(int threadCount) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(threadCount, threadCount, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), THREAD_FACTORY);
        executor.allowCoreThreadTimeOut(false);
        return executor;
    }

    private static ThreadFactory buildThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("LocateFixer-AsyncLocate-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private record LocateCacheKey(String dimension, String target, int coarseChunkX, int coarseChunkZ) {
        private static LocateCacheKey of(ServerLevel level, String target, BlockPos origin, int granularity) {
            int chunkX = origin.getX() >> 4;
            int chunkZ = origin.getZ() >> 4;
            int coarseX = Math.floorDiv(chunkX, granularity);
            int coarseZ = Math.floorDiv(chunkZ, granularity);
            return new LocateCacheKey(level.dimension().location().toString(), target, coarseX, coarseZ);
        }
    }

    private record LocateCacheEntry<T>(BlockPos pos, Holder<T> holder, long timestamp) {
    }

    private record LocateSettings(int[] rings, int poiSearchRadius, long cacheDurationMs, int cacheGranularity,
                                  int threadCount, double biomeSampleRadiusMultiplier, double biomeSampleStepMultiplier) {
        int maxRadius() {
            return rings.length == 0 ? 0 : rings[rings.length - 1];
        }
    }
}

