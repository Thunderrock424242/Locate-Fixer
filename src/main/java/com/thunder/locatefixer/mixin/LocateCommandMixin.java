package com.thunder.locatefixer.mixin;

import com.mojang.datafixers.util.Pair;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceOrTagArgument;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.commands.LocateCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.time.Duration;

@Mixin(LocateCommand.class)
public class LocateCommandMixin {

    @Inject(method = "locateStructure", at = @At("HEAD"), cancellable = true)
    private static void locatefix$overrideLocateStructure(
            CommandSourceStack source,
            ResourceOrTagKeyArgument.Result<Structure> structure,
            CallbackInfoReturnable<Integer> cir
    ) {
        try {
            var registry = source.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
            HolderSet<Structure> holders = LocateCommandAccessor.invokeGetHolders(structure, registry)
                    .orElseThrow(() -> LocateCommandAccessor.getStructureInvalid().create(structure.asPrintable()));
            BlockPos origin = BlockPos.containing(source.getPosition());
            ServerLevel level = source.getLevel();

            Pair<BlockPos, Holder<Structure>> found = level.getChunkSource().getGenerator()
                    .findNearestMapStructure(level, holders, origin, 64000, false);

            if (found == null) {
                throw LocateCommandAccessor.getStructureNotFound().create(structure.asPrintable());
            }

            int distance = LocateCommand.showLocateResult(
                    source, structure, origin, found,
                    "commands.locate.structure.success", false, Duration.ZERO
            );

            cir.setReturnValue(distance);
        } catch (Exception e) {
            source.sendFailure(net.minecraft.network.chat.Component.literal("LocateFixer: Error locating structure: " + e.getMessage()));
            cir.setReturnValue(0);
        }
    }

    @Inject(method = "locateBiome", at = @At("HEAD"), cancellable = true)
    private static void locatefix$overrideLocateBiome(
            CommandSourceStack source,
            ResourceOrTagArgument.Result<Biome> biome,
            CallbackInfoReturnable<Integer> cir
    ) {
        try {
            BlockPos origin = BlockPos.containing(source.getPosition());
            ServerLevel level = source.getLevel();

            Pair<BlockPos, Holder<Biome>> found = level.findClosestBiome3d(biome, origin, 64000, 32, 64);

            if (found == null) {
                throw LocateCommandAccessor.getBiomeNotFound().create(biome.asPrintable());
            }

            int distance = LocateCommand.showLocateResult(
                    source, biome, origin, found,
                    "commands.locate.biome.success", true, Duration.ZERO
            );

            cir.setReturnValue(distance);
        } catch (Exception e) {
            source.sendFailure(net.minecraft.network.chat.Component.literal("LocateFixer: Error locating biome: " + e.getMessage()));
            cir.setReturnValue(0);
        }
    }
}