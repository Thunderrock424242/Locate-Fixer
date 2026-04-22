package com.thunder.locatefixer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.thunder.locatefixer.teleport.LocateTeleportHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class LocateFixerConfirmCommand {
    private LocateFixerConfirmCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("locatefixerconfirm")
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .then(Commands.literal("yes")
                        .executes(ctx -> respond(ctx.getSource(), true)))
                .then(Commands.literal("no")
                        .executes(ctx -> respond(ctx.getSource(), false))));
    }

    private static int respond(CommandSourceStack source, boolean allowTeleport) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }

        boolean handled = LocateTeleportHandler.respondToUnsafeTeleport(player, allowTeleport);
        if (!handled) {
            source.sendFailure(Component.literal("There is no pending teleport confirmation."));
            return 0;
        }

        return 1;
    }
}
