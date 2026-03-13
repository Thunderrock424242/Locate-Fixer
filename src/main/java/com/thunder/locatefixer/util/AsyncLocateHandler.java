package com.thunder.locatefixer.util;

import com.mojang.datafixers.util.Pair;
import com.thunder.locatefixer.mixin.LocateCommandAccessor;
import com.thunder.locatefixer.mixin.LocateCommandInvoker;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceOrTagArgument;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
    private static final LocateSettings DEFAULT_SETTINGS = new LocateSettings(
            DEFAULT_RINGS,
            1024,
            TimeUnit.MINUTES.toMillis(10),
            8,
            2,
            1.0D,
            1.0D
    );
    private static final ThreadFactory THREAD_FACTORY = buildThreadFactory();

    private static volatile LocateSettings SETTINGS = DEFAULT_SETTINGS;
    private static volatile ExecutorService LOCATE_EXECUTOR = buildExecutor(SETTINGS.threadCount());

    private static final ConcurrentMap<LocateCacheKey, LocateCacheEntry<Structure>> STRUCTURE_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<LocateCacheKey, LocateCacheEntry<Biome>> BIOME_CACHE = new ConcurrentHashMap<>();

    public static void runAsyncTask(String taskName, Runnable task) {
        CompletableFuture.runAsync(() -> {
            try {
                task.run();
            } catch (Exception e) {
                LOGGER.error("[LocateFixer] Async task '{}' failed", taskName, e);
            }
        }, LOCATE_EXECUTOR);
    }

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
                            BlockPos surfacePos = structureTeleportTarget(level, cachedPos);
                            source.sendSuccess(() -> Component.literal("✅ Using cached locate result."), false);
                            LocateResultHelper.sendResult(source, "commands.locate.structure.success", holder, origin, surfacePos, true);
                        });
                        return;
                    }
                }

                Registry<Structure> registry = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
                HolderSet<Structure> holders = LocateCommandInvoker.invokeGetHolders(structure, registry)
                        .orElseThrow(() -> LocateCommandAccessor.getStructureInvalid().create(structure.asPrintable()));

                int startIndex = findStartIndex(cacheEntry, origin, rings);
                int totalSteps = Math.max(1, rings.length - startIndex);
                long startedAt = System.currentTimeMillis();

                for (int i = startIndex; i < rings.length; i++) {
                    int scanRadius = rings[i];
                    int step = (i - startIndex) + 1;
                    sendRingProgressUpdate(level, source, scanRadius, step, totalSteps, startedAt);
                    LOGGER.info("[LocateFixer] Scanning for structure up to {} blocks", scanRadius);

                    Pair<BlockPos, Holder<Structure>> result = level.getChunkSource().getGenerator()
                            .findNearestMapStructure(level, holders, origin, scanRadius, false);

                    if (result != null) {
                        BlockPos pos = result.getFirst();
                        Holder<Structure> holder = result.getSecond();

                        STRUCTURE_CACHE.put(cacheKey, new LocateCacheEntry<>(pos, holder, System.currentTimeMillis()));

                        level.getServer().execute(() -> {
                            BlockPos surfacePos = structureTeleportTarget(level, pos);
                            LocateResultHelper.sendResult(source, "commands.locate.structure.success", holder, origin, surfacePos, true);
                        });
                        return;
                    }
                }

                level.getServer().execute(() ->
                        source.sendFailure(Component.literal("❌ Structure not found within " + settings.maxRadius() + " blocks.")));

            } catch (Exception e) {
                LOGGER.error("[LocateFixer] Unexpected error while locating structure", e);
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
                int totalSteps = Math.max(1, rings.length - startIndex);
                long startedAt = System.currentTimeMillis();

                for (int i = startIndex; i < rings.length; i++) {
                    int scanRadius = rings[i];
                    int step = (i - startIndex) + 1;
                    sendRingProgressUpdate(level, source, scanRadius, step, totalSteps, startedAt);
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
                LOGGER.error("[LocateFixer] Unexpected error while locating biome", e);
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
                        Component.literal("🔍 Searching... radius " + poiRadius + " blocks (50%) ⏳"), false));

                Optional<Pair<Holder<PoiType>, BlockPos>> result = level.getPoiManager()
                        .findClosestWithType(poiType, origin, poiRadius, PoiManager.Occupancy.ANY);

                if (result.isPresent()) {
                    Pair<Holder<PoiType>, BlockPos> found = result.get();
                    BlockPos pos = found.getSecond();
                    Holder<PoiType> holder = found.getFirst();

                    level.getServer().execute(() -> {
                            source.sendSuccess(() -> Component.literal("✅ Search completed (100%)."), false);
                            LocateResultHelper.sendResult(source, "commands.locate.poi.success", holder, origin, pos, true);
                    });
                } else {
                    level.getServer().execute(() ->
                            source.sendFailure(Component.literal("❌ POI not found within " + poiRadius + " blocks."))
                    );
                }

            } catch (Exception e) {
                LOGGER.error("[LocateFixer] Unexpected error while locating POI", e);
                level.getServer().execute(() ->
                        source.sendFailure(Component.literal("LocateFixer error (POI): " + e.getMessage())));
            }
        }, LOCATE_EXECUTOR);
    }

    public static int locateNearestStructuresAsync(CommandSourceStack source, int count) {
        ServerLevel level = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());
        final LocateSettings settings = SETTINGS;

        CompletableFuture.runAsync(() -> {
            try {
                Registry<Structure> registry = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
                List<Holder.Reference<Structure>> allStructures = registry.holders().toList();
                if (allStructures.isEmpty()) {
                    level.getServer().execute(() -> source.sendFailure(Component.literal("❌ No structures are registered in this dimension.")));
                    return;
                }

                HolderSet<Structure> holders = HolderSet.direct(allStructures);
                List<LocatedEntry> found = new ArrayList<>();
                Set<Long> seen = new HashSet<>();

                for (int ring : settings.rings()) {
                    level.getServer().execute(() -> source.sendSuccess(() ->
                            Component.literal("🔍 Scanning for nearest " + count + " structures up to " + ring + " blocks..."), false));
                    for (BlockPos anchor : createAnchors(origin, ring)) {
                        Pair<BlockPos, Holder<Structure>> result = level.getChunkSource().getGenerator()
                                .findNearestMapStructure(level, holders, anchor, ring, false);
                        if (result == null) {
                            continue;
                        }

                        BlockPos pos = result.getFirst();
                        if (seen.add(pos.asLong())) {
                            found.add(new LocatedEntry(pos, result.getSecond().getRegisteredName(), horizontalDistance(origin, pos)));
                        }
                    }

                    if (found.size() >= count) {
                        break;
                    }
                }

                found.sort(Comparator.comparingInt(LocatedEntry::distance));
                List<LocatedEntry> nearest = found.stream().limit(count).toList();

                level.getServer().execute(() -> {
                    if (nearest.isEmpty()) {
                        source.sendFailure(Component.literal("❌ Structures not found within " + settings.maxRadius() + " blocks."));
                        return;
                    }
                    source.sendSuccess(() -> Component.literal("✅ Nearest " + nearest.size() + " structure results:"), false);
                    for (int i = 0; i < nearest.size(); i++) {
                        final int rank = i + 1;
                        LocatedEntry entry = nearest.get(i);
                        source.sendSuccess(() -> Component.literal(rank + ") " + entry.distance() + " blocks at ("
                                + entry.pos().getX() + " " + entry.pos().getY() + " " + entry.pos().getZ() + ")"
                                + " [" + entry.name() + "]"), false);
                    }
                });
            } catch (Exception e) {
                LOGGER.error("[LocateFixer] Unexpected error while locating nearest structures", e);
                level.getServer().execute(() -> source.sendFailure(Component.literal("LocateFixer error (structure multi): " + e.getMessage())));
            }
        }, LOCATE_EXECUTOR);

        return count;
    }

    public static int locateNearestBiomesAsync(CommandSourceStack source, int count) {
        ServerLevel level = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());
        final LocateSettings settings = SETTINGS;

        CompletableFuture.runAsync(() -> {
            try {
                List<LocatedEntry> found = new ArrayList<>();
                Set<Long> seen = new HashSet<>();

                for (int ring : settings.rings()) {
                    int sampleRadius = computeSampleRadius(ring, settings);
                    int step = computeSampleStep(ring, settings);
                    level.getServer().execute(() -> source.sendSuccess(() ->
                            Component.literal("🔍 Sampling for nearest " + count + " biomes up to " + ring + " blocks..."), false));

                    for (BlockPos anchor : createAnchors(origin, ring)) {
                        Pair<BlockPos, Holder<Biome>> result = level.findClosestBiome3d(holder -> true, anchor, ring, sampleRadius, step);
                        if (result == null) {
                            continue;
                        }
                        BlockPos pos = result.getFirst();
                        if (seen.add(pos.asLong())) {
                            found.add(new LocatedEntry(pos, result.getSecond().getRegisteredName(), horizontalDistance(origin, pos)));
                        }
                    }

                    if (found.size() >= count) {
                        break;
                    }
                }

                found.sort(Comparator.comparingInt(LocatedEntry::distance));
                List<LocatedEntry> nearest = found.stream().limit(count).toList();

                level.getServer().execute(() -> {
                    if (nearest.isEmpty()) {
                        source.sendFailure(Component.literal("❌ Biomes not found within " + settings.maxRadius() + " blocks."));
                        return;
                    }
                    source.sendSuccess(() -> Component.literal("✅ Nearest " + nearest.size() + " biome results:"), false);
                    for (int i = 0; i < nearest.size(); i++) {
                        final int rank = i + 1;
                        LocatedEntry entry = nearest.get(i);
                        source.sendSuccess(() -> Component.literal(rank + ") " + entry.distance() + " blocks at ("
                                + entry.pos().getX() + " " + entry.pos().getY() + " " + entry.pos().getZ() + ")"
                                + " [" + entry.name() + "]"), false);
                    }
                });
            } catch (Exception e) {
                LOGGER.error("[LocateFixer] Unexpected error while locating nearest biomes", e);
                level.getServer().execute(() -> source.sendFailure(Component.literal("LocateFixer error (biome multi): " + e.getMessage())));
            }
        }, LOCATE_EXECUTOR);

        return count;
    }

    private static List<BlockPos> createAnchors(BlockPos origin, int radius) {
        List<BlockPos> anchors = new ArrayList<>();
        anchors.add(origin);
        int samples = Math.max(8, Math.min(32, radius / 4000 + 8));
        for (int i = 0; i < samples; i++) {
            double angle = (Math.PI * 2D * i) / samples;
            int x = origin.getX() + (int) Math.round(Math.cos(angle) * radius);
            int z = origin.getZ() + (int) Math.round(Math.sin(angle) * radius);
            anchors.add(new BlockPos(x, origin.getY(), z));
        }
        return anchors;
    }

    public static void locateBiomeVariantsAsync (CommandSourceStack source, String biomeQuery, BlockPos
        origin, ServerLevel level){
            final LocateSettings settings = SETTINGS;
            CompletableFuture.runAsync(() -> {
                try {
                    String normalized = biomeQuery.toLowerCase();
                    Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);

                    List<Holder.Reference<Biome>> matchingBiomes = new ArrayList<>();
                    for (Holder.Reference<Biome> biomeRef : biomeRegistry.holders().toList()) {
                        String biomeId = biomeRef.key().location().toString().toLowerCase();
                        if (biomeId.contains(normalized)) {
                            matchingBiomes.add(biomeRef);
                        }
                    }

                    if (matchingBiomes.isEmpty()) {
                        level.getServer().execute(() ->
                                source.sendFailure(Component.literal("❌ No biome variants matched query: " + biomeQuery))
                        );
                        return;
                    }

                    int scanRadius = settings.maxRadius();
                    int sampleRadius = computeSampleRadius(scanRadius, settings);
                    int sampleStep = computeSampleStep(scanRadius, settings);
                    int maxCandidates = 24;
                    List<BiomeVariantResult> found = new ArrayList<>();

                    for (int i = 0; i < matchingBiomes.size() && i < maxCandidates; i++) {
                        Holder.Reference<Biome> candidate = matchingBiomes.get(i);
                        Pair<BlockPos, Holder<Biome>> result = level.findClosestBiome3d(
                                holder -> holder.is(candidate.key()), origin, scanRadius, sampleRadius, sampleStep
                        );
                        if (result != null) {
                            BlockPos foundPos = result.getFirst();
                            found.add(new BiomeVariantResult(candidate.key().location().toString(), horizontalDistance(origin, foundPos)));
                        }
                    }

                    if (found.isEmpty()) {
                        level.getServer().execute(() ->
                                source.sendFailure(Component.literal("❌ No matching biome variants were found within " + scanRadius + " blocks."))
                        );
                        return;
                    }

                    found.sort(Comparator.comparingInt(BiomeVariantResult::distance));

                    level.getServer().execute(() -> {
                        int shown = Math.min(found.size(), 8);
                        source.sendSuccess(() -> Component.literal("🌳 " + biomeQuery + " variants found:"), false);
                        for (int i = 0; i < shown; i++) {
                            BiomeVariantResult result = found.get(i);
                            source.sendSuccess(() -> Component.literal("• " + result.biomeId() + " (" + result.distance() + " blocks)"), false);
                        }
                        if (found.size() > shown) {
                            int remaining = found.size() - shown;
                            source.sendSuccess(() -> Component.literal("…and " + remaining + " more variants."), false);
                        }
                    });
                } catch (Exception e) {
                    LOGGER.error("[LocateFixer] Unexpected error while locating biome variants", e);
                    level.getServer().execute(() ->
                            source.sendFailure(Component.literal("LocateFixer error (biome variants): " + e.getMessage())));
                }
            }, LOCATE_EXECUTOR);
        }

        private static <
        T > LocateCacheEntry < T > getValidCacheEntry(ConcurrentMap < LocateCacheKey, LocateCacheEntry < T >> cache, LocateCacheKey key,
        long cacheDurationMs){
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

        private static int findStartIndex (LocateCacheEntry < ? > entry, BlockPos origin,int[] rings){
            if (entry == null) {
                return 0;
            }
            int distance = horizontalDistance(origin, entry.pos());
            for (int i = 0; i < rings.length; i++) {
                if (rings[i] >= distance) {
                    return i;
                }
            }
            return rings.length - 1;
        }

        private static int computeSampleRadius ( int searchRadius, LocateSettings settings){
            int computed = Math.max(16, searchRadius / 256);
            computed = Math.min(96, computed);
            double scaled = computed * settings.biomeSampleRadiusMultiplier();
            return (int) Math.max(16, Math.min(256, Math.round(scaled)));
        }

        private static int computeSampleStep ( int searchRadius, LocateSettings settings){
            int computed = Math.max(24, searchRadius / 192);
            computed = Math.min(128, computed);
            double scaled = computed * settings.biomeSampleStepMultiplier();
            return (int) Math.max(16, Math.min(256, Math.round(scaled)));
        }

        private static BlockPos structureTeleportTarget (ServerLevel level, BlockPos structurePos){
            int x = structurePos.getX();
            int z = structurePos.getZ();
            int surfaceY;
            if (level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))) {
                surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            } else {
                surfaceY = structurePos.getY();
            }
            if (surfaceY <= level.getMinBuildHeight()) {
                surfaceY = structurePos.getY();
            }
            int clampedY = Mth.clamp(surfaceY, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
            return new BlockPos(x, clampedY, z);
        }

        private static int horizontalDistance (BlockPos origin, BlockPos target){
            int dx = target.getX() - origin.getX();
            int dz = target.getZ() - origin.getZ();
            return (int) Math.sqrt((double) dx * dx + (double) dz * dz);
        }


        public static void locateBlockAsync (CommandSourceStack source, String blockId, BlockPos origin, ServerLevel
        level){
            final LocateSettings settings = SETTINGS;
            String normalizedBlockId = blockId.trim();
            CompletableFuture.runAsync(() -> {
                try {
                    ResourceLocation blockKey = ResourceLocation.tryParse(normalizedBlockId);
                    if (blockKey == null) {
                        level.getServer().execute(() -> source.sendFailure(Component.literal("❌ Invalid block id: " + normalizedBlockId)));
                        return;
                    }

                    Optional<? extends Holder.Reference<Block>> blockHolder = level.registryAccess()
                            .lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK)
                            .get(ResourceKey.create(Registries.BLOCK, blockKey));
                    if (blockHolder.isEmpty()) {
                        level.getServer().execute(() -> source.sendFailure(Component.literal("❌ Unknown block: " + normalizedBlockId)));
                        return;
                    }

                    Holder.Reference<Block> target = blockHolder.get();
                    int[] rings = settings.rings();

                    for (int radius : rings) {
                        level.getServer().execute(() -> source.sendSuccess(() ->
                                Component.literal("🔍 Scanning up to " + radius + " blocks for " + normalizedBlockId + "..."), false));

                        Optional<BlockPos> found = runOnServerThread(level, () -> findNearestBlockInRadius(level, origin, target.value(), radius));
                        if (found.isPresent()) {
                            BlockPos foundPos = found.get();
                            level.getServer().execute(() -> {
                                Component name = Component.translatable(target.value().getDescriptionId());
                                source.sendSuccess(() -> Component.literal("✅ Nearest ").append(name).append(" found ")
                                        .append(String.valueOf(horizontalDistance(origin, foundPos)))
                                        .append(" blocks away."), false);
                                source.sendSuccess(() -> Component.literal("Coordinates: " + foundPos.getX() + " " + foundPos.getY() + " " + foundPos.getZ()), false);
                            });
                            return;
                        }
                    }

                    level.getServer().execute(() -> source.sendFailure(Component.literal("❌ Could not find block '" + normalizedBlockId + "' within configured locate radii.")));
                } catch (Exception e) {
                    LOGGER.error("[LocateFixer] locate block failed for '{}'", normalizedBlockId, e);
                    level.getServer().execute(() -> source.sendFailure(Component.literal("❌ Locate block failed: " + e.getMessage())));
                }
            }, LOCATE_EXECUTOR);
        }

        private static Optional<BlockPos> findNearestBlockInRadius (ServerLevel level, BlockPos origin, Block
        targetBlock,int searchRadius){
            BlockPos bestPos = null;
            double bestDistanceSq = Double.MAX_VALUE;

            int originChunkX = SectionPos.blockToSectionCoord(origin.getX());
            int originChunkZ = SectionPos.blockToSectionCoord(origin.getZ());
            int chunkRadius = Mth.ceil(searchRadius / 16.0D);

            for (int ring = 0; ring <= chunkRadius; ring++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (ring != 0 && Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                            continue;
                        }

                        int chunkX = originChunkX + dx;
                        int chunkZ = originChunkZ + dz;
                        int centerX = (chunkX << 4) + 8;
                        int centerZ = (chunkZ << 4) + 8;
                        if (horizontalDistance(origin, new BlockPos(centerX, origin.getY(), centerZ)) > searchRadius + 12) {
                            continue;
                        }

                        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                        BlockPos candidate = findNearestInChunk(chunk, origin, targetBlock, searchRadius);
                        if (candidate == null) {
                            continue;
                        }

                        double distSq = origin.distSqr(candidate);
                        if (distSq < bestDistanceSq) {
                            bestDistanceSq = distSq;
                            bestPos = candidate.immutable();
                        }
                    }
                }
            }

            return Optional.ofNullable(bestPos);
        }

        private static BlockPos findNearestInChunk (LevelChunk chunk, BlockPos origin, Block targetBlock,
        int searchRadius){
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            BlockPos bestPos = null;
            double bestDistanceSq = Double.MAX_VALUE;

            int minY = chunk.getMinBuildHeight();
            int maxY = chunk.getMaxBuildHeight() - 1;

            int minX = chunk.getPos().getMinBlockX();
            int maxX = chunk.getPos().getMaxBlockX();
            int minZ = chunk.getPos().getMinBlockZ();
            int maxZ = chunk.getPos().getMaxBlockZ();

            long maxDistSq = (long) searchRadius * searchRadius;

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    int dx = x - origin.getX();
                    int dz = z - origin.getZ();
                    long horizontalSq = (long) dx * dx + (long) dz * dz;
                    if (horizontalSq > maxDistSq) {
                        continue;
                    }

                    for (int y = maxY; y >= minY; y--) {
                        cursor.set(x, y, z);
                        BlockState state = chunk.getBlockState(cursor);
                        if (state.is(targetBlock)) {
                            double distSq = origin.distSqr(cursor);
                            if (distSq < bestDistanceSq) {
                                bestDistanceSq = distSq;
                                bestPos = cursor.immutable();
                            }
                        }
                    }
                }
            }

            return bestPos;
        }

        private static <T > T runOnServerThread(ServerLevel level, java.util.concurrent.Callable < T > callable) {
            CompletableFuture<T> future = new CompletableFuture<>();
            level.getServer().execute(() -> {
                try {
                    future.complete(callable.call());
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
            return future.join();
        }

        private record BiomeVariantResult(String biomeId, int distance) {
        }

                private static void sendRingProgressUpdate(ServerLevel level, CommandSourceStack source, int scanRadius, int step, int totalSteps, long startedAtMs) {
                    int progressPercent = Mth.clamp((int) Math.round((step * 100.0D) / totalSteps), 1, 100);
                    long elapsedMs = Math.max(1L, System.currentTimeMillis() - startedAtMs);
                    long avgStepMs = elapsedMs / Math.max(1, step);
                    long remainingMs = Math.max(0L, avgStepMs * (totalSteps - step));
                    long remainingSeconds = TimeUnit.MILLISECONDS.toSeconds(remainingMs);
                    String etaText = remainingSeconds > 0 ? " ⏳ ~" + remainingSeconds + "s remaining" : "";

                    level.getServer().execute(() -> source.sendSuccess(() ->
                            Component.literal("🔍 Searching... radius " + scanRadius + " blocks (" + progressPercent + "%)" + etaText), false));
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
                    try {
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
                        int[] rings = new TreeSet<>(ringList).stream().mapToInt(Integer::intValue).toArray();

                        long cacheDurationMs = TimeUnit.MINUTES.toMillis(Math.max(1L, com.thunder.locatefixer.config.LocateFixerConfig.SERVER.cacheDurationMinutes.get()));
                        int cacheGranularity = Math.max(1, com.thunder.locatefixer.config.LocateFixerConfig.SERVER.cacheChunkGranularity.get());
                        int poiRadius = Math.max(16, com.thunder.locatefixer.config.LocateFixerConfig.SERVER.poiSearchRadius.get());
                        int threadCount = Math.max(1, com.thunder.locatefixer.config.LocateFixerConfig.SERVER.locateThreadCount.get());
                        double radiusMultiplier = Math.max(1.0D, com.thunder.locatefixer.config.LocateFixerConfig.SERVER.biomeSampleRadiusMultiplier.get());
                        double stepMultiplier = Math.max(1.0D, com.thunder.locatefixer.config.LocateFixerConfig.SERVER.biomeSampleStepMultiplier.get());

                        return new LocateSettings(rings, poiRadius, cacheDurationMs, cacheGranularity, threadCount, radiusMultiplier, stepMultiplier);
                    } catch (IllegalStateException ex) {
                        LOGGER.debug("[LocateFixer] Config not ready yet, using default locate settings.");
                        return DEFAULT_SETTINGS;
                    }
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

                private record LocatedEntry(BlockPos pos, String name, int distance) {
                }

                private record LocateSettings(int[] rings, int poiSearchRadius, long cacheDurationMs,
                                              int cacheGranularity,
                                              int threadCount, double biomeSampleRadiusMultiplier,
                                              double biomeSampleStepMultiplier) {
                    int maxRadius() {
                        return rings.length == 0 ? 0 : rings[rings.length - 1];
                    }
                }
        }
