package com.thunder.locatefixer.mixin;

import com.thunder.locatefixer.util.LocateResultHelper;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Commands.class)
public abstract class CommandsMixin {

    @Inject(method = "performPrefixedCommand", at = @At("HEAD"), cancellable = true)
    private void locatefix$handleTeleportCommand(CommandSourceStack source, String command, CallbackInfo ci) {
        if (command == null) {
            return;
        }

        String trimmed = command.startsWith("/") ? command.substring(1) : command;
        if (!trimmed.startsWith("locate teleport")) {
            return;
        }

        String[] parts = trimmed.split("\\s+");
        if (parts.length != 6 || !"teleport".equals(parts[1])) {
            return;
        }

        if (!(source.getEntity() instanceof ServerPlayer)) {
            source.sendFailure(Component.literal("Teleportation is only available for players."));
            source.callback().onFailure();
            ci.cancel();
            return;
        }

        int x;
        int y;
        int z;
        boolean absoluteY;
        try {
            x = Integer.parseInt(parts[2]);
            y = Integer.parseInt(parts[3]);
            z = Integer.parseInt(parts[4]);
            absoluteY = Boolean.parseBoolean(parts[5]);
        } catch (NumberFormatException ex) {
            source.sendFailure(Component.literal("Invalid teleport request."));
            source.callback().onFailure();
            ci.cancel();
            return;
        }

        ServerLevel level = source.getLevel();
        LocateResultHelper.startTeleportCountdown(source, level, new BlockPos(x, y, z), absoluteY);
        source.callback().onSuccess(1);
        ci.cancel();
    }
}
