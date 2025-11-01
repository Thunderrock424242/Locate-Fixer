package com.thunder.locatefixer.mixin;

import com.thunder.locatefixer.util.AsyncLocateHandler;
import com.thunder.locatefixer.util.LocateResultHelper;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.arguments.ResourceOrTagArgument;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.LocateCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
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

    @Inject(method = "register", at = @At("RETURN"))
    private static void locatefix$addTeleportSubcommand(
            CommandDispatcher<CommandSourceStack> dispatcher,
            CommandBuildContext context,
            CallbackInfo ci
    ) {
        CommandNode<CommandSourceStack> locateNodeRaw = dispatcher.getRoot().getChild("locate");
        if (!(locateNodeRaw instanceof LiteralCommandNode<CommandSourceStack> locateNode)) {
            return;
        }

        if (locateNode.getChild("teleport") != null) {
            return;
        }

        LiteralArgumentBuilder<CommandSourceStack> teleport = Commands.literal("teleport")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .then(Commands.argument("absoluteY", BoolArgumentType.bool())
                                                .executes(ctx -> {
                                                    CommandSourceStack source = ctx.getSource();
                                                    if (!(source.getEntity() instanceof ServerPlayer)) {
                                                        source.sendFailure(Component.literal("Teleportation is only available for players."));
                                                        return 0;
                                                    }

                                                    int x = IntegerArgumentType.getInteger(ctx, "x");
                                                    int y = IntegerArgumentType.getInteger(ctx, "y");
                                                    int z = IntegerArgumentType.getInteger(ctx, "z");
                                                    boolean absoluteY = BoolArgumentType.getBool(ctx, "absoluteY");

                                                    ServerLevel level = source.getLevel();
                                                    LocateResultHelper.startTeleportCountdown(source, level, new BlockPos(x, y, z), absoluteY);
                                                    return 1;
                                                })))));

        locateNode.addChild(teleport.build());
    }
}
