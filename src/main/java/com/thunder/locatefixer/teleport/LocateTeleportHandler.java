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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class LocateTeleportHandler {

    private static final int COUNTDOWN_SECONDS = 5;
    private static final int PRELOAD_RADIUS_CHUNKS = 2;
    private static final ScheduledExecutorService PRELOAD_EXECUTOR = Executors.newSingleThreadScheduledExecutor(buildThreadFactory());

    private LocateTeleportHandler() {
    }

    public static String createCommand(ResourceKey<Level> dimension, BlockPos target) {
        return "/execute in " + dimension.location() + " run tp @s "
                + target.getX() + " " + target.getY() + " " + target.getZ();
    }

    public static void startTeleportWithPreload(ServerPlayer player, ServerLevel level, BlockPos targetPos, Runnable teleportAction) {
        List<ChunkPos> forcedChunks = forceChunks(level, targetPos);
        player.sendSystemMessage(Component.literal("📦 Preloading destination chunks..."));

        scheduleCountdown(level, player, forcedChunks, teleportAction);
    }

    private static void scheduleCountdown(ServerLevel level, ServerPlayer player, List<ChunkPos> forcedChunks, Runnable teleportAction) {
        CountdownTask task = new CountdownTask(level, player, forcedChunks, teleportAction);
        ScheduledFuture<?> future = PRELOAD_EXECUTOR.scheduleAtFixedRate(task, 0L, 1L, TimeUnit.SECONDS);
        task.attachFuture(future);
    }

    private static List<ChunkPos> forceChunks(ServerLevel level, BlockPos center) {
        List<ChunkPos> forced = new ArrayList<>();
        ChunkPos centerChunk = new ChunkPos(center);
        for (int dx = -LocateTeleportHandler.PRELOAD_RADIUS_CHUNKS; dx <= LocateTeleportHandler.PRELOAD_RADIUS_CHUNKS; dx++) {
            for (int dz = -LocateTeleportHandler.PRELOAD_RADIUS_CHUNKS; dz <= LocateTeleportHandler.PRELOAD_RADIUS_CHUNKS; dz++) {
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

    private static ThreadFactory buildThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("LocateFixer-Preload-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static final class CountdownTask implements Runnable {
        private final ServerLevel level;
        private final ServerPlayer player;
        private final List<ChunkPos> forcedChunks;
        private final Runnable teleportAction;
        private int secondsLeft;
        private ScheduledFuture<?> future;

        private CountdownTask(ServerLevel level, ServerPlayer player, List<ChunkPos> forcedChunks, Runnable teleportAction) {
            this.level = level;
            this.player = player;
            this.forcedChunks = forcedChunks;
            this.teleportAction = teleportAction;
            this.secondsLeft = COUNTDOWN_SECONDS;
        }

        private void attachFuture(ScheduledFuture<?> future) {
            this.future = future;
        }

        @Override
        public void run() {
            if (player.isRemoved()) {
                cancelAndRelease("Teleport cancelled.");
                return;
            }

            if (secondsLeft > 0) {
                int displaySeconds = secondsLeft;
                secondsLeft--;
                level.getServer().execute(() ->
                        player.sendSystemMessage(Component.literal("Teleporting in " + displaySeconds + "...")));
                return;
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
            cancelFuture();
        }

        private void cancelAndRelease(String message) {
            level.getServer().execute(() -> {
                if (!player.isRemoved()) {
                    player.sendSystemMessage(Component.literal(message));
                }
                releaseChunks(level, forcedChunks);
            });
            cancelFuture();
        }

        private void cancelFuture() {
            if (future != null && !future.isCancelled()) {
                future.cancel(false);
            }
        }
    }
}
