package com.thunder.locatefixer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.thunder.locatefixer.util.LocateResultHelper;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public class LocateFixerPlayerCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("locate")
                .then(Commands.literal("player")
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(ctx -> {
                                    CommandSourceStack source = ctx.getSource();
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                    ServerLevel targetLevel = target.serverLevel();
                                    ServerLevel sourceLevel = source.getLevel();

                                    if (targetLevel != sourceLevel) {
                                        source.sendFailure(Component.literal(
                                                "❌ Player '" + target.getGameProfile().getName() + "' is in dimension "
                                                        + targetLevel.dimension().location()));
                                        return 0;
                                    }

                                    BlockPos origin = BlockPos.containing(source.getPosition());
                                    BlockPos targetPos = BlockPos.containing(target.position());

                                    LocateResultHelper.sendResult(source,
                                            "commands.locate.player.success",
                                            target.getDisplayName().getString(),
                                            origin,
                                            targetPos,
                                            true);
                                    LocateResultHelper.startTeleportCountdown(source, sourceLevel, targetPos, true);
                                    return 1;
                                }))));
    }
}
