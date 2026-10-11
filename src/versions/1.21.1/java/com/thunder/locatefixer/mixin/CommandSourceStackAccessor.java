package com.thunder.locatefixer.mixin;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to the stable command owner, including on Fabric. */
@Mixin(CommandSourceStack.class)
public interface CommandSourceStackAccessor {
    @Accessor("source") CommandSource locatefixer$getSource();
}
