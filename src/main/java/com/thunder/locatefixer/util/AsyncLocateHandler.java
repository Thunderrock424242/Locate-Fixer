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

import static com.thunder.locatefixer.locatefixer.LOGGER;

public class AsyncLocateHandler {

    private static final int[] RINGS = {6400, 16000, 32000, 64000, 128000, 256000};
    private static final int POI_SEARCH_RADIUS = 256;

    public static void locateStructureAsync(CommandSourceStack source, ResourceOrTagKeyArgument.Result<Structure> structure, BlockPos origin, ServerLevel level) {
        CompletableFuture.runAsync(() -> {
            try {
                Registry<Structure> registry = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
                HolderSet<Structure> holders = LocateCommandInvoker.invokeGetHolders(structure, registry)
                        .orElseThrow(() -> LocateCommandAccessor.getStructureInvalid().create(structure.asPrintable()));

                for (int radius : RINGS) {
                    int scanRadius = radius;
                    level.getServer().execute(() -> source.sendSuccess(() ->
                            Component.literal("🔍 Scanning up to " + scanRadius + " blocks..."), false));
                    LOGGER.info("[LocateFixer] Scanning for structure up to {} blocks", scanRadius);

                    Pair<BlockPos, Holder<Structure>> result = level.getChunkSource().getGenerator()
                            .findNearestMapStructure(level, holders, origin, radius, false);

                    if (result != null) {
                        BlockPos pos = result.getFirst();
                        Holder<Structure> holder = result.getSecond();

                        level.getServer().execute(() -> {
                            LocateResultHelper.sendResult(source, "commands.locate.structure.success", holder, origin, pos, false);
                            LocateResultHelper.startTeleportCountdown(source, level, pos, false);
                        });
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
        });
    }

    public static void locateBiomeAsync(CommandSourceStack source, ResourceOrTagArgument.Result<Biome> biome, BlockPos origin, ServerLevel level) {
        CompletableFuture.runAsync(() -> {
            try {
                for (int radius : RINGS) {
                    int scanRadius = radius;
                    level.getServer().execute(() -> source.sendSuccess(() ->
                            Component.literal("🔍 Scanning up to " + scanRadius + " blocks..."), false));
                    LOGGER.info("[LocateFixer] Scanning for biome up to {} blocks", scanRadius);

                    Pair<BlockPos, Holder<Biome>> result = level.findClosestBiome3d(biome, origin, radius, 32, 64);
                    if (result != null) {
                        BlockPos pos = result.getFirst();
                        Holder<Biome> holder = result.getSecond();

                        level.getServer().execute(() -> {
                            LocateResultHelper.sendResult(source, "commands.locate.biome.success", holder, origin, pos, true);
                            LocateResultHelper.startTeleportCountdown(source, level, pos, true);
                        });
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
        });
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

                    level.getServer().execute(() -> {
                        LocateResultHelper.sendResult(source, "commands.locate.poi.success", holder, origin, pos, false);
                        LocateResultHelper.startTeleportCountdown(source, level, pos, false);
                    });
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
        });
    }
}