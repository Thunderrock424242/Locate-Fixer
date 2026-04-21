package com.thunder.locatefixer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.thunder.locatefixer.data.PlayerBaseSavedData;
import com.thunder.locatefixer.teleport.LocateTeleportHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;

public class BaseHomeCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String root : new String[]{"base", "home"}) {
            dispatcher.register(Commands.literal(root)
                    .requires(source -> source.getEntity() instanceof ServerPlayer)
                    .executes(ctx -> listBases(ctx.getSource()))
                    .then(Commands.literal("set")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .executes(ctx -> setBase(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                    .then(Commands.literal("go")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .suggests(BaseHomeCommand::suggestOwnBases)
                                    .executes(ctx -> goToBase(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                    .then(Commands.literal("delete")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .suggests(BaseHomeCommand::suggestOwnBases)
                                    .executes(ctx -> deleteBase(ctx.getSource(), StringArgumentType.getString(ctx, "name"))))));
        }
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

        source.sendSuccess(() -> Component.literal(
                "Saved base '" + name.toLowerCase() + "' at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()), false);
        return 1;
    }

    static int goToBase(CommandSourceStack source, String name) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Only players can use this command."));
            return 0;
        }
        PlayerBaseSavedData data = PlayerBaseSavedData.get(player.serverLevel());
        BlockPos basePos = data.getBase(player.getUUID(), name);

        if (basePos == null) {
            source.sendFailure(Component.literal(
                    "No base named '" + name.toLowerCase() + "' found. Use /base set <name> to create one."));
            return 0;
        }

        ServerLevel level = player.serverLevel();
        source.sendSuccess(() -> Component.literal("📦 Teleporting to base '" + name.toLowerCase() + "'..."), false);
        LocateTeleportHandler.startTeleportWithPreload(player, level, basePos, safePos ->
                player.teleportTo(level,
                        safePos.getX() + 0.5D, safePos.getY(), safePos.getZ() + 0.5D,
                        player.getYRot(), player.getXRot())
        );
        return 1;
    }

    static int deleteBase(CommandSourceStack source, String name) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Only players can use this command."));
            return 0;
        }
        PlayerBaseSavedData data = PlayerBaseSavedData.get(player.serverLevel());
        boolean removed = data.removeBase(player.getUUID(), name);

        if (!removed) {
            source.sendFailure(Component.literal("No base named '" + name.toLowerCase() + "' found."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Deleted base '" + name.toLowerCase() + "'."), false);
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
