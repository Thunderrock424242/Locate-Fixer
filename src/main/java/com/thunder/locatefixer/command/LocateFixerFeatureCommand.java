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
            level.getServer().execute(() -> result.ifPresentOrElse(pos -> {
                BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos);
                LocateResultHelper.sendResult(
                        source, "commands.locatefixer.base.success", featureId.toString(), origin, surface, true);
            }, () -> source.sendFailure(Component.literal(
                    "❌ Feature '" + featureId + "' not found within "
                            + searchRings[searchRings.length - 1] + " blocks."))));
        });

        return 1;
    }

    private static Optional<BlockPos> findNearestFeatureBiome(
            ServerLevel level,
            BlockPos origin,
            ResourceKey<PlacedFeature> featureKey,
            int[] searchRings
    ) {
        for (int radius : sortedRings(searchRings)) {
            int horizontalInterval = Math.max(32, Math.min(256, radius / 256));
            var match = level.findClosestBiome3d(
                    biome -> biomeContainsFeature(biome, featureKey),
                    origin,
                    radius,
                    horizontalInterval,
                    64
            );
            if (match != null) {
                return Optional.of(match.getFirst());
            }
        }

        return Optional.empty();
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

}
