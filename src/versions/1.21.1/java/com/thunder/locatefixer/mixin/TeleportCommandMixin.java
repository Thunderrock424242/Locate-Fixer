package com.thunder.locatefixer.mixin;

import net.minecraft.server.commands.TeleportCommand;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Retained as an inert compatibility class. Locate travel is explicitly requested
 * through the locate argument, and this mixin is absent from runtime configuration.
 */
@Deprecated
@Mixin(TeleportCommand.class)
public abstract class TeleportCommandMixin {
}
