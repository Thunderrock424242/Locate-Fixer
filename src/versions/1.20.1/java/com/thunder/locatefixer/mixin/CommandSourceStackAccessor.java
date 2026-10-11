package com.thunder.locatefixer.mixin;

import com.mojang.brigadier.ResultConsumer;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only ownership and callback inspection; never changes command execution. */
@Mixin(CommandSourceStack.class)
public interface CommandSourceStackAccessor {
    @Accessor("source") CommandSource locatefixer$getSource();
    @Accessor("consumer") ResultConsumer<CommandSourceStack> locatefixer$getConsumer();
}
