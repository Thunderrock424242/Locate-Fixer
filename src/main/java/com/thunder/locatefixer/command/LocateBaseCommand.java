package com.thunder.locatefixer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.thunder.locatefixer.data.PlayerBaseSavedData;
import com.thunder.locatefixer.util.LocateResultHelper;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;

public class LocateBaseCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("locate")
                .then(Commands.literal("base")
                        .requires(source -> source.getEntity() instanceof ServerPlayer)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(BaseHomeCommand::suggestOwnBases)
                                .executes(ctx -> locateOwnBase(ctx.getSource(), StringArgumentType.getString(ctx, "name")))
                        )
                        .then(Commands.argument("player", EntityArgument.player())
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggestPlayerBases(ctx, builder, "player"))
                                        .executes(ctx -> locatePlayerBase(ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"),
                                                StringArgumentType.getString(ctx, "name")))
                                )
                        )
                )
                .then(Commands.literal("home")
                        .requires(source -> source.getEntity() instanceof ServerPlayer)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(BaseHomeCommand::suggestOwnBases)
                                .executes(ctx -> locateOwnBase(ctx.getSource(), StringArgumentType.getString(ctx, "name")))
                        )
                        .then(Commands.argument("player", EntityArgument.player())
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggestPlayerBases(ctx, builder, "player"))
                                        .executes(ctx -> locatePlayerBase(ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"),
                                                StringArgumentType.getString(ctx, "name")))
                                )
                        )
                )
                .then(Commands.literal("player")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("base")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> listPlayerBases(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))
                                )
                        )
                        .then(Commands.literal("home")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> listPlayerBases(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))
                                )
                        )
                ));
    }

    private static int locateOwnBase(CommandSourceStack source, String name) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Only players can use this command."));
            return 0;
        }
        return locatePlayerBase(source, player, name);
    }

    private static int locatePlayerBase(CommandSourceStack source, ServerPlayer targetPlayer, String name) {
        PlayerBaseSavedData data = PlayerBaseSavedData.get(source.getLevel());
        BlockPos basePos = data.getBase(targetPlayer.getUUID(), name);

        if (basePos == null) {
            source.sendFailure(Component.literal("No base named '" + name.toLowerCase() + "' found for " + targetPlayer.getGameProfile().getName() + "."));
            return 0;
        }

        BlockPos origin = BlockPos.containing(source.getPosition());
        String label = targetPlayer.getGameProfile().getName() + "'s " + name.toLowerCase();
        LocateResultHelper.sendResult(source, "commands.locatefixer.base.success", label, origin, basePos, true);
        return 1;
    }

    private static int listPlayerBases(CommandSourceStack source, ServerPlayer targetPlayer) {
        PlayerBaseSavedData data = PlayerBaseSavedData.get(source.getLevel());
        Set<String> bases = data.getBaseNames(targetPlayer.getUUID());

        if (bases.isEmpty()) {
            source.sendFailure(Component.literal(targetPlayer.getGameProfile().getName() + " has no saved bases."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(targetPlayer.getGameProfile().getName() + " bases: " + String.join(", ", bases)), false);
        return bases.size();
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestPlayerBases(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder,
            String playerArg
    ) {
        try {
            ServerPlayer target = EntityArgument.getPlayer(ctx, playerArg);
            Set<String> bases = PlayerBaseSavedData.get(ctx.getSource().getLevel()).getBaseNames(target.getUUID());
            return SharedSuggestionProvider.suggest(bases, builder);
        } catch (CommandSyntaxException ignored) {
            return builder.buildFuture();
        }
    }
}
