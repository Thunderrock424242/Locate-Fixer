package com.thunder.locatefixer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.thunder.locatefixer.config.LocateFixerConfig;
import com.thunder.locatefixer.util.AsyncLocateHandler;
import com.thunder.locatefixer.util.LocateResultHelper;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class LocateFixerFeatureCommand {

    private LocateFixerFeatureCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("locate")
                .then(Commands.literal("feature")
                        .requires(source -> {
                            try {
                                return source.hasPermission(2) &&
                                        LocateFixerConfig.SERVER.enableFeatureLocateCommand.get();
                            } catch (IllegalStateException e) {
                                return false;
                            }
                        })
                        .then(Commands.argument("feature", ResourceLocationArgument.id())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                                        context.getSource().getLevel()
                                                .registryAccess()
                                                .registryOrThrow(Registries.PLACED_FEATURE)
                                                .keySet()
                                                .stream(),
                                        builder
                                ))
                                .executes(context -> locateFeature(
                                        context.getSource(),
                                        ResourceLocationArgument.getId(context, "feature")
                                )))));
    }

    private static int locateFeature(CommandSourceStack source, ResourceLocation featureId) {

        ServerLevel level = source.getLevel();
        Registry<PlacedFeature> registry = level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE);
        ResourceKey<PlacedFeature> featureKey = ResourceKey.create(Registries.PLACED_FEATURE, featureId);
        Optional<Holder.Reference<PlacedFeature>> feature = registry.getHolder(featureKey);
        if (feature.isEmpty()) {
            source.sendFailure(Component.literal("❌ Unknown placed feature: " + featureId));
            return 0;
        }

        BlockPos origin = BlockPos.containing(source.getPosition());
        source.sendSuccess(() -> Component.literal("🔍 Locating feature '" + featureId + "'..."), false);

        AsyncLocateHandler.runAsyncTask("locate-feature-" + featureId, () -> {
            int[] searchRings = LocateFixerConfig.SERVER.locateRings.get().stream().mapToInt(Integer::intValue).toArray();
            if (searchRings.length == 0) {
                level.getServer().execute(() -> source.sendFailure(Component.literal("❌ No locate search radii configured.")));
                return;
            }

            Optional<BlockPos> result = findNearestFeatureBiome(level, origin, featureKey, searchRings);
            level.getServer().execute(() -> result.ifPresentOrElse(
                    pos -> LocateResultHelper.sendResult(source, "commands.locatefixer.base.success", featureId.toString(), origin, pos, true),
                    () -> source.sendFailure(Component.literal("❌ Feature '" + featureId + "' not found within " + searchRings[searchRings.length - 1] + " blocks."))
            ));
        });

        return 1;
    }

    private static Optional<BlockPos> findNearestFeatureBiome(
            ServerLevel level,
            BlockPos origin,
            ResourceKey<PlacedFeature> featureKey,
            int[] searchRings
    ) {
        int originChunkX = SectionPos.blockToSectionCoord(origin.getX());
        int originChunkZ = SectionPos.blockToSectionCoord(origin.getZ());
        final int chunkStep = 4;

        for (int radius : sortedRings(searchRings)) {
            int chunkRadius = Math.max(1, radius >> 4);
            BlockPos nearest = null;
            long nearestDistanceSq = Long.MAX_VALUE;

            for (int offset = -chunkRadius; offset <= chunkRadius; offset += chunkStep) {
                nearest = pickNearest(level, origin, featureKey, originChunkX + offset, originChunkZ - chunkRadius, nearest, nearestDistanceSq);
                if (nearest != null) nearestDistanceSq = distSqXZ(origin, nearest);
                nearest = pickNearest(level, origin, featureKey, originChunkX + offset, originChunkZ + chunkRadius, nearest, nearestDistanceSq);
                if (nearest != null) nearestDistanceSq = distSqXZ(origin, nearest);
                nearest = pickNearest(level, origin, featureKey, originChunkX - chunkRadius, originChunkZ + offset, nearest, nearestDistanceSq);
                if (nearest != null) nearestDistanceSq = distSqXZ(origin, nearest);
                nearest = pickNearest(level, origin, featureKey, originChunkX + chunkRadius, originChunkZ + offset, nearest, nearestDistanceSq);
                if (nearest != null) nearestDistanceSq = distSqXZ(origin, nearest);
            }

            if (nearest != null) {
                return Optional.of(nearest);
            }
        }

        return Optional.empty();
    }

    private static BlockPos pickNearest(
            ServerLevel level,
            BlockPos origin,
            ResourceKey<PlacedFeature> featureKey,
            int chunkX,
            int chunkZ,
            BlockPos currentNearest,
            long currentNearestDistanceSq
    ) {
        BlockPos candidate = chunkCenter(chunkX, chunkZ);
        if (!biomeContainsFeature(level.getBiome(candidate), featureKey)) {
            return currentNearest;
        }

        BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, candidate);
        long distanceSq = distSqXZ(origin, surface);
        return distanceSq < currentNearestDistanceSq ? surface : currentNearest;
    }

    private static boolean biomeContainsFeature(Holder<Biome> biome, ResourceKey<PlacedFeature> featureKey) {
        List<HolderSet<PlacedFeature>> featuresByStep = biome.value().getGenerationSettings().features();
        for (HolderSet<PlacedFeature> featuresInStep : featuresByStep) {
            for (Holder<PlacedFeature> feature : featuresInStep) {
                if (feature.is(featureKey)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int[] sortedRings(int[] rings) {
        return java.util.Arrays.stream(rings)
                .filter(radius -> radius > 0)
                .boxed()
                .sorted(Comparator.naturalOrder())
                .mapToInt(Integer::intValue)
                .toArray();
    }

    private static BlockPos chunkCenter(int chunkX, int chunkZ) {
        return new BlockPos((chunkX << 4) + 8, 0, (chunkZ << 4) + 8);
    }

    private static long distSqXZ(BlockPos a, BlockPos b) {
        long dx = (long) b.getX() - a.getX();
        long dz = (long) b.getZ() - a.getZ();
        return dx * dx + dz * dz;
    }
}
