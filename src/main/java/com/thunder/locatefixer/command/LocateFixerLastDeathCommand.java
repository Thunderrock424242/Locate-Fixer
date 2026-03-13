package com.thunder.locatefixer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.thunder.locatefixer.util.AsyncLocateHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

import java.util.Optional;

public class LocateFixerLastDeathCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("locate")
                .then(Commands.literal("lastdeath")
                        .executes(context -> execute(context.getSource()))));
    }

    private static int execute(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();

        Optional<GlobalPos> lastDeathOptional = player.getLastDeathLocation();
        BlockPos from = BlockPos.containing(source.getPosition());
        ResourceKey<net.minecraft.world.level.Level> currentDimension = source.getLevel().dimension();

        source.sendSuccess(() -> Component.literal("🔍 Looking up your last death location..."), false);

        AsyncLocateHandler.runAsyncTask("locate-last-death", () -> {
            Component resultMessage = buildResultMessage(currentDimension, from, lastDeathOptional);
            source.getServer().execute(() -> {
                if (resultMessage == null) {
                    source.sendFailure(Component.literal("No death location recorded yet."));
                } else {
                    source.sendSuccess(() -> resultMessage, false);
                }
            });
        });

        return 1;
    }

    private static Component buildResultMessage(ResourceKey<net.minecraft.world.level.Level> currentDimension, BlockPos from, Optional<GlobalPos> lastDeathOptional) {
        if (lastDeathOptional.isEmpty()) {
            return null;
        }

        GlobalPos lastDeath = lastDeathOptional.get();
        BlockPos deathPos = lastDeath.pos();
        String dimensionName = lastDeath.dimension().location().toString();

        if (currentDimension.equals(lastDeath.dimension())) {
            int distance = Mth.floor(Mth.sqrt((float) from.distSqr(deathPos)));
            return Component.literal("☠ Last death: ")
                    .append(createCoordinateComponent(deathPos, "/tp @s " + deathPos.getX() + " " + deathPos.getY() + " " + deathPos.getZ()))
                    .append(Component.literal(" (" + distance + " blocks away)"));
        }

        String teleportSuggestion = "/execute in " + dimensionName + " run tp @s "
                + deathPos.getX() + " " + deathPos.getY() + " " + deathPos.getZ();
        return Component.literal("☠ Last death in " + dimensionName + ": ")
                .append(createCoordinateComponent(deathPos, teleportSuggestion));
    }

    private static Component createCoordinateComponent(BlockPos pos, String clickCommand) {
        return ComponentUtils.wrapInSquareBrackets(
                Component.translatable("chat.coordinates", pos.getX(), pos.getY(), pos.getZ())
        ).withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, clickCommand))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("chat.coordinates.tooltip")))
        );
    }
}
