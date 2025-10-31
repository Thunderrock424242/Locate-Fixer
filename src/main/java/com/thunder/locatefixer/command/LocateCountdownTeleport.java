package com.thunder.locatefixer.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class LocateCountdownTeleport {

    private static final int COUNTDOWN_SECONDS = 5;

    private LocateCountdownTeleport() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var locateNode = dispatcher.getRoot().getChild("locate");
        if (!(locateNode instanceof LiteralCommandNode<CommandSourceStack> locateLiteral)) {
            return;
        }

        locateLiteral.addChild(Commands.literal("teleport")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(context -> teleportWithCountdown(context, BlockPosArgument.getBlockPos(context, "pos"))))
                .build());
    }

    private static int teleportWithCountdown(CommandContext<CommandSourceStack> context, BlockPos targetPos) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        MinecraftServer server = level.getServer();
        ServerChunkCache chunkSource = level.getChunkSource();
        ChunkPos chunkPos = new ChunkPos(targetPos);
        int ticketId = player.getId();

        chunkSource.addRegionTicket(TicketType.POST_TELEPORT, chunkPos, 1, ticketId);

        player.sendSystemMessage(Component.literal("📦 Preloading destination chunk..."));

        for (int secondsLeft = COUNTDOWN_SECONDS; secondsLeft >= 1; secondsLeft--) {
            int delaySeconds = COUNTDOWN_SECONDS - secondsLeft;
            int seconds = secondsLeft;
            schedule(server, delaySeconds, () -> {
                if (!player.isRemoved()) {
                    player.sendSystemMessage(Component.literal("Teleporting in " + seconds + "..."));
                }
            });
        }

        schedule(server, COUNTDOWN_SECONDS, () -> {
            if (player.isRemoved()) {
                chunkSource.removeRegionTicket(TicketType.POST_TELEPORT, chunkPos, 1, ticketId);
                return;
            }

            double x = targetPos.getX() + 0.5;
            double y = targetPos.getY();
            double z = targetPos.getZ() + 0.5;

            player.teleportTo(level, x, y, z, player.getYRot(), player.getXRot());
            player.sendSystemMessage(Component.literal("✅ Teleported!"));

            schedule(server, 1, () -> chunkSource.removeRegionTicket(TicketType.POST_TELEPORT, chunkPos, 1, ticketId));
        });

        return Command.SINGLE_SUCCESS;
    }

    private static void schedule(MinecraftServer server, int delaySeconds, Runnable action) {
        if (delaySeconds <= 0) {
            server.execute(action);
            return;
        }

        CompletableFuture.delayedExecutor(delaySeconds, TimeUnit.SECONDS)
                .execute(() -> server.execute(action));
    }
}
