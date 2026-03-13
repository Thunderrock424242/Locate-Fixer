package com.thunder.locatefixer.mixin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.thunder.locatefixer.util.AsyncLocateHandler;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.commands.LocateCommand;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocateCommand.class)
public class LocateVariantCommandMixin {

    @Inject(method = "register", at = @At("TAIL"))
    private static void locatefix$registerBiomeVariantsCommand(
            CommandDispatcher<CommandSourceStack> dispatcher,
            CommandBuildContext context,
            CallbackInfo ci
    ) {
        CommandNode<CommandSourceStack> locateNode = dispatcher.getRoot().getChild("locate");
        if (!(locateNode instanceof LiteralCommandNode<CommandSourceStack> literalLocateNode)
                || locateNode.getChild("biomevariants") != null) {
            return;
        }

        LiteralArgumentBuilder<CommandSourceStack> biomeVariants = Commands.literal("biomevariants")
                .then(Commands.argument("query", StringArgumentType.word())
                        .executes(command -> {
                            CommandSourceStack source = command.getSource();
                            ServerLevel level = source.getLevel();
                            BlockPos origin = BlockPos.containing(source.getPosition());
                            String query = StringArgumentType.getString(command, "query");
                            AsyncLocateHandler.locateBiomeVariantsAsync(source, query, origin, level);
                            return 1;
        }));

        literalLocateNode.addChild(biomeVariants.build());
    }
}
