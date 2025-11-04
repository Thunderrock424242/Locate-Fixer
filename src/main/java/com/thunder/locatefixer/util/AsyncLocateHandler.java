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

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.thunder.locatefixer.locatefixer.LOGGER;

public class AsyncLocateHandler {

    private static final int[] RINGS = {6400, 16000, 32000, 64000, 128000, 256000};
    private static final int POI_SEARCH_RADIUS = 256;
    private static final long CACHE_DURATION_MS = TimeUnit.MINUTES.toMillis(10);

    private static final ExecutorService LOCATE_EXECUTOR = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 2), buildThreadFactory());

    private static final ConcurrentMap<LocateCacheKey, LocateCacheEntry<Structure>> STRUCTURE_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<LocateCacheKey, LocateCacheEntry<Biome>> BIOME_CACHE = new ConcurrentHashMap<>();

    public static void locateStructureAsync(CommandSourceStack source, ResourceOrTagKeyArgument.Result<Structure> structure, BlockPos origin, ServerLevel level) {
        CompletableFuture.runAsync(() -> {
            try {
                LocateCacheKey cacheKey = LocateCacheKey.of(level, structure.asPrintable());
                LocateCacheEntry<Structure> cacheEntry = getValidCacheEntry(STRUCTURE_CACHE, cacheKey);

                if (cacheEntry != null) {
                    BlockPos cachedPos = cacheEntry.pos();
                    int distance = horizontalDistance(origin, cachedPos);
                    if (distance <= RINGS[RINGS.length - 1]) {
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

                int startIndex = findStartIndex(cacheEntry, origin);

                for (int i = startIndex; i < RINGS.length; i++) {
                    int scanRadius = RINGS[i];
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

                level.getServer().execute(() -> {
                    source.sendFailure(Component.literal("❌ Structure not found within 256,000 blocks."));
                });

            } catch (Exception e) {
                level.getServer().execute(() -> {
                    source.sendFailure(Component.literal("LocateFixer error (structure): " + e.getMessage()));
                });
            }
        }, LOCATE_EXECUTOR);
    }

    public static void locateBiomeAsync(CommandSourceStack source, ResourceOrTagArgument.Result<Biome> biome, BlockPos origin, ServerLevel level) {
        CompletableFuture.runAsync(() -> {
            try {
                LocateCacheKey cacheKey = LocateCacheKey.of(level, biome.asPrintable());
                LocateCacheEntry<Biome> cacheEntry = getValidCacheEntry(BIOME_CACHE, cacheKey);

                if (cacheEntry != null) {
                    BlockPos cachedPos = cacheEntry.pos();
                    int distance = horizontalDistance(origin, cachedPos);
                    if (distance <= RINGS[RINGS.length - 1]) {
                        Holder<Biome> holder = cacheEntry.holder();
                        level.getServer().execute(() -> {
                            source.sendSuccess(() -> Component.literal("✅ Using cached locate result."), false);
                            LocateResultHelper.sendResult(source, "commands.locate.biome.success", holder, origin, cachedPos, true);
                        });
                        return;
                    }
                }

                int startIndex = findStartIndex(cacheEntry, origin);

                for (int i = startIndex; i < RINGS.length; i++) {
                    int scanRadius = RINGS[i];
                    level.getServer().execute(() -> source.sendSuccess(() ->
                            Component.literal("🔍 Scanning up to " + scanRadius + " blocks..."), false));
                    LOGGER.info("[LocateFixer] Scanning for biome up to {} blocks", scanRadius);

                    Pair<BlockPos, Holder<Biome>> result = level.findClosestBiome3d(biome, origin, scanRadius, computeSampleRadius(scanRadius), computeSampleStep(scanRadius));
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

                level.getServer().execute(() -> {
                    source.sendFailure(Component.literal("❌ Biome not found within 256,000 blocks."));
                });

            } catch (Exception e) {
                level.getServer().execute(() -> {
                    source.sendFailure(Component.literal("LocateFixer error (biome): " + e.getMessage()));
                });
            }
        }, LOCATE_EXECUTOR);
    }

    public static void locatePoiAsync(CommandSourceStack source, ResourceOrTagArgument.Result<PoiType> poiType, BlockPos origin, ServerLevel level) {
        CompletableFuture.runAsync(() -> {
            try {
                LOGGER.info("[LocateFixer] Scanning for POI within {} blocks", POI_SEARCH_RADIUS);
                level.getServer().execute(() -> source.sendSuccess(() ->
                        Component.literal("🔍 Scanning for POI up to " + POI_SEARCH_RADIUS + " blocks..."), false));

                Optional<Pair<Holder<PoiType>, BlockPos>> result = level.getPoiManager()
                        .findClosestWithType(poiType, origin, POI_SEARCH_RADIUS, PoiManager.Occupancy.ANY);

                if (result.isPresent()) {
                    Pair<Holder<PoiType>, BlockPos> found = result.get();
                    BlockPos pos = found.getSecond();
                    Holder<PoiType> holder = found.getFirst();

                    level.getServer().execute(() ->
                            LocateResultHelper.sendResult(source, "commands.locate.poi.success", holder, origin, pos, false)
                    );
                } else {
                    level.getServer().execute(() ->
                            source.sendFailure(Component.literal("❌ POI not found within " + POI_SEARCH_RADIUS + " blocks."))
                    );
                }

            } catch (Exception e) {
                level.getServer().execute(() -> {
                    source.sendFailure(Component.literal("LocateFixer error (POI): " + e.getMessage()));
                });
            }
        }, LOCATE_EXECUTOR);
    }

    private static <T> LocateCacheEntry<T> getValidCacheEntry(ConcurrentMap<LocateCacheKey, LocateCacheEntry<T>> cache, LocateCacheKey key) {
        LocateCacheEntry<T> entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() - entry.timestamp() > CACHE_DURATION_MS) {
            cache.remove(key, entry);
            return null;
        }
        return entry;
    }

    private static int findStartIndex(LocateCacheEntry<?> entry, BlockPos origin) {
        if (entry == null) {
            return 0;
        }
        int distance = horizontalDistance(origin, entry.pos());
        for (int i = 0; i < RINGS.length; i++) {
            if (RINGS[i] >= distance) {
                return i;
            }
        }
        return 0;
    }

    private static int computeSampleRadius(int searchRadius) {
        int computed = Math.max(16, searchRadius / 256);
        return Math.min(96, computed);
    }

    private static int computeSampleStep(int searchRadius) {
        int computed = Math.max(24, searchRadius / 192);
        return Math.min(128, computed);
    }

    private static int horizontalDistance(BlockPos origin, BlockPos target) {
        int dx = target.getX() - origin.getX();
        int dz = target.getZ() - origin.getZ();
        return (int) Math.sqrt((double) dx * dx + (double) dz * dz);
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

    private record LocateCacheKey(String dimension, String target) {
        private static LocateCacheKey of(ServerLevel level, String target) {
            return new LocateCacheKey(level.dimension().location().toString(), target);
        }
    }

    private record LocateCacheEntry<T>(BlockPos pos, Holder<T> holder, long timestamp) {
    }
}

