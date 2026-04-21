package com.thunder.locatefixer.util;

import com.mojang.datafixers.util.Pair;
import com.thunder.locatefixer.mixin.LocateCommandAccessor;
import com.thunder.locatefixer.mixin.LocateCommandInvoker;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceOrTagArgument;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.thunder.locatefixer.locatefixer.LOGGER;

public class AsyncLocateHandler {

    private static final int[] DEFAULT_RINGS = {6400, 16000, 32000, 64000, 128000, 256000};
    private static final int CACHE_MAX_ENTRIES = 512;

    // Pre-computed unit-circle samples for createAnchors — avoids repeated sin/cos per call.
    // We pre-build for the maximum sample count (32) and slice as needed.
    private static final int MAX_ANCHOR_SAMPLES = 32;
    private static final double[] ANCHOR_COS = new double[MAX_ANCHOR_SAMPLES];
    private static final double[] ANCHOR_SIN = new double[MAX_ANCHOR_SAMPLES];

    static {
        for (int i = 0; i < MAX_ANCHOR_SAMPLES; i++) {
            double angle = (Math.PI * 2.0 * i) / MAX_ANCHOR_SAMPLES;
            ANCHOR_COS[i] = Math.cos(angle);
            ANCHOR_SIN[i] = Math.sin(angle);
        }
    }

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

    // Tracks players with an in-flight locate request so they can't spam the executor.
    // Uses the player name (String) to avoid holding a reference to the player object.
    private static final Set<String> IN_FLIGHT_PLAYERS = ConcurrentHashMap.newKeySet();

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

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
        String playerKey = playerKey(source);
        if (!acquireInFlight(source, playerKey)) return;

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
                    if (horizontalDistanceSq(origin, cachedPos) <= (long) settings.maxRadius() * settings.maxRadius()) {
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

                        putWithEviction(STRUCTURE_CACHE, cacheKey, new LocateCacheEntry<>(pos, holder, System.currentTimeMillis()));

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
            } finally {
                IN_FLIGHT_PLAYERS.remove(playerKey);
            }
        }, LOCATE_EXECUTOR);
    }

    public static void locateBiomeAsync(CommandSourceStack source, ResourceOrTagArgument.Result<Biome> biome, BlockPos origin, ServerLevel level) {
        String playerKey = playerKey(source);
        if (!acquireInFlight(source, playerKey)) return;

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
                    if (horizontalDistanceSq(origin, cachedPos) <= (long) settings.maxRadius() * settings.maxRadius()) {
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

                    Pair<BlockPos, Holder<Biome>> result = level.findClosestBiome3d(biome, origin, scanRadius,
                            computeSampleRadius(scanRadius, settings), computeSampleStep(scanRadius, settings));
                    if (result != null) {
                        BlockPos pos = result.getFirst();
                        Holder<Biome> holder = result.getSecond();

                        putWithEviction(BIOME_CACHE, cacheKey, new LocateCacheEntry<>(pos, holder, System.currentTimeMillis()));

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
            } finally {
                IN_FLIGHT_PLAYERS.remove(playerKey);
            }
        }, LOCATE_EXECUTOR);
    }

    public static void locatePoiAsync(CommandSourceStack source, ResourceOrTagArgument.Result<PoiType> poiType, BlockPos origin, ServerLevel level) {
        String playerKey = playerKey(source);
        if (!acquireInFlight(source, playerKey)) return;

        final LocateSettings settings = SETTINGS;
        CompletableFuture.runAsync(() -> {
            try {
                int poiRadius = settings.poiSearchRadius();
                LOGGER.info("[LocateFixer] Scanning for POI within {} blocks", poiRadius);
                level.getServer().execute(() -> source.sendSuccess(() ->
                        Component.literal("🔍 Searching... radius " + poiRadius + " blocks ⏳"), false));

                Optional<Pair<Holder<PoiType>, BlockPos>> result = level.getPoiManager()
                        .findClosestWithType(poiType, origin, poiRadius, PoiManager.Occupancy.ANY);

                if (result.isPresent()) {
                    Pair<Holder<PoiType>, BlockPos> found = result.get();
                    BlockPos pos = found.getSecond();
                    Holder<PoiType> holder = found.getFirst();

                    level.getServer().execute(() -> {
                        source.sendSuccess(() -> Component.literal("✅ Search completed."), false);
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
            } finally {
                IN_FLIGHT_PLAYERS.remove(playerKey);
            }
        }, LOCATE_EXECUTOR);
    }

    public static int locateNearestStructuresAsync(CommandSourceStack source, int count) {
        ServerLevel level = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());
        final LocateSettings settings = SETTINGS;

        String playerKey = playerKey(source);
        if (!acquireInFlight(source, playerKey)) return 0;

        CompletableFuture.runAsync(() -> {
            try {
                Registry<Structure> registry = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
                List<Holder.Reference<Structure>> allStructures = registry.holders().toList();
                if (allStructures.isEmpty()) {
                    level.getServer().execute(() -> source.sendFailure(Component.literal("❌ No structures are registered in this dimension.")));
                    return;
                }

                // Build the HolderSet once outside the loop — it never changes
                HolderSet<Structure> holders = HolderSet.direct(allStructures);

                // Deduplicate by (structureName + coarse grid cell) so we don't list the
                // same structure type twice from nearby positions.
                // Key: structureRegistryName + coarse-X + coarse-Z (128-block grid)
                Map<String, LocatedEntry> bestByType = new LinkedHashMap<>();
                Set<Long> seenPositions = new HashSet<>();

                for (int ring : settings.rings()) {
                    final int ringFinal = ring;
                    level.getServer().execute(() -> source.sendSuccess(() ->
                            Component.literal("🔍 Scanning for nearest " + count + " structures up to " + ringFinal + " blocks..."), false));

                    for (BlockPos anchor : createAnchors(origin, ring)) {
                        Pair<BlockPos, Holder<Structure>> result = level.getChunkSource().getGenerator()
                                .findNearestMapStructure(level, holders, anchor, ring, false);
                        if (result == null) continue;

                        BlockPos pos = result.getFirst();
                        long posKey = pos.asLong();
                        if (!seenPositions.add(posKey)) continue;

                        String name = result.getSecond().getRegisteredName();
                        int dist = horizontalDistance(origin, pos);

                        // Keep closest result per structure type
                        bestByType.merge(name, new LocatedEntry(pos, name, dist),
                                (existing, candidate) -> candidate.distance() < existing.distance() ? candidate : existing);
                    }

                    if (bestByType.size() >= count) break;
                }

                List<LocatedEntry> nearest = bestByType.values().stream()
                        .sorted(Comparator.comparingInt(LocatedEntry::distance))
                        .limit(count)
                        .toList();

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
            } finally {
                IN_FLIGHT_PLAYERS.remove(playerKey);
            }
        }, LOCATE_EXECUTOR);

        return count;
    }

    public static int locateNearestBiomesAsync(CommandSourceStack source, int count) {
        ServerLevel level = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());
        final LocateSettings settings = SETTINGS;

        String playerKey = playerKey(source);
        if (!acquireInFlight(source, playerKey)) return 0;

        CompletableFuture.runAsync(() -> {
            try {
                // Collect all biomes registered in this dimension so we can search for each
                // one specifically, rather than matching "any biome" from every anchor point
                // (which produces many duplicate results of the same nearest biome).
                List<Holder<Biome>> allBiomes = level.getChunkSource()
                        .getGenerator()
                        .getBiomeSource()
                        .possibleBiomes()
                        .stream()
                        .toList();

                Map<String, LocatedEntry> bestByBiome = new LinkedHashMap<>();

                for (int ring : settings.rings()) {
                    int sampleRadius = computeSampleRadius(ring, settings);
                    int step = computeSampleStep(ring, settings);
                    final int ringFinal = ring;
                    level.getServer().execute(() -> source.sendSuccess(() ->
                            Component.literal("🔍 Sampling for nearest " + count + " biomes up to " + ringFinal + " blocks..."), false));

                    for (Holder<Biome> targetBiome : allBiomes) {
                        String biomeName = targetBiome.getRegisteredName();
                        if (bestByBiome.containsKey(biomeName)) continue; // already found one closer

                        Pair<BlockPos, Holder<Biome>> result = level.findClosestBiome3d(
                                h -> h.is(targetBiome), origin, ring, sampleRadius, step);
                        if (result == null) continue;

                        BlockPos pos = result.getFirst();
                        int dist = horizontalDistance(origin, pos);
                        bestByBiome.put(biomeName, new LocatedEntry(pos, biomeName, dist));
                    }

                    if (bestByBiome.size() >= count) break;
                }

                List<LocatedEntry> nearest = bestByBiome.values().stream()
                        .sorted(Comparator.comparingInt(LocatedEntry::distance))
                        .limit(count)
                        .toList();

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
            } finally {
                IN_FLIGHT_PLAYERS.remove(playerKey);
            }
        }, LOCATE_EXECUTOR);

        return count;
    }

    // ---------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------

    /**
     * Returns a stable key identifying the requesting entity (player name or "server").
     */
    private static String playerKey(CommandSourceStack source) {
        return source.getTextName();
    }

    /**
     * Attempts to register the player as having an in-flight request.
     * Returns false (and sends a failure message) if they already have one.
     */
    private static boolean acquireInFlight(CommandSourceStack source, String key) {
        if (!IN_FLIGHT_PLAYERS.add(key)) {
            source.sendFailure(Component.literal("⏳ You already have a locate search in progress. Please wait for it to finish."));
            return false;
        }
        return true;
    }

    /**
     * Creates anchor positions evenly distributed on a ring.
     * Uses pre-computed sin/cos table — no transcendental calls at runtime.
     */
    private static List<BlockPos> createAnchors(BlockPos origin, int radius) {
        int samples = Math.max(8, Math.min(MAX_ANCHOR_SAMPLES, radius / 4000 + 8));
        List<BlockPos> anchors = new ArrayList<>(samples + 1);
        anchors.add(origin);
        // Stride through the pre-computed table to fit the requested sample count
        int stride = MAX_ANCHOR_SAMPLES / samples;
        for (int i = 0; i < samples; i++) {
            int tableIdx = (i * stride) % MAX_ANCHOR_SAMPLES;
            int x = origin.getX() + (int) Math.round(ANCHOR_COS[tableIdx] * radius);
            int z = origin.getZ() + (int) Math.round(ANCHOR_SIN[tableIdx] * radius);
            anchors.add(new BlockPos(x, origin.getY(), z));
        }
        return anchors;
    }

    private static <T> LocateCacheEntry<T> getValidCacheEntry(ConcurrentMap<LocateCacheKey, LocateCacheEntry<T>> cache,
                                                               LocateCacheKey key, long cacheDurationMs) {
        LocateCacheEntry<T> entry = cache.get(key);
        if (entry == null) return null;
        if (System.currentTimeMillis() - entry.timestamp() > cacheDurationMs) {
            cache.remove(key, entry);
            return null;
        }
        return entry;
    }

    /**
     * Inserts into a cache with a size cap.  When at capacity, evicts the entry with the
     * oldest timestamp using a single O(n) pass — called rarely (only on a cache miss
     * that found a result), so the cost is acceptable.
     */
    private static <T> void putWithEviction(ConcurrentMap<LocateCacheKey, LocateCacheEntry<T>> cache,
                                            LocateCacheKey key, LocateCacheEntry<T> entry) {
        if (cache.size() >= CACHE_MAX_ENTRIES) {
            LocateCacheKey oldest = null;
            long oldestTs = Long.MAX_VALUE;
            for (Map.Entry<LocateCacheKey, LocateCacheEntry<T>> e : cache.entrySet()) {
                if (e.getValue().timestamp() < oldestTs) {
                    oldestTs = e.getValue().timestamp();
                    oldest = e.getKey();
                }
            }
            if (oldest != null) cache.remove(oldest);
        }
        cache.put(key, entry);
    }

    private static int findStartIndex(LocateCacheEntry<?> entry, BlockPos origin, int[] rings) {
        if (entry == null) return 0;
        int distance = horizontalDistance(origin, entry.pos());
        for (int i = 0; i < rings.length; i++) {
            if (rings[i] >= distance) return i;
        }
        return rings.length - 1;
    }

    private static int computeSampleRadius(int searchRadius, LocateSettings settings) {
        int computed = Mth.clamp(searchRadius / 256, 16, 96);
        return (int) Mth.clamp((float) Math.round(computed * settings.biomeSampleRadiusMultiplier()), 16, 256);
    }

    private static int computeSampleStep(int searchRadius, LocateSettings settings) {
        int computed = Mth.clamp(searchRadius / 192, 24, 128);
        return (int) Mth.clamp((float) Math.round(computed * settings.biomeSampleStepMultiplier()), 16, 256);
    }

    private static BlockPos structureTeleportTarget(ServerLevel level, BlockPos structurePos) {
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
        return new BlockPos(x, Mth.clamp(surfaceY, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1), z);
    }

    /**
     * Integer horizontal distance (Euclidean XZ). Use {@link #horizontalDistanceSq}
     * for comparisons against a radius to avoid the sqrt entirely.
     */
    private static int horizontalDistance(BlockPos origin, BlockPos target) {
        long sq = horizontalDistanceSq(origin, target);
        return (int) Math.sqrt((double) sq);
    }

    /**
     * Squared horizontal distance — use this for radius comparisons to skip sqrt.
     */
    private static long horizontalDistanceSq(BlockPos origin, BlockPos target) {
        long dx = target.getX() - origin.getX();
        long dz = target.getZ() - origin.getZ();
        return dx * dx + dz * dz;
    }

    private static void sendRingProgressUpdate(ServerLevel level, CommandSourceStack source,
                                               int scanRadius, int step, int totalSteps, long startedAtMs) {
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
                if (ring != null && ring > 0) ringList.add(ring);
            }
            if (ringList.isEmpty()) {
                for (int ring : DEFAULT_RINGS) ringList.add(ring);
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

    // ---------------------------------------------------------------------------
    // Records
    // ---------------------------------------------------------------------------

    private record LocateCacheKey(String dimension, String target, int coarseChunkX, int coarseChunkZ) {
        private static LocateCacheKey of(ServerLevel level, String target, BlockPos origin, int granularity) {
            int chunkX = origin.getX() >> 4;
            int chunkZ = origin.getZ() >> 4;
            int coarseX = Math.floorDiv(chunkX, granularity);
            int coarseZ = Math.floorDiv(chunkZ, granularity);
            return new LocateCacheKey(level.dimension().location().toString(), target, coarseX, coarseZ);
        }
    }

    private record LocateCacheEntry<T>(BlockPos pos, Holder<T> holder, long timestamp) {}

    private record LocatedEntry(BlockPos pos, String name, int distance) {}

    private record LocateSettings(int[] rings, int poiSearchRadius, long cacheDurationMs,
                                  int cacheGranularity, int threadCount,
                                  double biomeSampleRadiusMultiplier, double biomeSampleStepMultiplier) {
        int maxRadius() {
            return rings.length == 0 ? 0 : rings[rings.length - 1];
        }
    }
}
