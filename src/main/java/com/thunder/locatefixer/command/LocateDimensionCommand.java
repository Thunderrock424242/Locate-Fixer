package com.thunder.locatefixer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.datafixers.util.Pair;
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
import com.thunder.locatefixer.teleport.LocateTeleportHandler;
import com.thunder.locatefixer.util.AsyncLocateHandler;

import java.util.List;

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
                                        .suggests((ctx, builder) -> {
                                            Registry<Biome> biomes = ctx.getSource().getLevel().registryAccess()
                                                    .registryOrThrow(Registries.BIOME);
                                            return SharedSuggestionProvider.suggestResource(biomes.keySet(), builder);
                                        })
                                        .executes(ctx -> execute(
                                                ctx.getSource(),
                                                DimensionArgument.getDimension(ctx, "dimension"),
                                                ResourceLocationArgument.getId(ctx, "biome")
                                        ))))));
    }

    private static int execute(CommandSourceStack source, ServerLevel targetLevel) throws CommandSyntaxException {
        return execute(source, targetLevel, null);
    }

    private static int execute(CommandSourceStack source, ServerLevel targetLevel, ResourceLocation biomeId) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        source.sendSuccess(() -> Component.literal("🔍 Locating destination biome asynchronously..."), false);

        AsyncLocateHandler.runAsyncTask("locate-dimension", () -> {
            RandomSource random = RandomSource.create();

            List<Holder<Biome>> possibleBiomes = targetLevel.getChunkSource()
                    .getGenerator()
                    .getBiomeSource()
                    .possibleBiomes()
                    .stream()
                    .toList();

            if (possibleBiomes.isEmpty()) {
                targetLevel.getServer().execute(() -> source.sendFailure(Component.literal("❌ No biomes were found for that dimension.")));
                return;
            }

            Pair<BlockPos, Holder<Biome>> selectedBiome = null;
            for (int attempt = 0; attempt < MAX_RANDOM_ATTEMPTS && selectedBiome == null; attempt++) {
                int progress = Math.max(1, (int) Math.round(((attempt + 1) * 100.0D) / MAX_RANDOM_ATTEMPTS));
                int searchAttempt = attempt + 1;
                targetLevel.getServer().execute(() -> source.sendSuccess(() ->
                        Component.literal("🔍 Locating biome... attempt " + searchAttempt + "/" + MAX_RANDOM_ATTEMPTS + " (" + progress + "%)"), false));

                BlockPos randomOrigin = new BlockPos(
                        random.nextIntBetweenInclusive(-RANDOM_COORD_RANGE, RANDOM_COORD_RANGE),
                        targetLevel.getSeaLevel(),
                        random.nextIntBetweenInclusive(-RANDOM_COORD_RANGE, RANDOM_COORD_RANGE)
                );

                if (biomeId != null) {
                    ResourceKey<Biome> biomeKey = ResourceKey.create(Registries.BIOME, biomeId);
                    selectedBiome = targetLevel.findClosestBiome3d(holder -> holder.is(biomeKey), randomOrigin,
                            BIOME_SEARCH_RADIUS, BIOME_HORIZONTAL_STEP, BIOME_VERTICAL_STEP);
                } else {
                    Holder<Biome> biome = possibleBiomes.get(random.nextInt(possibleBiomes.size()));
                    selectedBiome = targetLevel.findClosestBiome3d(holder -> holder.is(biome), randomOrigin,
                            BIOME_SEARCH_RADIUS, BIOME_HORIZONTAL_STEP, BIOME_VERTICAL_STEP);
                }
            }

            if (selectedBiome == null) {
                String biomeLabel = biomeId == null ? "a random biome" : "biome " + biomeId;
                targetLevel.getServer().execute(() -> source.sendFailure(Component.literal("❌ Could not find " + biomeLabel + " in that dimension.")));
                return;
            }

            BlockPos biomePos = selectedBiome.getFirst();
            Holder<Biome> biome = selectedBiome.getSecond();
            BlockPos surfaceOrigin = findSurfaceAnchor(targetLevel, biomePos);
            BlockPos safeTarget = LocateTeleportHandler.findSurfaceSafeTeleportPosition(targetLevel, surfaceOrigin);

            targetLevel.getServer().execute(() -> {
                if (player.isRemoved()) {
                    return;
                }

                String biomeName = biome.unwrapKey()
                        .map(key -> key.location().toString())
                        .orElse("unknown");
                String destinationLabel = biomeId == null ? "random biome " + biomeName : "biome " + biomeName;

                source.sendSuccess(() -> Component.literal("📦 Destination found. Preloading chunks for safe teleport..."), false);
                LocateTeleportHandler.startTeleportWithPreload(player, targetLevel, safeTarget, finalPos -> {
                    player.teleportTo(targetLevel, finalPos.getX() + 0.5D, finalPos.getY(), finalPos.getZ() + 0.5D,
                            player.getYRot(), player.getXRot());
                    source.sendSuccess(() -> Component.literal("✅ Teleported to " + destinationLabel +
                            " at " + finalPos.getX() + " " + finalPos.getY() + " " + finalPos.getZ()), true);
                });
            });
        });

        return 1;
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
}
