package com.thunder.locatefixer.teleport;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.common.Tags;
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
import java.util.function.Consumer;

public final class LocateTeleportHandler {

    private static final int COUNTDOWN_SECONDS = 5;
    private static final int PRELOAD_RADIUS_CHUNKS = 2;
    private static final int SAFE_AREA_RADIUS = 1;
    private static final int SAFE_AREA_HEIGHT = 2;
    private static final int SAFE_SEARCH_UP = 24;
    private static final int SAFE_SEARCH_DOWN = 12;
    private static final int SAFE_SEARCH_HORIZONTAL = 4;
    private static final ScheduledExecutorService PRELOAD_EXECUTOR = Executors.newSingleThreadScheduledExecutor(buildThreadFactory());
    private static final TagKey<Biome> CAVE_BIOME_TAG = Tags.Biomes.IS_CAVE;

    private LocateTeleportHandler() {
    }

    public static String createCommand(ResourceKey<Level> dimension, BlockPos target) {
        return "/execute in " + dimension.location() + " run tp @s "
                + target.getX() + " " + target.getY() + " " + target.getZ();
    }

    public static void startTeleportWithPreload(ServerPlayer player,
                                                ServerLevel level,
                                                BlockPos targetPos,
                                                Consumer<BlockPos> teleportAction) {
        List<ChunkPos> forcedChunks = forceChunks(level, targetPos);
        player.sendSystemMessage(Component.literal("📦 Preloading destination chunks..."));
        sendActionBar(player, Component.literal("📦 Preloading " + forcedChunks.size() + " chunks..."));

        BlockPos safePos = findSafeTeleportPosition(level, targetPos);
        scheduleCountdown(level, player, forcedChunks, safePos, teleportAction);
    }

    public static BlockPos findSafeTeleportPosition(ServerLevel level, BlockPos targetPos) {
        if (level.getBiome(targetPos).is(CAVE_BIOME_TAG)) {
            return findCaveSafePosition(level, targetPos);
        }
        return findSurfaceSafePosition(level, targetPos);
    }

    private static BlockPos findSurfaceSafePosition(ServerLevel level, BlockPos targetPos) {
        if (isSafePosition(level, targetPos)) {
            return targetPos;
        }

        for (int offset = 1; offset <= SAFE_SEARCH_UP; offset++) {
            BlockPos up = targetPos.above(offset);
            if (isSafePosition(level, up)) {
                return up;
            }
        }

        return targetPos;
    }

    private static BlockPos findCaveSafePosition(ServerLevel level, BlockPos targetPos) {
        if (isSafePosition(level, targetPos)) {
            return targetPos;
        }

        int maxRange = Math.max(SAFE_SEARCH_UP, SAFE_SEARCH_DOWN);
        for (int vertical = 0; vertical <= maxRange; vertical++) {
            if (vertical == 0) {
                BlockPos match = findCaveSafePositionAtYOffset(level, targetPos, 0);
                if (match != null) {
                    return match;
                }
                continue;
            }

            if (vertical <= SAFE_SEARCH_UP) {
                BlockPos match = findCaveSafePositionAtYOffset(level, targetPos, vertical);
                if (match != null) {
                    return match;
                }
            }

            if (vertical <= SAFE_SEARCH_DOWN) {
                BlockPos match = findCaveSafePositionAtYOffset(level, targetPos, -vertical);
                if (match != null) {
                    return match;
                }
            }
        }

        return targetPos;
    }

    private static BlockPos findCaveSafePositionAtYOffset(ServerLevel level, BlockPos targetPos, int yOffset) {
        BlockPos base = targetPos.offset(0, yOffset, 0);
        for (int radius = 0; radius <= SAFE_SEARCH_HORIZONTAL; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    BlockPos candidate = base.offset(dx, 0, dz);
                    if (isSafePosition(level, candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private static void scheduleCountdown(ServerLevel level,
                                          ServerPlayer player,
                                          List<ChunkPos> forcedChunks,
                                          BlockPos safePos,
                                          Consumer<BlockPos> teleportAction) {
        CountdownTask task = new CountdownTask(level, player, forcedChunks, safePos, teleportAction);
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

    private static boolean isSafePosition(ServerLevel level, BlockPos pos) {
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - SAFE_AREA_HEIGHT;
        if (pos.getY() < minY || pos.getY() > maxY) {
            return false;
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -SAFE_AREA_RADIUS; dx <= SAFE_AREA_RADIUS; dx++) {
            for (int dz = -SAFE_AREA_RADIUS; dz <= SAFE_AREA_RADIUS; dz++) {
                for (int dy = 0; dy < SAFE_AREA_HEIGHT; dy++) {
                    cursor.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (!level.isEmptyBlock(cursor)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static final class CountdownTask implements Runnable {
        private final ServerLevel level;
        private final ServerPlayer player;
        private final List<ChunkPos> forcedChunks;
        private final BlockPos safePos;
        private final Consumer<BlockPos> teleportAction;
        private int secondsLeft;
        private ScheduledFuture<?> future;

        private CountdownTask(ServerLevel level,
                              ServerPlayer player,
                              List<ChunkPos> forcedChunks,
                              BlockPos safePos,
                              Consumer<BlockPos> teleportAction) {
            this.level = level;
            this.player = player;
            this.forcedChunks = forcedChunks;
            this.safePos = safePos;
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
                        sendActionBar(player, Component.literal("✅ Destination ready."));
                        teleportAction.accept(safePos);
                    }
                } catch (Exception e) {
                    if (!player.isRemoved()) {
                        player.sendSystemMessage(Component.literal("Teleport failed: " + e.getMessage()));
                        sendActionBar(player, Component.literal("❌ Teleport failed."));
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

    private static void sendActionBar(ServerPlayer player, Component message) {
        player.displayClientMessage(message, true);
    }
}
