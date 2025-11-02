package com.thunder.locatefixer.teleport;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class LocateTeleportHandler {

    private static final int COUNTDOWN_SECONDS = 5;
    private static final int PRELOAD_RADIUS_CHUNKS = 2;

    private LocateTeleportHandler() {
    }

    public static String createCommand(ResourceKey<Level> dimension, BlockPos target) {
        return "/execute in " + dimension.location() + " run tp @s "
                + target.getX() + " " + target.getY() + " " + target.getZ();
    }

    public static void startTeleportWithPreload(ServerPlayer player, ServerLevel level, BlockPos targetPos, Runnable teleportAction) {
        List<ChunkPos> forcedChunks = forceChunks(level, targetPos, PRELOAD_RADIUS_CHUNKS);
        player.sendSystemMessage(Component.literal("📦 Preloading destination chunks..."));

        CompletableFuture.runAsync(() -> runCountdown(level, player, forcedChunks, teleportAction));
    }

    private static void runCountdown(ServerLevel level, ServerPlayer player, List<ChunkPos> forcedChunks, Runnable teleportAction) {
        try {
            for (int secondsLeft = COUNTDOWN_SECONDS; secondsLeft > 0; secondsLeft--) {
                int displaySeconds = secondsLeft;
                level.getServer().execute(() -> {
                    if (!player.isRemoved()) {
                        player.sendSystemMessage(Component.literal("Teleporting in " + displaySeconds + "..."));
                    }
                });
                Thread.sleep(1000L);
            }

            level.getServer().execute(() -> {
                try {
                    if (!player.isRemoved()) {
                        teleportAction.run();
                    }
                } catch (Exception e) {
                    if (!player.isRemoved()) {
                        player.sendSystemMessage(Component.literal("Teleport failed: " + e.getMessage()));
                    }
                } finally {
                    releaseChunks(level, forcedChunks);
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            level.getServer().execute(() -> {
                if (!player.isRemoved()) {
                    player.sendSystemMessage(Component.literal("Teleport cancelled."));
                }
                releaseChunks(level, forcedChunks);
            });
        }
    }

    private static List<ChunkPos> forceChunks(ServerLevel level, BlockPos center, int radius) {
        List<ChunkPos> forced = new ArrayList<>();
        ChunkPos centerChunk = new ChunkPos(center);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos chunkPos = new ChunkPos(centerChunk.x + dx, centerChunk.z + dz);
                level.setChunkForced(chunkPos.x, chunkPos.z, true);
                forced.add(chunkPos);
            }
        }
        return forced;
    }

    private static void releaseChunks(ServerLevel level, List<ChunkPos> forcedChunks) {
        for (ChunkPos chunkPos : forcedChunks) {
            level.setChunkForced(chunkPos.x, chunkPos.z, false);
        }
    }
}
