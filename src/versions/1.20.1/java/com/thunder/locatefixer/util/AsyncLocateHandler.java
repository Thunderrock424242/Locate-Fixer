package com.thunder.locatefixer.util;

import com.mojang.datafixers.util.Pair;
import com.thunder.locatefixer.LocateRuntime;
import com.thunder.locatefixer.api.LocatorRequest;
import com.thunder.locatefixer.api.LocatorResult;
import com.thunder.locatefixer.api.LocatorTargetType;
import com.thunder.locatefixer.api.LocatorProvider;
import com.thunder.locatefixer.api.LocatorProviderRegistry;
import com.thunder.locatefixer.api.LocatorThreadSafety;
import com.thunder.locatefixer.api.StructureLocatorRegistry;
import com.thunder.locatefixer.backend.LocatorBackend;
import com.thunder.locatefixer.job.LocateJob;
import com.thunder.locatefixer.job.LocateJobManager;
import com.thunder.locatefixer.index.WorldLocatorIndex;
import com.thunder.locatefixer.search.SearchPlan;
import com.thunder.locatefixer.search.SearchStage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceOrTagArgument;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.*;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.thunder.locatefixer.LocateFixerMod.LOGGER;

public class AsyncLocateHandler {

    private static final int[] DEFAULT_RINGS = {6400, 16000, 32000, 64000, 128000, 256000};
    private static final int STRUCTURE_START_SCAN_RADIUS_CHUNKS = 1;

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
    private static final AtomicLong CACHE_EPOCH = new AtomicLong();

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    public static void runAsyncTask(String taskName, Runnable task) {
        CompletableFuture.runAsync(() -> {
            try {
                task.run();
            } catch (Exception e) {
                LOGGER.error("[LocateUnbound] Async task '{}' failed", taskName, e);
            }
        }, executor());
    }

    public static void locateStructureAsync(CommandSourceStack source, ResourceOrTagKeyArgument.Result<Structure> structure, BlockPos origin, ServerLevel level) {
        final LocateSettings settings = SETTINGS;
        submitLocateJob(source, LocatorTargetType.STRUCTURE, structure.asPrintable(), origin, level, settings, job -> {
            try {
                int[] rings = plannedRings(job, settings);
                if (rings.length == 0) {
                    level.getServer().execute(() ->
                            source.sendFailure(Component.literal("❌ No locate search radii configured.")));
                    return;
                }

                Optional<? extends HolderSet.ListBacked<Structure>> resolved = callOnServerThread(level, () -> {
                    Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
                    return resolveStructureHolders(structure, registry);
                });
                if (resolved.isEmpty()) {
                    level.getServer().execute(() -> source.sendFailure(Component.literal(
                            "Unknown or empty structure target: " + structure.asPrintable())));
                    return;
                }

                HolderSet<Structure> holders = resolved.get();
                String canonicalTarget = canonicalStructureTarget(structure, holders);
                Set<String> allowedStructureIds = resolvedStructureIds(holders);

                LocateCacheKey cacheKey = LocateCacheKey.of(level, canonicalTarget, origin, settings.cacheGranularity());
                LocateCacheEntry<Structure> cacheEntry = getValidCacheEntry(STRUCTURE_CACHE, cacheKey, settings.cacheDurationMs());
                job.incrementCounter(cacheEntry == null ? "cache_misses" : "cache_hits");

                if (cacheEntry != null) {
                    BlockPos cachedPos = cacheEntry.pos();
                    if (horizontalDistanceSq(origin, cachedPos) <= (long) settings.maxRadius() * settings.maxRadius()) {
                        Holder<Structure> holder = cacheEntry.holder();
                        level.getServer().execute(() -> {
                            BlockPos surfacePos = locateTeleportTarget(level, structureTeleportTarget(level, cachedPos, holder));
                            source.sendSuccess(() -> Component.literal("✅ Using cached locate result."), false);
                            LocateResultHelper.sendResult(source, "commands.locate.structure.success", holder, origin, surfacePos, true);
                        });
                        completeJob(job, level, cachedPos, "memory-cache", true);
                        return;
                    }
                }
                Optional<LocatorResult> indexed = findIndexed(job, level);
                if (indexed.isPresent()) {
                    LocatorResult indexedResult = indexed.get();
                    level.getServer().execute(() -> {
                        BlockPos surfacePos = locateTeleportTarget(level, indexedResult.position());
                        source.sendSuccess(() -> Component.literal("✅ Using persistent world index."), false);
                        LocateResultHelper.sendResult(source, "commands.locatefixer.base.success",
                                indexedResult.targetId(), origin, surfacePos, true);
                    });
                    completeIndexedJob(job, indexedResult);
                    return;
                }
                SearchPlan fullPlan = plannedSearchPlan(job, settings);
                int startIndex = findStartIndex(cacheEntry, origin, rings);
                SearchPlan activePlan = slicePlan(fullPlan, startIndex);
                int totalSteps = activePlan.stages().size();
                long startedAt = System.currentTimeMillis();
                sendLocateStartUpdate(level, source, "structure", canonicalTarget, totalSteps, settings.maxRadius());

                LocatorBackend backend = selectedBackend(job);
                AtomicInteger completedStages = new AtomicInteger();
                AtomicReference<Holder<Structure>> foundHolder = new AtomicReference<>();
                Optional<LocatorResult> located = backend.locate(job.request(), activePlan,
                        job.cancellationToken(), (stage, cancellationToken) -> {
                    cancellationToken.throwIfCancelled();
                    job.incrementCounter("search_stages");
                    int scanRadius = stage.radius();
                    int step = completedStages.incrementAndGet();
                    job.searching(Math.max(2, (step * 95) / totalSteps),
                            "Structure ring " + step + "/" + totalSteps + " (" + scanRadius + " blocks)");
                    sendRingProgressUpdate(level, source, scanRadius, step, totalSteps, startedAt);
                    LOGGER.info("[LocateUnbound] Scanning for structure up to {} blocks", scanRadius);

                    int scanRadiusChunks = blocksToChunks(scanRadius);
                    job.incrementCounter("backend_calls");
                    Pair<BlockPos, Holder<Structure>> result = callOnServerThread(level, () ->
                            level.getChunkSource().getGenerator()
                                    .findNearestMapStructure(level, holders, origin, scanRadiusChunks, false));

                    if (result != null) {
                        BlockPos pos = result.getFirst();
                        Holder<Structure> holder = result.getSecond();
                        String foundId = holderName(holder);
                        if (!allowedStructureIds.contains(foundId)) {
                            LOGGER.warn("[LocateUnbound] Ignoring locate candidate '{}' for request '{}'; resolved allowed ids={}",
                                    foundId, canonicalTarget, allowedStructureIds);
                            return Optional.empty();
                        }

                        putWithEviction(STRUCTURE_CACHE, cacheKey, new LocateCacheEntry<>(pos, holder, System.currentTimeMillis()));
                        foundHolder.set(holder);
                        return Optional.of(createLocatorResult(job, pos, backend.id(), "world-generator", true));
                    }

                    return Optional.empty();
                });

                if (located.isPresent()) {
                    LocatorResult result = located.get();
                    Holder<Structure> holder = foundHolder.get();
                    int step = Math.max(1, completedStages.get());
                    level.getServer().execute(() -> {
                        BlockPos surfacePos = holder == null
                                ? locateTeleportTarget(level, result.position())
                                : locateTeleportTarget(level, structureTeleportTarget(level, result.position(), holder));
                        sendLocateCompletionUpdate(source, startedAt, step, totalSteps, result.position(), surfacePos);
                        if (holder != null) {
                            LocateResultHelper.sendResult(source, "commands.locate.structure.success", holder, origin, surfacePos, true);
                        } else {
                            LocateResultHelper.sendResult(source, "commands.locatefixer.base.success",
                                    result.targetId(), origin, surfacePos, true);
                        }
                    });
                    completeBackendJob(job, level, result);
                    return;
                }

                level.getServer().execute(() ->
                        source.sendFailure(Component.literal("❌ Structure not found within " + settings.maxRadius() + " blocks.")));
                failJobNotFound(job, "Structure not found within " + settings.maxRadius() + " blocks.");

            } catch (Exception e) {
                if (e instanceof CancellationException cancellation) throw cancellation;
                job.fail(e);
                LOGGER.error("[LocateUnbound] Unexpected error while locating structure", e);
                level.getServer().execute(() ->
                        source.sendFailure(Component.literal("Locate Unbound error (structure): " + e.getMessage())));
            }
        });
    }


    public static void locateCustomStructureAsync(CommandSourceStack source, String structureId, BlockPos origin, ServerLevel level) {
        final LocateSettings settings = SETTINGS;
        Optional<LocatorProvider> registeredProvider = LocatorProviderRegistry.get(structureId);
        LocatorTargetType requestType = registeredProvider.map(LocatorProvider::targetType)
                .orElse(LocatorTargetType.CUSTOM);
        submitLocateJob(source, requestType, structureId, origin, level, settings, job -> {
            try {
                int[] rings = plannedRings(job, settings);
                if (rings.length == 0) {
                    level.getServer().execute(() ->
                            source.sendFailure(Component.literal("❌ No locate search radii configured.")));
                    return;
                }

                int maxRadius = settings.maxRadius();
                Optional<LocatorResult> indexed = registeredProvider.isPresent()
                        && !registeredProvider.get().cachePolicy().allowsPersistentIndex()
                        ? Optional.empty() : findIndexed(job, level);
                if (indexed.isPresent()) {
                    LocatorResult indexedResult = indexed.get();
                    level.getServer().execute(() -> {
                        BlockPos teleportTarget = locateTeleportTarget(level, indexedResult.position());
                        source.sendSuccess(() -> Component.literal("✅ Using persistent world index."), false);
                        if (registeredProvider.isPresent() && !registeredProvider.get().safelyTeleportable()) {
                            source.sendSuccess(() -> Component.literal("✅ " + indexedResult.targetId() + " at "
                                    + teleportTarget.getX() + " " + teleportTarget.getY() + " " + teleportTarget.getZ()), false);
                        } else {
                            LocateResultHelper.sendResult(source, "commands.locatefixer.base.success",
                                    indexedResult.targetId(), origin, teleportTarget, true);
                        }
                    });
                    completeIndexedJob(job, indexedResult);
                    return;
                }
                level.getServer().execute(() -> source.sendSuccess(() ->
                        Component.literal("🔍 Locating structure '" + structureId + "'..."), false));

                // Providers receive a live ServerLevel, so invoke them on the owning server thread.
                // This is safe for ordinary mod state and avoids forcing every integration to be
                // independently thread-safe.
                Optional<LocatorProvider> provider = registeredProvider;
                LocatorBackend backend = selectedBackend(job);
                SearchPlan providerPlan = oneStagePlan(maxRadius, "provider lookup");
                Optional<LocatorResult> backendResult = backend.locate(job.request(), providerPlan,
                        job.cancellationToken(), (stage, cancellationToken) -> {
                    cancellationToken.throwIfCancelled();
                    job.incrementCounter("search_stages");
                    job.incrementCounter("backend_calls");
                    if (provider.isPresent()) {
                        return invokeProvider(provider.get(), job, level, origin, stage.radius());
                    }
                    return callOnServerThread(level, () ->
                                    StructureLocatorRegistry.locate(structureId, level, origin, stage.radius()))
                            .map(position -> createLocatorResult(job, position, backend.id(),
                                    "legacy-custom-provider", true));
                });
                Optional<BlockPos> result = backendResult.map(LocatorResult::position);

                level.getServer().execute(() -> {
                    if (result.isEmpty()) {
                        source.sendFailure(Component.literal("❌ Structure '" + structureId + "' was not found within " + maxRadius + " blocks."));
                        return;
                    }

                    BlockPos teleportTarget = locateTeleportTarget(level, result.get());
                    if (provider.isPresent() && !provider.get().safelyTeleportable()) {
                        source.sendSuccess(() -> Component.literal("✅ " + structureId + " at "
                                + teleportTarget.getX() + " " + teleportTarget.getY() + " " + teleportTarget.getZ()), false);
                    } else {
                        LocateResultHelper.sendResult(source, "commands.locatefixer.base.success",
                                structureId, origin, teleportTarget, true);
                    }
                });
                if (result.isPresent()) {
                    if (provider.isPresent()) {
                        completeProviderJob(job, level, provider.get(), backendResult.orElseThrow());
                    } else {
                        completeBackendJob(job, level, backendResult.orElseThrow());
                    }
                } else {
                    failJobNotFound(job, "Custom structure not found within " + maxRadius + " blocks.");
                }
            } catch (Exception e) {
                if (e instanceof CancellationException cancellation) throw cancellation;
                job.fail(e);
                LOGGER.error("[LocateUnbound] Unexpected error while locating custom structure '{}'", structureId, e);
                level.getServer().execute(() ->
                        source.sendFailure(Component.literal("Locate Unbound error (custom structure): " + e.getMessage())));
            }
        });
    }

    public static void locateFeatureAsync(CommandSourceStack source,
                                          String featureId,
                                          BlockPos origin,
                                          ServerLevel level,
                                          FeatureSearchOperation operation) {
        final LocateSettings settings = SETTINGS;
        submitLocateJob(source, LocatorTargetType.FEATURE, featureId, origin, level, settings, job -> {
            try {
                Optional<LocatorResult> indexed = findIndexed(job, level);
                if (indexed.isPresent()) {
                    LocatorResult indexedResult = indexed.get();
                    level.getServer().execute(() -> {
                        BlockPos surface = locateTeleportTarget(level, indexedResult.position());
                        source.sendSuccess(() -> Component.literal("✅ Using persistent world index."), false);
                        LocateResultHelper.sendResult(source, "commands.locatefixer.base.success",
                                featureId, origin, surface, true);
                    });
                    completeIndexedJob(job, indexedResult);
                    return;
                }

                SearchPlan plan = plannedSearchPlan(job, settings);
                job.searching(10, "Searching biome generation settings for " + featureId);
                LocatorBackend backend = selectedBackend(job);
                Optional<LocatorResult> result = backend.locate(job.request(), plan,
                        job.cancellationToken(), (stage, cancellationToken) -> {
                    cancellationToken.throwIfCancelled();
                    job.incrementCounter("search_stages");
                    job.incrementCounter("backend_calls");
                    Optional<BlockPos> position = callOnServerThread(level,
                            () -> operation.search(new int[]{stage.radius()}, cancellationToken));
                    return position.map(pos -> createLocatorResult(job, pos, backend.id(),
                            "biome-generation-settings", true));
                });
                if (result.isPresent()) {
                    LocatorResult located = result.get();
                    level.getServer().execute(() -> {
                        BlockPos surface = locateTeleportTarget(level, located.position());
                        LocateResultHelper.sendResult(source, "commands.locatefixer.base.success",
                                featureId, origin, surface, true);
                    });
                    completeBackendJob(job, level, located);
                } else {
                    level.getServer().execute(() -> source.sendFailure(Component.literal(
                            "❌ Feature '" + featureId + "' not found within " + settings.maxRadius() + " blocks.")));
                    failJobNotFound(job, "Feature not found within " + settings.maxRadius() + " blocks.");
                }
            } catch (Exception failure) {
                if (failure instanceof CancellationException cancellation) throw cancellation;
                job.fail(failure);
                LOGGER.error("[LocateUnbound] Unexpected error while locating feature '{}'", featureId, failure);
                level.getServer().execute(() -> source.sendFailure(Component.literal(
                        "Locate Unbound error (feature): " + failure.getMessage())));
            }
        });
    }

    @FunctionalInterface
    public interface FeatureSearchOperation {
        Optional<BlockPos> search(int[] rings, com.thunder.locatefixer.search.LocateCancellationToken token);
    }

    public static void locateBiomeAsync(CommandSourceStack source, ResourceOrTagArgument.Result<Biome> biome, BlockPos origin, ServerLevel level) {
        final LocateSettings settings = SETTINGS;
        submitLocateJob(source, LocatorTargetType.BIOME, biome.asPrintable(), origin, level, settings, job -> {
            try {
                int[] rings = plannedRings(job, settings);
                if (rings.length == 0) {
                    level.getServer().execute(() ->
                            source.sendFailure(Component.literal("❌ No locate search radii configured.")));
                    return;
                }

                LocateCacheKey cacheKey = LocateCacheKey.of(level, biome.asPrintable(), origin, settings.cacheGranularity());
                LocateCacheEntry<Biome> cacheEntry = getValidCacheEntry(BIOME_CACHE, cacheKey, settings.cacheDurationMs());
                job.incrementCounter(cacheEntry == null ? "cache_misses" : "cache_hits");

                if (cacheEntry != null) {
                    BlockPos cachedPos = cacheEntry.pos();
                    if (horizontalDistanceSq(origin, cachedPos) <= (long) settings.maxRadius() * settings.maxRadius()) {
                        Holder<Biome> holder = cacheEntry.holder();
                        level.getServer().execute(() -> {
                            BlockPos teleportTarget = locateTeleportTarget(level, cachedPos);
                            source.sendSuccess(() -> Component.literal("✅ Using cached locate result."), false);
                            LocateResultHelper.sendResult(source, "commands.locate.biome.success", holder, origin, teleportTarget, true);
                        });
                        completeJob(job, level, cachedPos, "memory-cache", true);
                        return;
                    }
                }

                Optional<LocatorResult> indexed = findIndexed(job, level);
                if (indexed.isPresent()) {
                    LocatorResult indexedResult = indexed.get();
                    level.getServer().execute(() -> {
                        BlockPos teleportTarget = locateTeleportTarget(level, indexedResult.position());
                        source.sendSuccess(() -> Component.literal("✅ Using persistent world index."), false);
                        LocateResultHelper.sendResult(source, "commands.locatefixer.base.success",
                                indexedResult.targetId(), origin, teleportTarget, true);
                    });
                    completeIndexedJob(job, indexedResult);
                    return;
                }

                SearchPlan fullPlan = plannedSearchPlan(job, settings);
                int startIndex = findStartIndex(cacheEntry, origin, rings);
                SearchPlan activePlan = slicePlan(fullPlan, startIndex);
                int totalSteps = activePlan.stages().size();
                long startedAt = System.currentTimeMillis();
                sendLocateStartUpdate(level, source, "biome", biome.asPrintable(), totalSteps, settings.maxRadius());

                LocatorBackend backend = selectedBackend(job);
                AtomicInteger completedStages = new AtomicInteger();
                AtomicReference<Holder<Biome>> foundHolder = new AtomicReference<>();
                Optional<LocatorResult> located = backend.locate(job.request(), activePlan,
                        job.cancellationToken(), (stage, cancellationToken) -> {
                    cancellationToken.throwIfCancelled();
                    job.incrementCounter("search_stages");
                    int scanRadius = stage.radius();
                    int step = completedStages.incrementAndGet();
                    job.searching(Math.max(2, (step * 95) / totalSteps),
                            "Biome ring " + step + "/" + totalSteps + " (" + scanRadius + " blocks)");
                    sendRingProgressUpdate(level, source, scanRadius, step, totalSteps, startedAt);
                    LOGGER.info("[LocateUnbound] Scanning for biome up to {} blocks", scanRadius);

                    Pair<BlockPos, Holder<Biome>> result = callOnServerThread(level, () ->
                            level.findClosestBiome3d(biome, origin, scanRadius,
                                    computeSampleRadius(scanRadius, stage.sampleRadiusMultiplier()),
                                    computeSampleStep(scanRadius, stage.sampleStepMultiplier())));
                    job.incrementCounter("backend_calls");
                    if (result != null) {
                        BlockPos pos = result.getFirst();
                        Holder<Biome> holder = result.getSecond();

                        putWithEviction(BIOME_CACHE, cacheKey, new LocateCacheEntry<>(pos, holder, System.currentTimeMillis()));
                        foundHolder.set(holder);
                        return Optional.of(createLocatorResult(job, pos, backend.id(), "world-generator", true));
                    }

                    return Optional.empty();
                });

                if (located.isPresent()) {
                    LocatorResult result = located.get();
                    Holder<Biome> holder = foundHolder.get();
                    int step = Math.max(1, completedStages.get());
                    level.getServer().execute(() -> {
                        BlockPos teleportTarget = locateTeleportTarget(level, result.position());
                        sendLocateCompletionUpdate(source, startedAt, step, totalSteps, result.position(), teleportTarget);
                        if (holder != null) {
                            LocateResultHelper.sendResult(source, "commands.locate.biome.success", holder, origin, teleportTarget, true);
                        } else {
                            LocateResultHelper.sendResult(source, "commands.locatefixer.base.success",
                                    result.targetId(), origin, teleportTarget, true);
                        }
                    });
                    completeBackendJob(job, level, result);
                    return;
                }

                level.getServer().execute(() ->
                        source.sendFailure(Component.literal("❌ Biome not found within " + settings.maxRadius() + " blocks.")));
                failJobNotFound(job, "Biome not found within " + settings.maxRadius() + " blocks.");

            } catch (Exception e) {
                if (e instanceof CancellationException cancellation) throw cancellation;
                job.fail(e);
                LOGGER.error("[LocateUnbound] Unexpected error while locating biome", e);
                level.getServer().execute(() ->
                        source.sendFailure(Component.literal("Locate Unbound error (biome): " + e.getMessage())));
            }
        });
    }

    public static void locatePoiAsync(CommandSourceStack source, ResourceOrTagArgument.Result<PoiType> poiType, BlockPos origin, ServerLevel level) {
        final LocateSettings settings = SETTINGS;
        int configuredPoiRadius = settings.poiSearchRadius();
        submitLocateJob(source, LocatorTargetType.POI, poiType.asPrintable(), origin, level,
                configuredPoiRadius, job -> {
            try {
                int poiRadius = configuredPoiRadius;
                job.searching(25, "Searching POIs within " + poiRadius + " blocks");
                Optional<LocatorResult> indexed = findIndexed(job, level);
                if (indexed.isPresent()) {
                    LocatorResult indexedResult = indexed.get();
                    level.getServer().execute(() -> {
                        BlockPos teleportTarget = locateTeleportTarget(level, indexedResult.position());
                        source.sendSuccess(() -> Component.literal("✅ Using persistent world index."), false);
                        LocateResultHelper.sendResult(source, "commands.locatefixer.base.success",
                                indexedResult.targetId(), origin, teleportTarget, true);
                    });
                    completeIndexedJob(job, indexedResult);
                    return;
                }
                LOGGER.info("[LocateUnbound] Scanning for POI within {} blocks", poiRadius);
                level.getServer().execute(() -> source.sendSuccess(() ->
                        Component.literal("🔍 Searching... radius " + poiRadius + " blocks ⏳"), false));

                LocatorBackend backend = selectedBackend(job);
                AtomicReference<Holder<PoiType>> foundHolder = new AtomicReference<>();
                Optional<LocatorResult> result = backend.locate(job.request(),
                        oneStagePlan(poiRadius, "configured POI radius"), job.cancellationToken(),
                        (stage, cancellationToken) -> {
                    cancellationToken.throwIfCancelled();
                    job.incrementCounter("search_stages");
                    job.incrementCounter("backend_calls");
                    Optional<Pair<Holder<PoiType>, BlockPos>> found = callOnServerThread(level, () ->
                            level.getPoiManager().findClosestWithType(
                                    poiType, origin, stage.radius(), PoiManager.Occupancy.ANY));
                    if (found.isEmpty()) return Optional.empty();
                    foundHolder.set(found.get().getFirst());
                    return Optional.of(createLocatorResult(job, found.get().getSecond(), backend.id(),
                            "poi-manager", true));
                });

                if (result.isPresent()) {
                    LocatorResult located = result.get();
                    BlockPos pos = located.position();
                    Holder<PoiType> holder = foundHolder.get();

                    level.getServer().execute(() -> {
                        BlockPos teleportTarget = locateTeleportTarget(level, pos);
                        source.sendSuccess(() -> Component.literal("✅ Search completed."), false);
                        if (holder != null) {
                            LocateResultHelper.sendResult(source, "commands.locate.poi.success", holder, origin, teleportTarget, true);
                        } else {
                            LocateResultHelper.sendResult(source, "commands.locatefixer.base.success",
                                    located.targetId(), origin, teleportTarget, true);
                        }
                    });
                    completeBackendJob(job, level, located);
                } else {
                    level.getServer().execute(() ->
                            source.sendFailure(Component.literal("❌ POI not found within " + poiRadius + " blocks."))
                    );
                    failJobNotFound(job, "POI not found within " + poiRadius + " blocks.");
                }

            } catch (Exception e) {
                if (e instanceof CancellationException cancellation) throw cancellation;
                job.fail(e);
                LOGGER.error("[LocateUnbound] Unexpected error while locating POI", e);
                level.getServer().execute(() ->
                        source.sendFailure(Component.literal("Locate Unbound error (POI): " + e.getMessage())));
            }
        });
    }

    public static int locateNearestStructuresAsync(CommandSourceStack source, int count) {
        ServerLevel level = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());
        final LocateSettings settings = SETTINGS;

        submitLocateJob(source, LocatorTargetType.STRUCTURE, "*", origin, level, settings, job -> {
            try {
                List<Holder.Reference<Structure>> allStructures = callOnServerThread(level, () ->
                        level.registryAccess().registryOrThrow(Registries.STRUCTURE).holders().toList());
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

                int[] plannedRings = plannedRings(job, settings);
                int ringIndex = 0;
                for (int ring : plannedRings) {
                    job.cancellationToken().throwIfCancelled();
                    ringIndex++;
                    job.searching(Math.max(2, ringIndex * 95 / plannedRings.length),
                            "Sampling structure candidates within " + ring + " blocks");
                    job.incrementCounter("search_stages");
                    final int ringFinal = ring;
                    level.getServer().execute(() -> source.sendSuccess(() ->
                            Component.literal("🔍 Scanning for nearest " + count + " structures up to " + ringFinal + " blocks..."), false));

                    for (BlockPos anchor : createAnchors(origin, ring)) {
                        job.cancellationToken().throwIfCancelled();
                        job.incrementCounter("anchors_sampled");
                        job.incrementCounter("backend_calls");
                        int ringChunks = blocksToChunks(ring);
                        Pair<BlockPos, Holder<Structure>> result = callOnServerThread(level, () ->
                                level.getChunkSource().getGenerator()
                                        .findNearestMapStructure(level, holders, anchor, ringChunks, false));
                        if (result == null) continue;

                        BlockPos pos = result.getFirst();
                        long posKey = pos.asLong();
                        if (!seenPositions.add(posKey)) continue;

                        String name = holderName(result.getSecond());
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
                if (nearest.isEmpty()) {
                    failJobNotFound(job, "No structures found within " + settings.maxRadius() + " blocks.");
                } else {
                    completeJob(job, level, nearest.get(0).pos(), "world-generator", true);
                }
            } catch (Exception e) {
                if (e instanceof CancellationException cancellation) throw cancellation;
                job.fail(e);
                LOGGER.error("[LocateUnbound] Unexpected error while locating nearest structures", e);
                level.getServer().execute(() -> source.sendFailure(Component.literal("Locate Unbound error (structure multi): " + e.getMessage())));
            }
        });

        return count;
    }

    public static int locateNearestBiomesAsync(CommandSourceStack source, int count) {
        ServerLevel level = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());
        final LocateSettings settings = SETTINGS;

        submitLocateJob(source, LocatorTargetType.BIOME, "*", origin, level, settings, job -> {
            try {
                // Collect all biomes registered in this dimension so we can search for each
                // one specifically, rather than matching "any biome" from every anchor point
                // (which produces many duplicate results of the same nearest biome).
                List<Holder<Biome>> allBiomes = callOnServerThread(level, () -> level.getChunkSource()
                        .getGenerator()
                        .getBiomeSource()
                        .possibleBiomes()
                        .stream()
                        .toList());

                Map<String, LocatedEntry> bestByBiome = new LinkedHashMap<>();

                int[] plannedRings = plannedRings(job, settings);
                int ringIndex = 0;
                for (int ring : plannedRings) {
                    job.cancellationToken().throwIfCancelled();
                    ringIndex++;
                    job.searching(Math.max(2, ringIndex * 95 / plannedRings.length),
                            "Sampling biome candidates within " + ring + " blocks");
                    job.incrementCounter("search_stages");
                    int sampleRadius = computeSampleRadius(ring, settings);
                    int step = computeSampleStep(ring, settings);
                    final int ringFinal = ring;
                    level.getServer().execute(() -> source.sendSuccess(() ->
                            Component.literal("🔍 Sampling for nearest " + count + " biomes up to " + ringFinal + " blocks..."), false));

                    for (Holder<Biome> targetBiome : allBiomes) {
                        job.cancellationToken().throwIfCancelled();
                        job.incrementCounter("candidates_sampled");
                        job.incrementCounter("backend_calls");
                        String biomeName = holderName(targetBiome);
                        if (bestByBiome.containsKey(biomeName)) continue; // already found one closer

                        Pair<BlockPos, Holder<Biome>> result = callOnServerThread(level, () -> targetBiome.unwrapKey()
                                .map(key -> level.findClosestBiome3d(h -> h.is(key), origin, ring, sampleRadius, step))
                                .orElse(null));
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
                if (nearest.isEmpty()) {
                    failJobNotFound(job, "No biomes found within " + settings.maxRadius() + " blocks.");
                } else {
                    completeJob(job, level, nearest.get(0).pos(), "world-generator", true);
                }
            } catch (Exception e) {
                if (e instanceof CancellationException cancellation) throw cancellation;
                job.fail(e);
                LOGGER.error("[LocateUnbound] Unexpected error while locating nearest biomes", e);
                level.getServer().execute(() -> source.sendFailure(Component.literal("Locate Unbound error (biome multi): " + e.getMessage())));
            }
        });

        return count;
    }

    // ---------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------

    private static void submitLocateJob(CommandSourceStack source,
                                        LocatorTargetType targetType,
                                        String targetId,
                                        BlockPos origin,
                                        ServerLevel level,
                                        LocateSettings settings,
                                        LocateJobManager.LocateJobTask task) {
        submitLocateJob(source, targetType, targetId, origin, level, settings.maxRadius(), task);
    }

    private static void submitLocateJob(CommandSourceStack source,
                                        LocatorTargetType targetType,
                                        String targetId,
                                        BlockPos origin,
                                        ServerLevel level,
                                        int maxRadius,
                                        LocateJobManager.LocateJobTask task) {
        LocatorRequest request = LocatorRequest.create(playerKey(source), targetType, targetId,
                level.dimension().location().toString(), origin, maxRadius);
        LocateJobManager.Submission submission = LocateRuntime.jobs().submit(request, job -> {
            job.selectBackend(LocateRuntime.backends().select(targetType)
                    .map(backend -> backend.id()).orElse("locatefixer:vanilla"));
            job.attribute("biomespy", Boolean.toString(LocateRuntime.integrations().active("biomespy")));
            task.run(job);
        });
        if (!submission.accepted()) {
            source.sendFailure(Component.literal("⏳ " + submission.rejectionMessage()));
        }
    }

    private static int[] plannedRings(LocateJob job, LocateSettings settings) {
        SearchPlan plan = plannedSearchPlan(job, settings);
        return plan.stages().stream().mapToInt(SearchStage::radius).toArray();
    }

    private static SearchPlan plannedSearchPlan(LocateJob job, LocateSettings settings) {
        SearchPlan plan;
        if (com.thunder.locatefixer.config.LocateFixerConfig.SERVER.adaptiveSearchEnabled.get()) {
            plan = LocateRuntime.planner().plan(job.request(), settings.rings(),
                    LocateRuntime.searchHistory().contextFor(job.request()),
                    settings.biomeSampleRadiusMultiplier(), settings.biomeSampleStepMultiplier());
        } else {
            double radiusMultiplier = job.request().targetType() == LocatorTargetType.BIOME
                    ? settings.biomeSampleRadiusMultiplier() : 1.0D;
            double stepMultiplier = job.request().targetType() == LocatorTargetType.BIOME
                    ? settings.biomeSampleStepMultiplier() : 1.0D;
            List<SearchStage> stages = Arrays.stream(settings.rings())
                    .filter(radius -> radius > 0 && radius <= job.request().maxRadius())
                    .distinct()
                    .sorted()
                    .mapToObj(radius -> new SearchStage(radius, radiusMultiplier, stepMultiplier,
                            "configured fallback"))
                    .toList();
            if (stages.isEmpty()) {
                stages = List.of(new SearchStage(job.request().maxRadius(), radiusMultiplier,
                        stepMultiplier, "maximum-radius fallback"));
            }
            plan = new SearchPlan(stages, stages.get(stages.size() - 1).radius(), false);
        }
        job.attribute("adaptive_plan", Boolean.toString(plan.adaptive()));
        job.attribute("planned_stages", Integer.toString(plan.stages().size()));
        return plan;
    }

    private static SearchPlan slicePlan(SearchPlan plan, int startIndex) {
        int clampedStart = Mth.clamp(startIndex, 0, plan.stages().size() - 1);
        return new SearchPlan(plan.stages().subList(clampedStart, plan.stages().size()),
                plan.maxRadius(), plan.adaptive());
    }

    private static SearchPlan oneStagePlan(int radius, String reason) {
        return new SearchPlan(List.of(new SearchStage(radius, 1.0D, 1.0D, reason)), radius, false);
    }

    private static LocatorBackend selectedBackend(LocateJob job) {
        LocatorBackend backend = LocateRuntime.backends().select(job.request().targetType())
                .orElseThrow(() -> new IllegalStateException(
                        "No locator backend is available for " + job.request().targetType()));
        job.selectBackend(backend.id());
        return backend;
    }

    private static void completeJob(LocateJob job, ServerLevel level, BlockPos position,
                                    String discoverySource, boolean verified) throws Exception {
        LocatorResult result = createLocatorResult(job, position, job.snapshot().backendId(),
                discoverySource, verified);
        completeBackendJob(job, level, result);
    }

    private static LocatorResult createLocatorResult(LocateJob job, BlockPos position, String backendId,
                                                       String discoverySource, boolean verified) {
        int distance = horizontalDistance(job.request().origin(), position);
        return new LocatorResult(job.request().targetType(), job.request().targetId(),
                job.request().dimensionId(), position, backendId, discoverySource,
                Instant.now(), true, verified, Map.of("distance", Integer.toString(distance)));
    }

    private static void completeBackendJob(LocateJob job, ServerLevel level, LocatorResult result) throws Exception {
        if (result.targetType() != job.request().targetType()
                || !result.dimensionId().equals(job.request().dimensionId())
                || horizontalDistance(job.request().origin(), result.position()) > job.request().maxRadius()) {
            throw new IllegalArgumentException("Backend returned an out-of-scope locate result");
        }
        int distance = horizontalDistance(job.request().origin(), result.position());
        job.selectBackend(result.backendId());
        job.attribute("result_distance", Integer.toString(distance));
        LocateRuntime.searchHistory().recordSuccess(job.request(), distance, 50);
        if (com.thunder.locatefixer.config.LocateFixerConfig.SERVER.persistentIndexEnabled.get()) {
            callOnServerThread(level, () -> {
                WorldLocatorIndex.get(level).record(result);
                return null;
            });
        }
        job.found(result);
    }

    private static Optional<LocatorResult> findIndexed(LocateJob job, ServerLevel level) throws Exception {
        if (!com.thunder.locatefixer.config.LocateFixerConfig.SERVER.persistentIndexEnabled.get()) {
            return Optional.empty();
        }
        Optional<LocatorResult> result = callOnServerThread(level, () -> WorldLocatorIndex.get(level).findNearest(
                job.request().targetType(), job.request().targetId(), job.request().dimensionId(),
                job.request().origin(), job.request().maxRadius()));
        job.incrementCounter(result.isPresent() ? "index_hits" : "index_misses");
        return result;
    }

    private static void completeIndexedJob(LocateJob job, LocatorResult result) {
        job.selectBackend("locatefixer:persistent-index");
        LocateRuntime.searchHistory().recordSuccess(job.request(),
                horizontalDistance(job.request().origin(), result.position()), 1);
        job.found(result);
    }

    private static Optional<LocatorResult> invokeProvider(LocatorProvider provider,
                                                           LocateJob job,
                                                           ServerLevel level,
                                                           BlockPos origin,
                                                           int requestedRadius) {
        String dimensionId = level.dimension().location().toString();
        if (!provider.supportedDimensions().isEmpty()
                && !provider.supportedDimensions().contains(dimensionId)) {
            return Optional.empty();
        }
        int radius = Math.min(requestedRadius, provider.maximumRadius());
        Callable<Optional<LocatorResult>> locate = () -> provider.locate(
                level, job.request().targetId(), origin, radius, job.cancellationToken());
        try {
            return provider.threadSafety() == LocatorThreadSafety.WORKER_SAFE
                    ? locate.call()
                    : callOnServerThread(level, locate);
        } catch (Exception failure) {
            throw new CompletionException(failure);
        }
    }

    private static void completeProviderJob(LocateJob job, ServerLevel level,
                                            LocatorProvider provider, LocatorResult result) throws Exception {
        if (!result.dimensionId().equals(job.request().dimensionId())
                || result.targetType() != job.request().targetType()
                || horizontalDistance(job.request().origin(), result.position()) > job.request().maxRadius()) {
            throw new IllegalArgumentException("Provider returned an out-of-scope locate result");
        }
        int distance = horizontalDistance(job.request().origin(), result.position());
        job.selectBackend(result.backendId());
        job.attribute("result_distance", Integer.toString(distance));
        LocateRuntime.searchHistory().recordSuccess(job.request(), distance, provider.estimatedSearchCost());
        if (com.thunder.locatefixer.config.LocateFixerConfig.SERVER.persistentIndexEnabled.get()
                && provider.cachePolicy().allowsPersistentIndex()) {
            callOnServerThread(level, () -> {
                WorldLocatorIndex.get(level).record(result);
                return null;
            });
        }
        job.found(result);
    }

    private static void failJobNotFound(LocateJob job, String message) {
        LocateRuntime.searchHistory().recordFailure(job.request(), job.request().maxRadius(), 50);
        job.notFound(message);
    }

    /**
     * Returns a stable key identifying the requesting entity (player name or "server").
     */
    private static String playerKey(CommandSourceStack source) {
        return source.getTextName();
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
        int maxEntries = com.thunder.locatefixer.config.LocateFixerConfig.SERVER.cacheMaxEntries.get();
        if (cache.size() >= maxEntries) {
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
        return computeSampleRadius(searchRadius, settings.biomeSampleRadiusMultiplier());
    }

    private static int computeSampleRadius(int searchRadius, double multiplier) {
        int computed = Mth.clamp(searchRadius / 256, 16, 96);
        return (int) Mth.clamp((float) Math.round(computed * multiplier), 16, 256);
    }

    private static int computeSampleStep(int searchRadius, LocateSettings settings) {
        return computeSampleStep(searchRadius, settings.biomeSampleStepMultiplier());
    }

    private static int computeSampleStep(int searchRadius, double multiplier) {
        int computed = Mth.clamp(searchRadius / 192, 24, 128);
        return (int) Mth.clamp((float) Math.round(computed * multiplier), 16, 256);
    }

    /**
     * Minecraft's structure locator accepts a radius in chunks, while Locate Unbound's
     * public configuration is expressed in blocks.
     */
    private static int blocksToChunks(int radiusBlocks) {
        long chunks = ((long) radiusBlocks + 15L) / 16L;
        return (int) Mth.clamp(chunks, 1L, Integer.MAX_VALUE);
    }

    private static Optional<? extends HolderSet.ListBacked<Structure>> resolveStructureHolders(
            ResourceOrTagKeyArgument.Result<Structure> requested,
            Registry<Structure> registry
    ) {
        return requested.unwrap().map(
                key -> registry.getHolder(key).map(HolderSet::direct),
                registry::getTag
        );
    }

    /**
     * Runs live level/chunk/POI access on the thread that owns the server. The locate
     * worker waits for the result, leaving command dispatch asynchronous without racing
     * Minecraft's non-concurrent world-state caches.
     */
    public static <T> T callOnServerThread(ServerLevel level, Callable<T> task) throws Exception {
        if (level.getServer().isSameThread()) {
            return task.call();
        }

        CompletableFuture<T> result = new CompletableFuture<>();
        level.getServer().execute(() -> {
            try {
                result.complete(task.call());
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            }
        });

        try {
            return result.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Locate search interrupted while waiting for the server thread");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        }
    }

    private static BlockPos structureTeleportTarget(ServerLevel level, BlockPos structurePos, Holder<Structure> holder) {
        BlockPos refinedPos = resolveStructureCenter(level, structurePos, holder);
        int clampedY = Mth.clamp(refinedPos.getY(), level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        return new BlockPos(refinedPos.getX(), clampedY, refinedPos.getZ());
    }

    private static BlockPos resolveStructureCenter(ServerLevel level, BlockPos locatePos, Holder<Structure> holder) {
        try {
            ChunkPos anchorChunk = new ChunkPos(locatePos);
            BlockPos bestCenter = null;
            double bestDistanceSq = Double.MAX_VALUE;

            for (int dx = -STRUCTURE_START_SCAN_RADIUS_CHUNKS; dx <= STRUCTURE_START_SCAN_RADIUS_CHUNKS; dx++) {
                for (int dz = -STRUCTURE_START_SCAN_RADIUS_CHUNKS; dz <= STRUCTURE_START_SCAN_RADIUS_CHUNKS; dz++) {
                    ChunkAccess chunk = level.getChunk(anchorChunk.x + dx, anchorChunk.z + dz, ChunkStatus.STRUCTURE_STARTS);
                    StructureStart start = level.structureManager()
                            .getStartForStructure(SectionPos.bottomOf(chunk), holder.value(), chunk);
                    if (start == null || !start.isValid()) {
                        continue;
                    }

                    BlockPos center = start.getBoundingBox().getCenter();
                    double distanceSq = center.distSqr(locatePos);
                    if (distanceSq < bestDistanceSq) {
                        bestDistanceSq = distanceSq;
                        bestCenter = center;
                    }
                }
            }

            if (bestCenter != null) {
                return bestCenter;
            }
        } catch (Exception e) {
            LOGGER.debug("[LocateUnbound] Could not refine structure locate target for '{}'.",
                    holderName(holder), e);
        }

        return locatePos;
    }

    private static BlockPos locateTeleportTarget(ServerLevel level, BlockPos locatedPos) {
        int y = Mth.clamp(locatedPos.getY(), level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        return new BlockPos(locatedPos.getX(), y, locatedPos.getZ());
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
        long dx = (long) target.getX() - origin.getX();
        long dz = (long) target.getZ() - origin.getZ();
        return dx * dx + dz * dz;
    }

    private static String canonicalStructureTarget(ResourceOrTagKeyArgument.Result<Structure> requested,
                                                   HolderSet<Structure> holders) {
        if (holders.size() == 1) {
            return holders.stream().findFirst().map(AsyncLocateHandler::holderName).orElse(requested.asPrintable());
        }
        // For tags/multi-target searches, cache by resolved structure ids so different
        // queries cannot accidentally share results.
        return holders.stream()
                .map(AsyncLocateHandler::holderName)
                .filter(Objects::nonNull)
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .map(ids -> "set:" + ids)
                .orElse(requested.asPrintable());
    }

    private static Set<String> resolvedStructureIds(HolderSet<Structure> holders) {
        Set<String> ids = new HashSet<>();
        for (Holder<Structure> holder : holders) {
            String id = holderName(holder);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static String holderName(Holder<?> holder) {
        return holder.unwrapKey()
                .map(key -> key.location().toString())
                .orElse("unknown");
    }

    private static void sendRingProgressUpdate(ServerLevel level, CommandSourceStack source,
                                               int scanRadius, int step, int totalSteps, long startedAtMs) {
        int progressPercent = Mth.clamp((int) Math.round((step * 100.0D) / totalSteps), 1, 100);
        long elapsedMs = Math.max(1L, System.currentTimeMillis() - startedAtMs);
        long avgStepMs = elapsedMs / Math.max(1, step);
        long remainingMs = Math.max(0L, avgStepMs * (totalSteps - step));
        long remainingSeconds = TimeUnit.MILLISECONDS.toSeconds(remainingMs);
        String etaText = remainingSeconds > 0 ? " ⏳ ~" + remainingSeconds + "s remaining" : "";
        long approxChunks = approximateChunksInRadius(scanRadius);
        int completionPercent = Math.max(1, progressPercent);
        long approxChunksCovered = Math.max(1L, Math.round(approxChunks * (completionPercent / 100.0D)));
        String radiusText = "radius " + scanRadius + " blocks (~" + approxChunks + " chunks)";
        String searchStateText;
        if (scanRadius > 6400) {
            int lanesPassed = Math.max(1, scanRadius / 6400);
            searchStateText = "🔍 Extending search radius... passed " + lanesPassed + " lane(s) of 6400 blocks, " + radiusText;
        } else {
            searchStateText = "🔍 Searching... " + radiusText;
        }

        level.getServer().execute(() -> source.sendSuccess(() ->
                Component.literal(searchStateText + " [ring " + step + "/" + totalSteps + ", " + progressPercent + "%, "
                        + "approx chunks scanned " + approxChunksCovered + "/" + approxChunks + "]" + etaText), false));
    }

    private static void sendLocateStartUpdate(ServerLevel level, CommandSourceStack source, String kind,
                                              String query, int ringCount, int maxRadius) {
        level.getServer().execute(() -> source.sendSuccess(() ->
                Component.literal("🧭 Starting " + kind + " search for '" + query + "' with "
                        + ringCount + " ring(s), max radius " + maxRadius + " blocks."), false));
    }

    private static void sendLocateCompletionUpdate(CommandSourceStack source, long startedAtMs, int step, int totalSteps,
                                                   BlockPos locatedPos, BlockPos teleportTarget) {
        long elapsedMs = Math.max(1L, System.currentTimeMillis() - startedAtMs);
        String elapsedText = String.format(java.util.Locale.ROOT, "%.2fs", elapsedMs / 1000.0D);
        source.sendSuccess(() -> Component.literal("✅ Search complete in " + elapsedText
                + " (ring " + step + "/" + totalSteps + ")."), false);
        source.sendSuccess(() -> Component.literal("🛰 Teleport prep: located "
                + locatedPos.getX() + " " + locatedPos.getY() + " " + locatedPos.getZ()
                + " → target " + teleportTarget.getX() + " " + teleportTarget.getY() + " " + teleportTarget.getZ() + "."), false);
    }

    private static long approximateChunksInRadius(int radiusBlocks) {
        double area = Math.PI * radiusBlocks * radiusBlocks;
        return Math.max(1L, Math.round(area / 256.0D));
    }

    public static void reloadConfig() {
        LocateSettings newSettings = loadSettings();
        synchronized (AsyncLocateHandler.class) {
            int previousThreads = SETTINGS.threadCount();
            SETTINGS = newSettings;
            if (LOCATE_EXECUTOR == null || LOCATE_EXECUTOR.isShutdown()) {
                LOCATE_EXECUTOR = buildExecutor(newSettings.threadCount());
            } else if (previousThreads != newSettings.threadCount()) {
                ExecutorService oldExecutor = LOCATE_EXECUTOR;
                LOCATE_EXECUTOR = buildExecutor(newSettings.threadCount());
                oldExecutor.shutdown();
            }
        }
        clearCaches();
        LOGGER.info("[LocateUnbound] Reloaded locate settings: {} rings, {}ms cache, {} thread(s).",
                newSettings.rings().length, newSettings.cacheDurationMs(), newSettings.threadCount());
    }

    public static void clearCaches() {
        CACHE_EPOCH.incrementAndGet();
        STRUCTURE_CACHE.clear();
        BIOME_CACHE.clear();
    }

    public static int cacheEntryCount() {
        return STRUCTURE_CACHE.size() + BIOME_CACHE.size();
    }

    public static void shutdownForServerStop() {
        synchronized (AsyncLocateHandler.class) {
            if (LOCATE_EXECUTOR != null) {
                LOCATE_EXECUTOR.shutdownNow();
                LOCATE_EXECUTOR = null;
            }
        }
        clearCaches();
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
            LOGGER.debug("[LocateUnbound] Config not ready yet, using default locate settings.");
            return DEFAULT_SETTINGS;
        }
    }

    private static ExecutorService buildExecutor(int threadCount) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(threadCount, threadCount, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), THREAD_FACTORY);
        executor.allowCoreThreadTimeOut(false);
        return executor;
    }

    private static ExecutorService executor() {
        ExecutorService executor = LOCATE_EXECUTOR;
        if (executor != null && !executor.isShutdown()) {
            return executor;
        }

        synchronized (AsyncLocateHandler.class) {
            if (LOCATE_EXECUTOR == null || LOCATE_EXECUTOR.isShutdown()) {
                LOCATE_EXECUTOR = buildExecutor(SETTINGS.threadCount());
            }
            return LOCATE_EXECUTOR;
        }
    }

    private static ThreadFactory buildThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("LocateUnbound-Background-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    // ---------------------------------------------------------------------------
    // Records
    // ---------------------------------------------------------------------------

    private record LocateCacheKey(long epoch, String dimension, String target, int coarseChunkX, int coarseChunkZ) {
        private static LocateCacheKey of(ServerLevel level, String target, BlockPos origin, int granularity) {
            int chunkX = origin.getX() >> 4;
            int chunkZ = origin.getZ() >> 4;
            int coarseX = Math.floorDiv(chunkX, granularity);
            int coarseZ = Math.floorDiv(chunkZ, granularity);
            return new LocateCacheKey(CACHE_EPOCH.get(), level.dimension().location().toString(), target, coarseX, coarseZ);
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
