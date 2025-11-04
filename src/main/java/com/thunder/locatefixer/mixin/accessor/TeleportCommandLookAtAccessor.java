package com.thunder.locatefixer.mixin.accessor;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "net.minecraft.server.commands.TeleportCommand$LookAt")
public interface TeleportCommandLookAtAccessor {
}
