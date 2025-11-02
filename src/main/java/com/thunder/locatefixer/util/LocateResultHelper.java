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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.concurrent.CompletableFuture;

public class LocateResultHelper {

    private static final int TELEPORT_COUNTDOWN_SECONDS = 5;

    public static void sendResult(CommandSourceStack source, String label, Holder<?> target, BlockPos from, BlockPos to, boolean absoluteY) {
        int distance = absoluteY
                ? Mth.floor(Mth.sqrt((float) from.distSqr(to)))
                : Mth.floor(distXZ(from, to));

        String yText = absoluteY ? String.valueOf(to.getY()) : "~";
        String teleportCommand = String.format("/locate teleport %d %d %d %s",
                to.getX(),
                to.getY(),
                to.getZ(),
                absoluteY ? "true" : "false");

        Component coords = ComponentUtils.wrapInSquareBrackets(
                Component.translatable("chat.coordinates", to.getX(), yText, to.getZ())
        ).withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, teleportCommand))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("Click to teleport after preloading.")))
        );

        Component message = Component.translatable(label,
                target.getRegisteredName() != null ? target.getRegisteredName() : "unknown",
                coords,
                distance
        );

        source.sendSuccess(() -> message, false);
    }

    public static void startTeleportCountdown(CommandSourceStack source, ServerLevel level, BlockPos target, boolean absoluteY) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            ChunkPos chunkPos = new ChunkPos(target);
            ServerChunkCache chunkSource = level.getChunkSource();
            int ticketIdentifier = chunkPos.hashCode();

            chunkSource.addRegionTicket(TicketType.POST_TELEPORT, chunkPos, 1, ticketIdentifier);

            try {
                for (int seconds = TELEPORT_COUNTDOWN_SECONDS; seconds > 0; seconds--) {
                    int countdown = seconds;
                    level.getServer().execute(() -> player.sendSystemMessage(
                            Component.literal("⏳ Teleporting in " + countdown + "... (preloading area)")));
                    Thread.sleep(1000L);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                level.getServer().execute(() -> player.sendSystemMessage(
                        Component.literal("⚠️ Teleport cancelled.")));
                chunkSource.removeRegionTicket(TicketType.POST_TELEPORT, chunkPos, 1, ticketIdentifier);
                return;
            }

            level.getServer().execute(() -> {
                if (player.isRemoved()) {
                    chunkSource.removeRegionTicket(TicketType.POST_TELEPORT, chunkPos, 1, ticketIdentifier);
                    return;
                }
                double y = absoluteY
                        ? target.getY()
                        : level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, target.getX(), target.getZ());

                player.teleportTo(level,
                        target.getX() + 0.5,
                        y,
                        target.getZ() + 0.5,
                        player.getYRot(),
                        player.getXRot());
                player.sendSystemMessage(Component.literal("✅ Teleported to located area."));
                chunkSource.removeRegionTicket(TicketType.POST_TELEPORT, chunkPos, 1, ticketIdentifier);
            });
        });
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
        String teleportCommand = String.format("/locate teleport %d %d %d %s",
                to.getX(),
                to.getY(),
                to.getZ(),
                absoluteY ? "true" : "false");

        Component coords = ComponentUtils.wrapInSquareBrackets(
                Component.translatable("chat.coordinates", to.getX(), yText, to.getZ())
        ).withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, teleportCommand))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("Click to teleport after preloading.")))
        );

        Component message = Component.translatable(label, name, coords, distance);
        source.sendSuccess(() -> message, false);
    }
}