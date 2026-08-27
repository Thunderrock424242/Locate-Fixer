package com.thunder.locatefixer.mixin;

import com.mojang.brigadier.ParseResults;
import com.thunder.locatefixer.util.CommandErrorFixer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Commands.class)
abstract class FabricCommandErrorMixin {
    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void locatefixer$improveCommandError(
            ParseResults<CommandSourceStack> parseResults,
            String command,
            CallbackInfoReturnable<Integer> callback
    ) {
        if (CommandErrorFixer.handle(parseResults)) {
            callback.setReturnValue(0);
        }
    }
}
