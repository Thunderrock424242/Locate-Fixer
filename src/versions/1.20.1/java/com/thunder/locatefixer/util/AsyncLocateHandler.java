package com.thunder.locatefixer.util;

import com.mojang.datafixers.util.Pair;
import com.thunder.locatefixer.LocateRuntime;
import com.thunder.locatefixer.api.LocatorResult;
import com.thunder.locatefixer.api.LocatorTargetType;
import com.thunder.locatefixer.api.LocatorProvider;
import com.thunder.locatefixer.api.LocatorProviderRegistry;
import com.thunder.locatefixer.api.StructureLocatorRegistry;
import com.thunder.locatefixer.backend.LocatorBackend;
import com.thunder.locatefixer.job.LocateJob;
import com.thunder.locatefixer.job.LocateJobManager;
import com.thunder.locatefixer.job.LocateJobSubmissions;
import com.thunder.locatefixer.index.WorldLocatorIndex;
import com.thunder.locatefixer.search.LocateSearchMessages;
import com.thunder.locatefixer.search.SearchPlan;
import com.thunder.locatefixer.search.SearchStage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceOrTagArgument;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.util.*;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.thunder.locatefixer.LocateFixerMod.LOGGER;

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
    private static volatile ExecutorService LOCATE_EXECUTOR;

    private static final ConcurrentMap<LocateCacheKey, LocateCacheEntry<Structure>> STRUCTURE_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<LocateCacheKey, LocateCacheEntry<Biome>> BIOME_CACHE = new ConcurrentHashMap<>();
    private static final AtomicLong CACHE_EPOCH = new AtomicLong();
    private static final ConcurrentMap<UUID, CacheScope> JOB_SCOPES = new ConcurrentHashMap<>();

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
        locateStructureAsync(source, structure, origin, level, false);
    }

    public static void locateStructureAsync(CommandSourceStack source, ResourceOrTagKeyArgument.Result<Structure> structure, BlockPos origin, ServerLevel level, boolean teleportRequested) {
        final LocateSettings settings = SETTINGS;
        submitLocateJob(source, LocatorTargetType.STRUCTURE, structure.asPrintable(), origin, level, settings, job -> {
            try {
                int[] rings = plannedRings(job, settings);
                if (rings.length == 0) {
                    executeOnServerThread(level, () ->
                            source.sendFailure(Component.literal("❌ No locate search radii configured.")));
                    return;
                }

                Optional<? extends HolderSet.ListBacked<Structure>> resolved = callOnServerThread(level, () -> {
                    Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
                    return resolveStructureHolders(structure, registry);
                });
                if (resolved.isEmpty()) {
                    String message = "Unknown or empty structure target: " + structure.asPrintable();
                    executeOnServerThread(level, () -> source.sendFailure(Component.literal(message)));
                    job.notFound(message);
                    return;
                }

                HolderSet<Structure> holders = callOnServerThread(level, () -> HolderSet.direct(resolved.get().stream()
                        .filter(holder -> !level.getChunkSource().getGeneratorState().getPlacementsForStructure(holder).isEmpty())
                        .toList()));
                if (holders.size() == 0) {
                    String message = "Structure target " + structure.asPrintable() + " cannot generate in "
                            + level.dimension().location() + ".";
                    executeOnServerThread(level, () -> source.sendFailure(Component.literal(message)));
                    job.unavailable(message);
                    return;
                }
                int placementStep = callOnServerThread(level, () -> structurePlacementStep(level, holders));
                String canonicalTarget = structure.asPrintable();
                Set<String> allowedStructureIds = resolvedStructureIds(holders);

                LocateCacheKey cacheKey = LocateCacheKey.of(level, canonicalTarget, origin, job.request().maxRadius());
                LocateCacheEntry<Structure> cacheEntry = getValidCacheEntry(STRUCTURE_CACHE, cacheKey, settings.cacheDurationMs());
                job.incrementCounter(cacheEntry == null ? "cache_misses" : "cache_hits");

                if (cacheEntry != null) {
                    BlockPos cachedPos = cacheEntry.pos();
                    if (horizontalDistanceSq(origin, cachedPos) <= (long) job.request().maxRadius() * job.request().maxRadius()) {
                        Holder<Structure> holder = cacheEntry.holder();
                        completeJob(job, level, cachedPos, "memory-cache", true);
                        executeOnServerThread(level, () -> {
                            BlockPos surfacePos = cachedPos;
                            LocateResultHelper.sendResult(source, "commands.locate.structure.success", holder, origin, surfacePos, true);
                            requestTeleportIfNeeded(source, level, surfacePos, teleportRequested);
                        });
                        return;
                    }
                }
                Optional<LocatorResult> indexed = findIndexed(job, level);
                SearchPlan fullPlan = plannedSearchPlan(job, settings);
                SearchPlan activePlan = withIndexedCandidate(fullPlan, indexed, origin);
                int totalSteps = activePlan.stages().size();
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
                    sendRingProgressUpdate(level, source, "structure", scanRadius, step, totalSteps);
                    LOGGER.info("[LocateUnbound] Scanning for structure up to {} blocks", scanRadius);

                    int scanRadiusChunks = structureSearchRings(scanRadius, placementStep);
                    job.incrementCounter("backend_calls");
                    Pair<BlockPos, Holder<Structure>> result = callOnServerThread(level, () ->
                            level.getChunkSource().getGenerator()
                                    .findNearestMapStructure(level, holders, origin, scanRadiusChunks, false));

                    if (result != null) {
                        BlockPos pos = result.getFirst();
                        if (horizontalDistanceSq(origin, pos) > (long) scanRadius * scanRadius) return Optional.empty();
                        Holder<Structure> holder = result.getSecond();
                        String foundId = holderName(holder);
                        if (!allowedStructureIds.contains(foundId)) {
                            LOGGER.warn("[LocateUnbound] Ignoring locate candidate '{}' for request '{}'; resolved allowed ids={}",
                                    foundId, canonicalTarget, allowedStructureIds);
                            return Optional.empty();
                        }

                        putWithEviction(STRUCTURE_CACHE, cacheKey, new LocateCacheEntry<>(pos, holder, System.nanoTime()));
                        foundHolder.set(holder);
                        return Optional.of(createLocatorResult(job, pos, backend.id(), "world-generator", true));
                    }

                    return Optional.empty();
                });

                if (located.isPresent()) {
                    LocatorResult result = located.get();
                    Holder<Structure> holder = foundHolder.get();
                    completeBackendJob(job, level, result);
                    executeOnServerThread(level, () -> {
                        BlockPos surfacePos = result.position();
                        if (holder != null) {
                            LocateResultHelper.sendResult(source, "commands.locate.structure.success", holder, origin, surfacePos, true);
                            requestTeleportIfNeeded(source, level, surfacePos, teleportRequested);
                        } else {
                            LocateResultHelper.sendResult(source, "commands.locatefixer.base.success",
                                    result.targetId(), origin, surfacePos, true);
                        }
                    });
                    return;
                }

                executeOnServerThread(level, () ->
                        source.sendFailure(Component.literal("❌ Structure not found within " + settings.maxRadius() + " blocks.")));
                failJobNotFound(job, "Structure not found within " + settings.maxRadius() + " blocks.");

            } catch (Exception e) {
                if (e instanceof CancellationException cancellation) throw cancellation;
                job.fail(e);
                LOGGER.error("[LocateUnbound] Unexpected error while locating structure", e);
                executeOnServerThread(level, () ->
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
                    executeOnServerThread(level, () ->
                            source.sendFailure(Component.literal("❌ No locate search radii configured.")));
                    return;
                }

                if (registeredProvider.isPresent() && !registeredProvider.get().supportedDimensions().isEmpty()
                        && !registeredProvider.get().supportedDimensions().contains(job.request().dimensionId())) {
                    String message = "Target " + structureId + " is unavailable in " + job.request().dimensionId() + ".";
                    executeOnServerThread(level, () -> source.sendFailure(Component.literal(message)));
                    job.unavailable(message);
                    return;
                }
                int maxRadius = Math.min(job.request().maxRadius(), registeredProvider.map(LocatorProvider::maximumRadius).orElse(job.request().maxRadius()));
                Optional<LocatorResult> memoryCached = registeredProvider.isPresent()
                        && registeredProvider.get().cachePolicy().allowsMemoryCache()
                        ? LocateRuntime.providerCache().find(job.request(), settings.cacheGranularity(),
                                settings.cacheDurationMs(), providerScope(job, registeredProvider.orElseThrow()))
                        : Optional.empty();
                if (registeredProvider.isPresent()
                        && registeredProvider.get().cachePolicy().allowsMemoryCache()) {
                    job.incrementCounter(memoryCached.isPresent() ? "provider_cache_hits" : "provider_cache_misses");
                }
                if (memoryCached.isPresent()) {
                    LocatorResult cachedResult = memoryCached.get();
                    LocatorProvider provider = registeredProvider.orElseThrow();
                    completeProviderMemoryJob(job, provider, cachedResult);
                    executeOnServerThread(level, () -> {
                        BlockPos teleportTarget = cachedResult.position();
                        if (!provider.safelyTeleportable()) {
                            source.sendSuccess(() -> Component.literal("✅ " + cachedResult.targetId() + " at "
                                    + teleportTarget.getX() + " " + teleportTarget.getY() + " "
                                    + teleportTarget.getZ()), false);
                        } else {
                            LocateResultHelper.sendResult(source, "commands.locatefixer.base.success",
                                    cachedResult.targetId(), origin, teleportTarget, true);
                        }
                    });
                    return;
                }
                Optional<LocatorResult> indexed = registeredProvider.isPresent()
                        && !registeredProvider.get().cachePolicy().allowsPersistentIndex()
                        ? Optional.empty() : findIndexed(job, level);
                executeOnServerThread(level, () -> source.sendSuccess(() ->
                        Component.literal("🔍 Locating structure '" + structureId + "'..."), false));

                // Providers receive a live ServerLevel, so invoke them on the owning server thread.
                // This is safe for ordinary mod state and avoids forcing every integration to be
                // independently thread-safe.
                Optional<LocatorProvider> provider = registeredProvider;
                LocatorBackend backend = selectedBackend(job);
                SearchPlan providerPlan = withIndexedCandidate(oneStagePlan(maxRadius, "provider lookup"), indexed, origin);
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
                            .filter(position -> horizontalDistanceSq(origin, position) <= (long) stage.radius() * stage.radius())
                            .map(position -> createLocatorResult(job, position, backend.id(),
                                    "legacy-custom-provider", true));
                });
                Optional<BlockPos> result = backendResult.map(LocatorResult::position);

                if (result.isPresent()) {
                    if (provider.isPresent()) {
                        completeProviderJob(job, level, provider.get(), backendResult.orElseThrow());
                    } else {
                        completeBackendJob(job, level, backendResult.orElseThrow());
                    }
                } else {
                    failJobNotFound(job, "Custom structure not found within " + maxRadius + " blocks.");
                }
                executeOnServerThread(level, () -> {
                    if (result.isEmpty()) {
                        source.sendFailure(Component.literal("❌ Structure '" + structureId + "' was not found within " + maxRadius + " blocks."));
                        return;
                    }

                    BlockPos teleportTarget = result.get();
                    if (provider.isPresent() && !provider.get().safelyTeleportable()) {
                        source.sendSuccess(() -> Component.literal("✅ " + structureId + " at "
                                + teleportTarget.getX() + " " + teleportTarget.getY() + " " + teleportTarget.getZ()), false);
                    } else {
                        LocateResultHelper.sendResult(source, "commands.locatefixer.base.success",
                                structureId, origin, teleportTarget, true);
                    }
                });

            } catch (Exception e) {
                if (e instanceof CancellationException cancellation) throw cancellation;
                job.fail(e);
                LOGGER.error("[LocateUnbound] Unexpected error while locating custom structure '{}'", structureId, e);
                executeOnServerThread(level, () ->
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
                            "biome-generation-capability", false, false));
                });
                if (result.isPresent()) {
                    LocatorResult located = result.get();
                    completeBackendJob(job, level, located);
                    executeOnServerThread(level, () -> {
                        BlockPos position = located.position();
                        source.sendSuccess(() -> Component.literal("✅ Nearest biome capable of generating '"
                                + featureId + "' is near " + position.getX() + " " + position.getY() + " "
                                + position.getZ() + ". This does not confirm a placed feature exists there."), false);
                    });
                } else {
                    executeOnServerThread(level, () -> source.sendFailure(Component.literal(
                            "❌ No biome capable of generating feature '" + featureId + "' was found within "
                                    + settings.maxRadius() + " blocks.")));
                    failJobNotFound(job, "Feature-capable biome not found within "
                            + settings.maxRadius() + " blocks.");
                }
            } catch (Exception failure) {
                if (failure instanceof CancellationException cancellation) throw cancellation;
                job.fail(failure);
                LOGGER.error("[LocateUnbound] Unexpected error while locating feature '{}'", featureId, failure);
                executeOnServerThread(level, () -> source.sendFailure(Component.literal(
                        "Locate Unbound error (feature): " + failure.getMessage())));
            }
        });
    }

    @FunctionalInterface
    public interface FeatureSearchOperation {
        Optional<BlockPos> search(int[] rings, com.thunder.locatefixer.search.LocateCancellationToken token);
    }

    public static void locateBiomeAsync(CommandSourceStack source, ResourceOrTagArgument.Result<Biome> biome, BlockPos origin, ServerLevel level) {
        locateBiomeAsync(source, biome, origin, level, false);
    }

    public static void locateBiomeAsync(CommandSourceStack source, ResourceOrTagArgument.Result<Biome> biome, BlockPos origin, ServerLevel level, boolean teleportRequested) {
        final LocateSettings settings = SETTINGS;
        submitLocateJob(source, LocatorTargetType.BIOME, biome.asPrintable(), origin, level, settings, job -> {
            try {
                int[] rings = plannedRings(job, settings);
                if (rings.length == 0) {
                    executeOnServerThread(level, () ->
                            source.sendFailure(Component.literal("❌ No locate search radii configured.")));
                    return;
                }

                BiomeSourceStatus biomeSourceStatus = inspectBiomeSource(level, biome);
                job.attribute("biome_source", biomeSourceStatus.sourceType());
                job.attribute("biome_possible_count", Integer.toString(biomeSourceStatus.possibleBiomeCount()));
                job.attribute("biome_target_available", Boolean.toString(biomeSourceStatus.targetAvailable()));
                if (!biomeSourceStatus.targetAvailable()) {
                    String dimension = level.dimension().location().toString();
                    String failureMessage = LocateSearchMessages.biomeUnavailable(biome.asPrintable(), dimension);
                    LOGGER.warn("[LocateUnbound] Skipping biome radius search for '{}' in {}: active source {} exposes {} possible biome(s), but none match the target.",
                            biome.asPrintable(), dimension, biomeSourceStatus.sourceType(), biomeSourceStatus.possibleBiomeCount());
                    executeOnServerThread(level, () -> source.sendFailure(Component.literal(failureMessage)));
                    // This is a worldgen availability rejection, not evidence that the configured radius was searched.
                    job.unavailable(failureMessage);
                    return;
                }

                LocateCacheKey cacheKey = LocateCacheKey.of(level, biome.asPrintable(), origin, job.request().maxRadius());
                LocateCacheEntry<Biome> cacheEntry = getValidCacheEntry(BIOME_CACHE, cacheKey, settings.cacheDurationMs());
                job.incrementCounter(cacheEntry == null ? "cache_misses" : "cache_hits");

                if (cacheEntry != null) {
                    BlockPos cachedPos = cacheEntry.pos();
                    if (horizontalDistanceSq(origin, cachedPos) <= (long) job.request().maxRadius() * job.request().maxRadius()) {
                        Holder<Biome> holder = cacheEntry.holder();
                        completeJob(job, level, cachedPos, "memory-cache", true);
                        executeOnServerThread(level, () -> {
                            BlockPos teleportTarget = cachedPos;
                            LocateResultHelper.sendResult(source, "commands.locate.biome.success", holder, origin, teleportTarget, true);
                            requestTeleportIfNeeded(source, level, teleportTarget, teleportRequested);
                        });
                        return;
                    }
                }

                Optional<LocatorResult> indexed = findIndexed(job, level);

                SearchPlan fullPlan = plannedSearchPlan(job, settings);
                SearchPlan activePlan = withIndexedCandidate(fullPlan, indexed, origin);
                int totalSteps = activePlan.stages().size();
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
                    sendRingProgressUpdate(level, source, "biome", scanRadius, step, totalSteps);
                    LOGGER.info("[LocateUnbound] Scanning for biome up to {} blocks", scanRadius);

                    Pair<BlockPos, Holder<Biome>> result = callOnServerThread(level, () ->
                            level.findClosestBiome3d(biome, origin, scanRadius,
                                    computeSampleRadius(scanRadius, stage.sampleRadiusMultiplier()),
                                    computeSampleStep(scanRadius, stage.sampleStepMultiplier())));
                    job.incrementCounter("backend_calls");
                    if (result != null) {
                        BlockPos pos = result.getFirst();
                        if (horizontalDistanceSq(origin, pos) > (long) scanRadius * scanRadius) return Optional.empty();
                        Holder<Biome> holder = result.getSecond();

                        putWithEviction(BIOME_CACHE, cacheKey, new LocateCacheEntry<>(pos, holder, System.nanoTime()));
                        foundHolder.set(holder);
                        return Optional.of(createLocatorResult(job, pos, backend.id(), "world-generator", true));
                    }

                    return Optional.empty();
                });

                if (located.isPresent()) {
                    LocatorResult result = located.get();
                    Holder<Biome> holder = foundHolder.get();
                    completeBackendJob(job, level, result);
                    executeOnServerThread(level, () -> {
                        BlockPos teleportTarget = result.position();
                        if (holder != null) {
                            LocateResultHelper.sendResult(source, "commands.locate.biome.success", holder, origin, teleportTarget, true);
                            requestTeleportIfNeeded(source, level, teleportTarget, teleportRequested);
                        } else {
                            LocateResultHelper.sendResult(source, "commands.locatefixer.base.success",
                                    result.targetId(), origin, teleportTarget, true);
                        }
                    });
                    return;
                }

                executeOnServerThread(level, () ->
                        source.sendFailure(Component.literal("❌ Biome not found within " + settings.maxRadius() + " blocks.")));
                failJobNotFound(job, "Biome not found within " + settings.maxRadius() + " blocks.");

            } catch (Exception e) {
                if (e instanceof CancellationException cancellation) throw cancellation;
                job.fail(e);
                LOGGER.error("[LocateUnbound] Unexpected error while locating biome", e);
                executeOnServerThread(level, () ->
                        source.sendFailure(Component.literal("Locate Unbound error (biome): " + e.getMessage())));
            }
        });
    }

    public static void locatePoiAsync(CommandSourceStack source, ResourceOrTagArgument.Result<PoiType> poiType, BlockPos origin, ServerLevel level) {
        locatePoiAsync(source, poiType, origin, level, false);
    }

    public static void locatePoiAsync(CommandSourceStack source, ResourceOrTagArgument.Result<PoiType> poiType, BlockPos origin, ServerLevel level, boolean teleportRequested) {
        final LocateSettings settings = SETTINGS;
        int configuredPoiRadius = settings.poiSearchRadius();
        submitLocateJob(source, LocatorTargetType.POI, poiType.asPrintable(), origin, level,
                configuredPoiRadius, job -> {
            try {
                int poiRadius = configuredPoiRadius;
                job.searching(25, "Searching POIs within " + poiRadius + " blocks");
                LOGGER.info("[LocateUnbound] Scanning for POI within {} blocks", poiRadius);
                executeOnServerThread(level, () -> source.sendSuccess(() ->
                        Component.literal("Searching for " + poiType.asPrintable() + "..."), false));

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
                    if (found.isEmpty() || horizontalDistanceSq(origin, found.get().getSecond())
                            > (long) stage.radius() * stage.radius()) return Optional.empty();
                    foundHolder.set(found.get().getFirst());
                    return Optional.of(createLocatorResult(job, found.get().getSecond(), backend.id(),
                            "poi-manager", true));
                });

                if (result.isPresent()) {
                    LocatorResult located = result.get();
                    BlockPos pos = located.position();
                    Holder<PoiType> holder = foundHolder.get();

                    completeBackendJob(job, level, located);
                    executeOnServerThread(level, () -> {
                        BlockPos teleportTarget = pos;
                        if (holder != null) {
                            LocateResultHelper.sendResult(source, "commands.locate.poi.success", holder, origin, teleportTarget, true);
                            requestTeleportIfNeeded(source, level, teleportTarget, teleportRequested);
                        } else {
                            LocateResultHelper.sendResult(source, "commands.locatefixer.base.success",
                                    located.targetId(), origin, teleportTarget, true);
                        }
                    });
                } else {
                    executeOnServerThread(level, () ->
                            source.sendFailure(Component.literal("❌ POI not found within " + poiRadius + " blocks."))
                    );
                    failJobNotFound(job, "POI not found within " + poiRadius + " blocks.");
                }

            } catch (Exception e) {
                if (e instanceof CancellationException cancellation) throw cancellation;
                job.fail(e);
                LOGGER.error("[LocateUnbound] Unexpected error while locating POI", e);
                executeOnServerThread(level, () ->
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
                        level.registryAccess().registryOrThrow(Registries.STRUCTURE).holders()
                                .filter(holder -> !level.getChunkSource().getGeneratorState().getPlacementsForStructure(holder).isEmpty())
                                .toList());
                if (allStructures.isEmpty()) {
                    executeOnServerThread(level, () -> source.sendFailure(Component.literal("❌ No structures are registered in this dimension.")));
                    return;
                }

                // Keep the closest vanilla result for each registered structure type.
                Map<String, LocatedEntry> bestByType = new LinkedHashMap<>();

                int[] plannedRings = plannedRings(job, settings);
                int ringIndex = 0;
                for (int ring : plannedRings) {
                    job.cancellationToken().throwIfCancelled();
                    ringIndex++;
                    job.searching(Math.max(2, ringIndex * 95 / plannedRings.length),
                            "Sampling structure candidates within " + ring + " blocks");
                    job.incrementCounter("search_stages");
                    final int ringFinal = ring;
                    executeOnServerThread(level, () -> source.sendSuccess(() ->
                            Component.literal("🔍 Scanning for nearest " + count + " structures up to " + ringFinal + " blocks..."), false));

                    for (Holder<Structure> target : allStructures) {
                        job.cancellationToken().throwIfCancelled();
                        String targetName = target.unwrapKey().map(key -> key.location().toString()).orElse("unknown");
                        if (bestByType.containsKey(targetName)) continue;
                        job.incrementCounter("candidates_sampled");
                        job.incrementCounter("backend_calls");
                        HolderSet<Structure> holders = HolderSet.direct(target);
                        Pair<BlockPos, Holder<Structure>> result = callOnServerThread(level, () ->
                                level.getChunkSource().getGenerator().findNearestMapStructure(level, holders, origin,
                                        structureSearchRings(ring, structurePlacementStep(level, holders)), false));
                        if (result == null || horizontalDistanceSq(origin, result.getFirst()) > (long) ring * ring) continue;
                        BlockPos pos = result.getFirst();
                        String name = holderName(result.getSecond());
                        bestByType.put(name, new LocatedEntry(pos, name, horizontalDistance(origin, pos)));
                    }

                    if (bestByType.size() >= count) break;
                }

                List<LocatedEntry> nearest = bestByType.values().stream()
                        .sorted(Comparator.comparingInt(LocatedEntry::distance))
                        .limit(count)
                        .toList();

                executeOnServerThread(level, () -> {
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
                    completeJob(job, level, nearest.get(0).pos(), "world-generator", false);
                }
            } catch (Exception e) {
                if (e instanceof CancellationException cancellation) throw cancellation;
                job.fail(e);
                LOGGER.error("[LocateUnbound] Unexpected error while locating nearest structures", e);
                executeOnServerThread(level, () -> source.sendFailure(Component.literal("Locate Unbound error (structure multi): " + e.getMessage())));
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
                    executeOnServerThread(level, () -> source.sendSuccess(() ->
                            Component.literal("🔍 Sampling for nearest " + count + " biomes up to " + ringFinal + " blocks..."), false));

                    for (Holder<Biome> targetBiome : allBiomes) {
                        job.cancellationToken().throwIfCancelled();
                        job.incrementCounter("candidates_sampled");
                        job.incrementCounter("backend_calls");
                        String biomeName = holderName(targetBiome);
                        if (bestByBiome.containsKey(biomeName)) continue; // already found one closer

                        Pair<BlockPos, Holder<Biome>> result = callOnServerThread(level, () ->
                                level.findClosestBiome3d(h -> targetBiome.unwrapKey().map(h::is).orElse(false), origin, ring, sampleRadius, step));
                        if (result == null || horizontalDistanceSq(origin, result.getFirst()) > (long) ring * ring) continue;

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

                executeOnServerThread(level, () -> {
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
                    completeJob(job, level, nearest.get(0).pos(), "world-generator", false);
                }
            } catch (Exception e) {
                if (e instanceof CancellationException cancellation) throw cancellation;
                job.fail(e);
                LOGGER.error("[LocateUnbound] Unexpected error while locating nearest biomes", e);
                executeOnServerThread(level, () -> source.sendFailure(Component.literal("Locate Unbound error (biome multi): " + e.getMessage())));
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
        CacheScope submittedScope = CacheScope.of(level, null);
        LocateJobSubmissions.submit(source, targetType, targetId, origin, level, maxRadius, job -> {
            JOB_SCOPES.put(job.request().jobId(), submittedScope);
            try {
                jobScope(job);
                task.run(job);
            } finally { JOB_SCOPES.remove(job.request().jobId()); }
        }, job -> LocateJobSubmissions.notifyTimeout(source, level, job,
                () -> submittedScope.epoch() == CACHE_EPOCH.get()));
    }

    private static int[] plannedRings(LocateJob job, LocateSettings settings) {
        SearchPlan plan = plannedSearchPlan(job, settings);
        return plan.stages().stream().mapToInt(SearchStage::radius).toArray();
    }

    private static SearchPlan plannedSearchPlan(LocateJob job, LocateSettings settings) {
        SearchPlan plan;
        if (com.thunder.locatefixer.config.LocateFixerConfig.SERVER.adaptiveSearchEnabled.get()) {
            plan = LocateRuntime.planner().plan(job.request(), settings.rings(),
                    LocateRuntime.searchHistory().contextFor(job.request(), jobScope(job)),
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
        return createLocatorResult(job, position, backendId, discoverySource, true, verified);
    }

    private static LocatorResult createLocatorResult(LocateJob job, BlockPos position, String backendId,
                                                       String discoverySource, boolean generated, boolean verified) {
        int distance = horizontalDistance(job.request().origin(), position);
        return new LocatorResult(job.request().targetType(), job.request().targetId(),
                job.request().dimensionId(), position, backendId, discoverySource,
                Instant.now(), generated, verified, Map.of("distance", Integer.toString(distance)));
    }

    private static void completeBackendJob(LocateJob job, ServerLevel level, LocatorResult result) throws Exception {
        job.cancellationToken().throwIfCancelled();
        if (result.targetType() != job.request().targetType()
                || !result.targetId().equals(job.request().targetId())
                || !result.dimensionId().equals(job.request().dimensionId())
                || horizontalDistanceSq(job.request().origin(), result.position())
                        > (long) job.request().maxRadius() * job.request().maxRadius()) {
            throw new IllegalArgumentException("Backend returned an out-of-scope locate result");
        }
        int distance = horizontalDistance(job.request().origin(), result.position());
        job.selectBackend(result.backendId());
        job.attribute("result_distance", Integer.toString(distance));
        LocateRuntime.searchHistory().recordSuccess(job.request(), distance, 50, jobScope(job));
        if (result.verified() && !"memory-cache".equals(result.discoverySource())
                && com.thunder.locatefixer.config.LocateFixerConfig.SERVER.persistentIndexEnabled.get()) {
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
            Optional<LocatorResult> result = callOnServerThread(level, locate);
            job.cancellationToken().throwIfCancelled();
            if (result.isPresent() && horizontalDistanceSq(origin, result.get().position()) > (long) radius * radius) {
                throw new IllegalArgumentException("Provider returned a result outside its search radius");
            }
            return result;
        } catch (CancellationException cancellation) {
            throw cancellation;
        } catch (Exception failure) {
            throw new CompletionException(failure);
        }
    }

    private static void completeProviderJob(LocateJob job, ServerLevel level,
                                            LocatorProvider provider, LocatorResult result) throws Exception {
        job.cancellationToken().throwIfCancelled();
        if (!result.dimensionId().equals(job.request().dimensionId())
                || result.targetType() != job.request().targetType()
                || !result.targetId().equals(job.request().targetId())
                || horizontalDistanceSq(job.request().origin(), result.position())
                        > (long) job.request().maxRadius() * job.request().maxRadius()) {
            throw new IllegalArgumentException("Provider returned an out-of-scope locate result");
        }
        int distance = horizontalDistance(job.request().origin(), result.position());
        job.selectBackend(result.backendId());
        job.attribute("result_distance", Integer.toString(distance));
        LocateRuntime.searchHistory().recordSuccess(job.request(), distance, provider.estimatedSearchCost(), jobScope(job));
        if (provider.cachePolicy().allowsMemoryCache()) {
            synchronized (AsyncLocateHandler.class) {
                job.cancellationToken().throwIfCancelled();
                CacheScope scope = providerScope(job, provider);
                LocateRuntime.providerCache().put(job.request(), result,
                        com.thunder.locatefixer.config.LocateFixerConfig.SERVER.cacheChunkGranularity.get(),
                        com.thunder.locatefixer.config.LocateFixerConfig.SERVER.cacheMaxEntries.get(), scope);
            }
        }
        if (result.verified()
                && com.thunder.locatefixer.config.LocateFixerConfig.SERVER.persistentIndexEnabled.get()
                && provider.cachePolicy().allowsPersistentIndex()) {
            callOnServerThread(level, () -> {
                WorldLocatorIndex.get(level).record(result);
                return null;
            });
        }
        job.found(result);
    }

    private static void completeProviderMemoryJob(LocateJob job, LocatorProvider provider, LocatorResult result) {
        int distance = horizontalDistance(job.request().origin(), result.position());
        job.selectBackend("locatefixer:memory-cache");
        job.attribute("result_distance", Integer.toString(distance));
        LocateRuntime.searchHistory().recordSuccess(job.request(), distance, provider.estimatedSearchCost(), jobScope(job));
        job.found(result);
    }

    private static void failJobNotFound(LocateJob job, String message) {
        LocateRuntime.searchHistory().recordFailure(job.request(), job.request().maxRadius(), 50, jobScope(job));
        job.notFound(message);
    }

    private static <T> LocateCacheEntry<T> getValidCacheEntry(ConcurrentMap<LocateCacheKey, LocateCacheEntry<T>> cache,
                                                               LocateCacheKey key, long cacheDurationMs) {
        LocateCacheEntry<T> entry = cache.get(key);
        if (entry == null) return null;
        if (cacheDurationMs <= 0L || System.nanoTime() - entry.timestamp() > TimeUnit.MILLISECONDS.toNanos(cacheDurationMs)) {
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
    private static synchronized <T> void putWithEviction(ConcurrentMap<LocateCacheKey, LocateCacheEntry<T>> cache,
                                            LocateCacheKey key, LocateCacheEntry<T> entry) {
        if (key.epoch() != CACHE_EPOCH.get()) return;
        LocateJobManager.currentJob().ifPresent(job -> {
            jobScope(job);
            job.cancellationToken().throwIfCancelled();
        });
        int maxEntries = com.thunder.locatefixer.config.LocateFixerConfig.SERVER.cacheMaxEntries.get();
        if (!cache.containsKey(key) && cache.size() >= maxEntries) {
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

    private static BiomeSourceStatus inspectBiomeSource(ServerLevel level,
                                                        ResourceOrTagArgument.Result<Biome> target) throws Exception {
        return callOnServerThread(level, () -> {
            BiomeSource biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
            Set<Holder<Biome>> possibleBiomes = biomeSource.possibleBiomes();
            return new BiomeSourceStatus(
                    possibleBiomes.stream().anyMatch(target),
                    biomeSource.getClass().getName(),
                    possibleBiomes.size()
            );
        });
    }

    private static int computeSampleRadius(int searchRadius, LocateSettings settings) {
        return computeSampleRadius(searchRadius, settings.biomeSampleRadiusMultiplier());
    }

    private static int computeSampleRadius(int searchRadius, double multiplier) {
        return 32;
    }

    private static int computeSampleStep(int searchRadius, LocateSettings settings) {
        return computeSampleStep(searchRadius, settings.biomeSampleStepMultiplier());
    }

    private static int computeSampleStep(int searchRadius, double multiplier) {
        return 64;
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
        Optional<LocateJob> current = LocateJobManager.currentJob();
        com.thunder.locatefixer.search.LocateCancellationToken token = current
                .map(LocateJob::cancellationToken).orElseGet(() -> {
                    var bounded = new com.thunder.locatefixer.search.LocateCancellationToken();
                    bounded.setTimeoutSeconds(com.thunder.locatefixer.config.LocateFixerConfig.SERVER.searchTimeoutSeconds.get());
                    return bounded;
                });
        return com.thunder.locatefixer.search.ServerThreadCall.call(level.getServer(),
                level.getServer()::isSameThread, () -> {
                    current.ifPresent(AsyncLocateHandler::jobScope);
                    return task.call();
                }, token,
                (waitNanos, callNanos) -> current.ifPresent(job -> {
                    job.recordServerCall(waitNanos, callNanos);
                    if (com.thunder.locatefixer.config.LocateFixerConfig.SERVER.benchmarkEnabled.get()) {
                        LocateRuntime.jobs().trackTickImpact(job);
                    }
                }));
    }

    private static void executeOnServerThread(ServerLevel level, Runnable task) {
        Optional<LocateJob> job = LocateJobManager.currentJob();
        long epoch = job.map(active -> JOB_SCOPES.get(active.request().jobId()))
                .map(CacheScope::epoch).orElse(CACHE_EPOCH.get());
        level.getServer().execute(() -> {
            if (epoch != CACHE_EPOCH.get() || job.map(active -> !active.canPublish()).orElse(false)) return;
            task.run();
        });
    }

    private static SearchPlan withIndexedCandidate(SearchPlan plan, Optional<LocatorResult> indexed, BlockPos origin) {
        if (indexed.isEmpty()) return plan;
        int radius = Math.max(1, (int) Math.ceil(Math.sqrt(horizontalDistanceSq(origin, indexed.get().position()))));
        return plan.withCandidateRadius(radius);
    }

    private static int structurePlacementStep(ServerLevel level, HolderSet<Structure> holders) {
        int minimumSpacing = Integer.MAX_VALUE;
        for (Holder<Structure> holder : holders) {
            for (var placement : level.getChunkSource().getGeneratorState().getPlacementsForStructure(holder)) {
                if (placement instanceof RandomSpreadStructurePlacement random) {
                    minimumSpacing = Math.min(minimumSpacing, Math.max(1, random.spacing()));
                }
            }
        }
        return minimumSpacing == Integer.MAX_VALUE ? 16 : minimumSpacing * 16;
    }

    private static int structureSearchRings(int blockRadius, int placementStep) {
        return (int) Math.min(Integer.MAX_VALUE, ((long) blockRadius + placementStep - 1L) / placementStep + 1L);
    }

    private static void requestTeleportIfNeeded(CommandSourceStack source, ServerLevel level,
                                                BlockPos position, boolean requested) {
        if (requested) com.thunder.locatefixer.teleport.LocateTeleportHandler.requestTeleport(source, level, position);
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
        return com.thunder.locatefixer.search.LocateDistances.horizontalSquared(origin, target);
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
        return holder.unwrapKey().map(key -> key.location().toString()).orElse("unknown");
    }

    private static void sendRingProgressUpdate(ServerLevel level, CommandSourceStack source, String kind,
                                               int scanRadius, int step, int totalSteps) {
        LocateJob job = LocateJobManager.currentJob().orElse(null);
        if (job == null || !job.shouldReportProgress()) return;
        executeOnServerThread(level, () -> {
            if (job.snapshot().status().isTerminal()) return;
            source.sendSuccess(() -> Component.literal("Still searching for " + job.request().targetId() + "..."), false);
        });
    }

    private static void sendLocateStartUpdate(ServerLevel level, CommandSourceStack source, String kind,
                                              String query, int ringCount, int maxRadius) {
        executeOnServerThread(level, () -> source.sendSuccess(() ->
                Component.literal("Searching for " + query + "..."), false));
    }

    public static void reloadConfig() {
        LocateSettings newSettings = loadSettings();
        SETTINGS = newSettings;
        clearCaches();
        LOGGER.info("[LocateUnbound] Reloaded locate settings: {} rings, {}ms cache, {} thread(s).",
                newSettings.rings().length, newSettings.cacheDurationMs(), newSettings.threadCount());
    }

    public static synchronized void clearCaches() {
        CACHE_EPOCH.incrementAndGet();
        STRUCTURE_CACHE.clear();
        BIOME_CACHE.clear();
        LocateRuntime.providerCache().clear();
    }

    public static int cacheEntryCount() {
        return STRUCTURE_CACHE.size() + BIOME_CACHE.size() + LocateRuntime.providerCache().size();
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
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32), THREAD_FACTORY, new ThreadPoolExecutor.AbortPolicy());
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

    private static Object jobScope(LocateJob job) {
        CacheScope scope = JOB_SCOPES.get(job.request().jobId());
        if (scope == null) throw new CancellationException("Locate job scope expired");
        if (scope.epoch() != CACHE_EPOCH.get()) throw new CancellationException("Locate world data reloaded");
        return scope;
    }

    private static CacheScope providerScope(LocateJob job, LocatorProvider provider) {
        CacheScope scope = (CacheScope) jobScope(job);
        return new CacheScope(scope.world(), scope.registries(), provider, scope.epoch());
    }

    private record CacheScope(ServerLevel world, Object registries, LocatorProvider provider, long epoch) {
        static CacheScope of(ServerLevel level, LocatorProvider provider) {
            return new CacheScope(level, level.registryAccess(), provider, CACHE_EPOCH.get());
        }
    }

    private record LocateCacheKey(long epoch, ServerLevel world, Object registries,
                                  String target, BlockPos origin, int maxRadius) {
        private static LocateCacheKey of(ServerLevel level, String target, BlockPos origin, int maxRadius) {
            return new LocateCacheKey(CACHE_EPOCH.get(), level, level.registryAccess(), target, origin.immutable(), maxRadius);
        }
    }

    private record LocateCacheEntry<T>(BlockPos pos, Holder<T> holder, long timestamp) {}

    private record LocatedEntry(BlockPos pos, String name, int distance) {}

    private record BiomeSourceStatus(boolean targetAvailable, String sourceType, int possibleBiomeCount) {}

    private record LocateSettings(int[] rings, int poiSearchRadius, long cacheDurationMs,
                                  int cacheGranularity, int threadCount,
                                  double biomeSampleRadiusMultiplier, double biomeSampleStepMultiplier) {
        int maxRadius() {
            return rings.length == 0 ? 0 : rings[rings.length - 1];
        }
    }
}
