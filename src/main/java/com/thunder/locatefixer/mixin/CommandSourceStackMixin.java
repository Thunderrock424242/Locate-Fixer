package com.thunder.locatefixer.mixin;

import com.thunder.locatefixer.util.LocateResultHelper;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CommandSourceStack.class)
public abstract class CommandSourceStackMixin {

    @Inject(method = "performPrefixedCommand", at = @At("HEAD"), cancellable = true)
    private void locatefix$handleTeleportCommand(String command, CallbackInfoReturnable<Integer> cir) {
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

        CommandSourceStack source = (CommandSourceStack) (Object) this;
        if (!(source.getEntity() instanceof ServerPlayer)) {
            source.sendFailure(Component.literal("Teleportation is only available for players."));
            cir.setReturnValue(0);
            cir.cancel();
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
            cir.setReturnValue(0);
            cir.cancel();
            return;
        }

        ServerLevel level = source.getLevel();
        LocateResultHelper.startTeleportCountdown(source, level, new BlockPos(x, y, z), absoluteY);
        cir.setReturnValue(1);
        cir.cancel();
    }
}
