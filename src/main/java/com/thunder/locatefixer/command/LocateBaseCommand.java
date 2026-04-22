package com.thunder.locatefixer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.authlib.GameProfile;
import com.thunder.locatefixer.config.LocateFixerConfig;
import com.thunder.locatefixer.data.PlayerBaseSavedData;
import com.thunder.locatefixer.util.LocateResultHelper;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

public class LocateBaseCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("locate")
                .then(Commands.literal("home")
                        .requires(source -> isBaseHomeEnabled())
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
                .then(Commands.literal("playerhome")
                        .requires(source -> isBaseHomeEnabled())
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(ctx -> locateDefaultPlayerBase(ctx.getSource(), GameProfileArgument.getGameProfiles(ctx, "player")))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggestGameProfileBases(ctx, builder, "player"))
                                        .executes(ctx -> locatePlayerBase(ctx.getSource(),
                                                GameProfileArgument.getGameProfiles(ctx, "player"),
                                                StringArgumentType.getString(ctx, "name")))
                                )
                        )
                ));
    }

    private static boolean isBaseHomeEnabled() {
        try {
            return LocateFixerConfig.SERVER.enableBaseHomeCommands.get();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private static int locateOwnBase(CommandSourceStack source, String name) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Only players can use this command."));
            return 0;
        }
        return locatePlayerBase(source, player, name);
    }

    private static int locatePlayerBase(CommandSourceStack source, ServerPlayer targetPlayer, String name) {
        return locatePlayerBase(source, targetPlayer.getUUID(), targetPlayer.getGameProfile().getName(), name);
    }

    private static int locatePlayerBase(CommandSourceStack source, Collection<GameProfile> gameProfiles, String name) {
        GameProfile profile = firstProfile(gameProfiles);
        if (profile == null) {
            source.sendFailure(Component.literal("Could not resolve that player profile."));
            return 0;
        }

        return locatePlayerBase(source, profile.getId(), profile.getName(), name);
    }

    private static int locatePlayerBase(CommandSourceStack source, UUID targetPlayerId, String targetPlayerName, String name) {
        PlayerBaseSavedData data = PlayerBaseSavedData.get(source.getLevel());
        BlockPos basePos = data.getBase(targetPlayerId, name);

        if (basePos == null) {
            source.sendFailure(Component.literal("No base named '" + name.toLowerCase() + "' found for " + targetPlayerName + "."));
            return 0;
        }

        BlockPos origin = BlockPos.containing(source.getPosition());
        String label = targetPlayerName + "'s " + name.toLowerCase();
        LocateResultHelper.sendResult(source, "commands.locatefixer.base.success", label, origin, basePos, true);
        return 1;
    }

    private static int locateDefaultPlayerBase(CommandSourceStack source, Collection<GameProfile> gameProfiles) {
        GameProfile profile = firstProfile(gameProfiles);
        if (profile == null) {
            source.sendFailure(Component.literal("Could not resolve that player profile."));
            return 0;
        }

        PlayerBaseSavedData data = PlayerBaseSavedData.get(source.getLevel());
        Set<String> baseNames = data.getBaseNames(profile.getId());
        if (baseNames.isEmpty()) {
            source.sendFailure(Component.literal(profile.getName() + " has no saved bases."));
            return 0;
        }

        String defaultBase = baseNames.contains("base") ? "base" : baseNames.iterator().next();
        return locatePlayerBase(source, profile.getId(), profile.getName(), defaultBase);
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

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestGameProfileBases(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder,
            String playerArg
    ) {
        try {
            GameProfile profile = firstProfile(GameProfileArgument.getGameProfiles(ctx, playerArg));
            if (profile == null) {
                return builder.buildFuture();
            }

            Set<String> bases = PlayerBaseSavedData.get(ctx.getSource().getLevel()).getBaseNames(profile.getId());
            return SharedSuggestionProvider.suggest(bases, builder);
        } catch (CommandSyntaxException ignored) {
            return builder.buildFuture();
        }
    }

    private static GameProfile firstProfile(Collection<GameProfile> profiles) {
        if (profiles == null || profiles.isEmpty()) {
            return null;
        }

        return profiles.iterator().next();
    }
}
