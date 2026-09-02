package com.thunder.locatefixer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.datafixers.util.Pair;
import com.thunder.locatefixer.LocateFixerMod;
import com.thunder.locatefixer.api.LocatorResult;
import com.thunder.locatefixer.api.LocatorTargetType;
import com.thunder.locatefixer.job.LocateJobSubmissions;
import com.thunder.locatefixer.teleport.LocateTeleportHandler;
import com.thunder.locatefixer.util.AsyncLocateHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;

import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

public final class LocateDimensionCommand {
    private static final int RANDOM_COORD_RANGE = 25000;
    private static final int BIOME_SEARCH_RADIUS = 6400;
    private static final int BIOME_HORIZONTAL_STEP = 32;
    private static final int BIOME_VERTICAL_STEP = 64;
    private static final int MAX_RANDOM_ATTEMPTS = 12;

    private LocateDimensionCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("locate")
                .then(Commands.literal("dimension")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> execute(ctx.getSource(), ctx.getSource().getLevel()))
                        .then(Commands.argument("dimension", DimensionArgument.dimension())
                                .executes(ctx -> execute(ctx.getSource(), DimensionArgument.getDimension(ctx, "dimension")))
                                .then(Commands.argument("biome", ResourceLocationArgument.id())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggestResource(
                                                getBiomeSuggestionsForDimension(DimensionArgument.getDimension(ctx, "dimension")),
                                                builder
                                        ))
                                        .executes(ctx -> execute(
                                                ctx.getSource(),
                                                DimensionArgument.getDimension(ctx, "dimension"),
                                                ResourceLocationArgument.getId(ctx, "biome")
                                        ))))));
    }

    private static Set<ResourceLocation> getBiomeSuggestionsForDimension(ServerLevel targetLevel) {
        Registry<Biome> biomeRegistry = targetLevel.registryAccess().registryOrThrow(Registries.BIOME);
        return targetLevel.getChunkSource()
                .getGenerator()
                .getBiomeSource()
                .possibleBiomes()
                .stream()
                .map(holder -> holder.unwrapKey().map(ResourceKey::location).orElse(null))
                .filter(resourceLocation -> resourceLocation != null && biomeRegistry.containsKey(resourceLocation))
                .collect(Collectors.toSet());
    }

    private static int execute(CommandSourceStack source, ServerLevel targetLevel) throws CommandSyntaxException {
        return execute(source, targetLevel, null);
    }

    private static int execute(CommandSourceStack source, ServerLevel targetLevel, ResourceLocation biomeId) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        BlockPos requestOrigin = BlockPos.containing(source.getPosition());
        String requestTarget = biomeId == null ? "random" : biomeId.toString();
        boolean accepted = LocateJobSubmissions.submit(source, LocatorTargetType.BIOME, requestTarget,
                requestOrigin, targetLevel, RANDOM_COORD_RANGE + BIOME_SEARCH_RADIUS, job -> {
            try {
                RandomSource random = RandomSource.create();
                DimensionBiomeContext biomeContext = AsyncLocateHandler.callOnServerThread(targetLevel, () ->
                        new DimensionBiomeContext(targetLevel.getChunkSource().getGenerator().getBiomeSource()
                                .possibleBiomes().stream().toList(), targetLevel.getSeaLevel()));
                job.cancellationToken().throwIfCancelled();

                if (biomeContext.possibleBiomes().isEmpty()) {
                    String message = "No biomes were found for that dimension.";
                    targetLevel.getServer().execute(() -> source.sendFailure(Component.literal("❌ " + message)));
                    job.notFound(message);
                    return;
                }

                Pair<BlockPos, Holder<Biome>> selectedBiome = null;
                int attempts = 0;
                for (int attempt = 0; attempt < MAX_RANDOM_ATTEMPTS && selectedBiome == null; attempt++) {
                    job.cancellationToken().throwIfCancelled();
                    attempts = attempt + 1;
                    int progress = Math.max(1, (int) Math.round((attempts * 95.0D) / MAX_RANDOM_ATTEMPTS));
                    int searchAttempt = attempts;
                    job.searching(progress, "Locating destination biome, attempt " + searchAttempt
                            + "/" + MAX_RANDOM_ATTEMPTS);
                    targetLevel.getServer().execute(() -> source.sendSuccess(() -> Component.literal(
                            "🔍 Locating biome... attempt " + searchAttempt + "/" + MAX_RANDOM_ATTEMPTS
                                    + " (" + progress + "%)"), false));

                    BlockPos randomOrigin = new BlockPos(
                            random.nextIntBetweenInclusive(-RANDOM_COORD_RANGE, RANDOM_COORD_RANGE),
                            biomeContext.seaLevel(),
                            random.nextIntBetweenInclusive(-RANDOM_COORD_RANGE, RANDOM_COORD_RANGE));
                    Holder<Biome> randomBiome = biomeId == null
                            ? biomeContext.possibleBiomes().get(random.nextInt(biomeContext.possibleBiomes().size()))
                            : null;
                    selectedBiome = AsyncLocateHandler.callOnServerThread(targetLevel, () -> {
                        if (biomeId != null) {
                            ResourceKey<Biome> biomeKey = ResourceKey.create(Registries.BIOME, biomeId);
                            return targetLevel.findClosestBiome3d(holder -> holder.is(biomeKey), randomOrigin,
                                    BIOME_SEARCH_RADIUS, BIOME_HORIZONTAL_STEP, BIOME_VERTICAL_STEP);
                        }
                        return targetLevel.findClosestBiome3d(holder -> holder.is(randomBiome), randomOrigin,
                                BIOME_SEARCH_RADIUS, BIOME_HORIZONTAL_STEP, BIOME_VERTICAL_STEP);
                    });
                    job.cancellationToken().throwIfCancelled();
                }

                if (selectedBiome == null) {
                    String biomeLabel = biomeId == null ? "a random biome" : "biome " + biomeId;
                    String message = "Could not find " + biomeLabel + " in that dimension.";
                    targetLevel.getServer().execute(() -> source.sendFailure(Component.literal("❌ " + message)));
                    job.notFound(message);
                    return;
                }

                BlockPos biomePos = selectedBiome.getFirst();
                Holder<Biome> biome = selectedBiome.getSecond();
                int completedAttempts = attempts;
                AsyncLocateHandler.callOnServerThread(targetLevel, () -> {
                    job.cancellationToken().throwIfCancelled();
                    if (player.isRemoved()) {
                        throw new CancellationException("Player disconnected before dimension travel began");
                    }
                    BlockPos surfaceOrigin = findSurfaceAnchor(targetLevel, biomePos);
                    BlockPos safeTarget = LocateTeleportHandler.findSurfaceSafeTeleportPosition(targetLevel, surfaceOrigin);
                    String biomeName = biome.unwrapKey().map(key -> key.location().toString()).orElse("unknown");
                    String destinationLabel = biomeId == null ? "random biome " + biomeName : "biome " + biomeName;

                    source.sendSuccess(() -> Component.literal("📦 Destination found. Preloading chunks for safe teleport..."), false);
                    LocateTeleportHandler.startTeleportWithPreload(player, targetLevel, safeTarget, finalPos -> {
                        player.teleportTo(targetLevel, finalPos.getX() + 0.5D, finalPos.getY(), finalPos.getZ() + 0.5D,
                                player.getYRot(), player.getXRot());
                        source.sendSuccess(() -> Component.literal("✅ Teleported to " + destinationLabel
                                + " at " + finalPos.getX() + " " + finalPos.getY() + " " + finalPos.getZ()), true);
                    });
                    return null;
                });
                job.attribute("attempts", Integer.toString(completedAttempts));
                job.attribute("destination_dimension", targetLevel.dimension().location().toString());
                job.found(new LocatorResult(LocatorTargetType.BIOME, requestTarget,
                        targetLevel.dimension().location().toString(), biomePos,
                        "locatefixer:vanilla", "dimension-biome-search", Instant.now(),
                        false, true, Map.of("attempts", Integer.toString(completedAttempts))));
            } catch (CancellationException cancellation) {
                throw cancellation;
            } catch (Exception failure) {
                job.fail(failure);
                LocateFixerMod.LOGGER.error("[LocateUnbound] Dimension biome search failed", failure);
                targetLevel.getServer().execute(() -> source.sendFailure(Component.literal(
                        "Locate Unbound error (dimension): " + failure.getMessage())));
            }
        });

        if (accepted) {
            source.sendSuccess(() -> Component.literal("🔍 Destination biome search queued."), false);
        }
        return accepted ? 1 : 0;
    }

    private static BlockPos findSurfaceAnchor(ServerLevel level, BlockPos biomePos) {
        int worldSurfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, biomePos.getX(), biomePos.getZ());
        int motionBlockingY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, biomePos.getX(), biomePos.getZ());
        int anchorY = Math.max(worldSurfaceY, motionBlockingY) + 1;
        int minSafeY = level.getMinBuildHeight() + 4;
        int maxSafeY = level.getMaxBuildHeight() - 3;
        int clampedY = Math.max(minSafeY, Math.min(maxSafeY, anchorY));
        return new BlockPos(biomePos.getX(), clampedY, biomePos.getZ());
    }

    private record DimensionBiomeContext(List<Holder<Biome>> possibleBiomes, int seaLevel) {
    }
}
