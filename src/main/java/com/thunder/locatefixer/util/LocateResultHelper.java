package com.thunder.locatefixer.util;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.util.Mth;

public class LocateResultHelper {

    public static void sendResult(CommandSourceStack source, String label, Holder<?> target, BlockPos from, BlockPos to, boolean absoluteY) {
        int distance = absoluteY
                ? Mth.floor(Mth.sqrt((float) from.distSqr(to)))
                : Mth.floor(distXZ(from, to));

        String yText = absoluteY ? String.valueOf(to.getY()) : "~";
        Component coords = ComponentUtils.wrapInSquareBrackets(
                Component.translatable("chat.coordinates", to.getX(), yText, to.getZ())
        ).withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/tp @s " + to.getX() + " " + yText + " " + to.getZ()))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("chat.coordinates.tooltip")))
        );

        Component message = Component.translatable(label,
                target.getRegisteredName() != null ? target.getRegisteredName() : "unknown",
                coords,
                distance
        );

        source.sendSuccess(() -> message, false);
    }

    private static float distXZ(BlockPos a, BlockPos b) {
        int dx = b.getX() - a.getX();
        int dz = b.getZ() - a.getZ();
        return Mth.sqrt((float)(dx * dx + dz * dz));
    }
    public static void sendResult(CommandSourceStack source, String label, String name, BlockPos from, BlockPos to, boolean absoluteY) {
        int distance = absoluteY
                ? Mth.floor(Mth.sqrt((float) from.distSqr(to)))
                : Mth.floor(distXZ(from, to));

        String yText = absoluteY ? String.valueOf(to.getY()) : "~";
        Component coords = ComponentUtils.wrapInSquareBrackets(
                Component.translatable("chat.coordinates", to.getX(), yText, to.getZ())
        ).withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/tp @s " + to.getX() + " " + yText + " " + to.getZ()))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("chat.coordinates.tooltip")))
        );

        Component message = Component.translatable(label, name, coords, distance);
        source.sendSuccess(() -> message, false);
    }
}