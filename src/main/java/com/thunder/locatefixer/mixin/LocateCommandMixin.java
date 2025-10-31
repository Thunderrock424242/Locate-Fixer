package com.thunder.locatefixer.mixin;

import com.thunder.locatefixer.util.AsyncLocateHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceOrTagArgument;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.commands.LocateCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


@Mixin(LocateCommand.class)
public class LocateCommandMixin {

    @Inject(method = "locateStructure", at = @At("HEAD"), cancellable = true)
    private static void locatefix$asyncLocateStructure(
            CommandSourceStack source,
            ResourceOrTagKeyArgument.Result<Structure> structure,
            CallbackInfoReturnable<Integer> cir
    ) {
        ServerLevel level = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());

        AsyncLocateHandler.locateStructureAsync(source, structure, origin, level);
        cir.setReturnValue(1); // fake immediate return
    }

    @Inject(method = "locateBiome", at = @At("HEAD"), cancellable = true)
    private static void locatefix$asyncLocateBiome(
            CommandSourceStack source,
            ResourceOrTagArgument.Result<Biome> biome,
            CallbackInfoReturnable<Integer> cir
    ) {
        ServerLevel level = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());

        AsyncLocateHandler.locateBiomeAsync(source, biome, origin, level);
        cir.setReturnValue(1); // fake return — actual logic runs async
    }

}
