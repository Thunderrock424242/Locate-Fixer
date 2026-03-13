package com.thunder.locatefixer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.thunder.locatefixer.data.PlayerBaseSavedData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;

public class BaseHomeCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("base")
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .executes(ctx -> listBases(ctx.getSource()))
                .then(Commands.literal("set")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> setBase(ctx.getSource(), StringArgumentType.getString(ctx, "name"))))));

        dispatcher.register(Commands.literal("home")
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .executes(ctx -> listBases(ctx.getSource()))
                .then(Commands.literal("set")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> setBase(ctx.getSource(), StringArgumentType.getString(ctx, "name"))))));
    }

    static int listBases(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Only players can use this command."));
            return 0;
        }
        PlayerBaseSavedData data = PlayerBaseSavedData.get(player.serverLevel());
        Set<String> bases = data.getBaseNames(player.getUUID());

        if (bases.isEmpty()) {
            source.sendFailure(Component.literal("You do not have any bases yet. Use /base set <name> while standing at your base."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Your bases: " + String.join(", ", bases)), false);
        return bases.size();
    }

    static int setBase(CommandSourceStack source, String name) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Only players can use this command."));
            return 0;
        }
        BlockPos pos = player.blockPosition();
        PlayerBaseSavedData data = PlayerBaseSavedData.get(player.serverLevel());
        data.setBase(player.getUUID(), name, pos);

        source.sendSuccess(() -> Component.literal("Saved base '" + name.toLowerCase() + "' at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()), false);
        return 1;
    }

    static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestOwnBases(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder
    ) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            return builder.buildFuture();
        }

        Set<String> bases = PlayerBaseSavedData.get(player.serverLevel()).getBaseNames(player.getUUID());
        return SharedSuggestionProvider.suggest(bases, builder);
    }
}
