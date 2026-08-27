package com.thunder.locatefixer.teleport;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class LocateTeleportHandler {

    private static final int COUNTDOWN_SECONDS = 5;
    private static final int PRELOAD_RADIUS_CHUNKS = 2;
    private static final int SAFE_AREA_RADIUS = 0;
    private static final int SAFE_AREA_HEIGHT = 2;
    private static final int SAFE_SEARCH_UP = 24;
    private static final int SAFE_SEARCH_DOWN = 12;
    private static final int SAFE_SEARCH_HORIZONTAL = 4;
    private static final int POCKET_RADIUS = 1;
    private static final int POCKET_HEIGHT = 3;
    private static final ScheduledExecutorService PRELOAD_EXECUTOR = Executors.newSingleThreadScheduledExecutor(buildThreadFactory());
    private static final Set<CountdownTask> ACTIVE_COUNTDOWNS = ConcurrentHashMap.newKeySet();
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
        ChunkPos targetChunk = new ChunkPos(targetPos);
        player.sendSystemMessage(Component.literal("📦 Preloading destination chunks around "
                + "[" + targetChunk.x + ", " + targetChunk.z + "]"
                + " (radius " + PRELOAD_RADIUS_CHUNKS + ", " + forcedChunks.size() + " newly forced)."));
        sendActionBar(player, Component.literal("📦 Chunk preload: warming up destination..."));

        LandingPlan landingPlan = findLandingPlan(level, targetPos);
        BlockPos safePos = landingPlan.position();
        int offsetX = safePos.getX() - targetPos.getX();
        int offsetY = safePos.getY() - targetPos.getY();
        int offsetZ = safePos.getZ() - targetPos.getZ();
        player.sendSystemMessage(Component.literal("🛰 Teleport safety scan complete: "
                + safePos.getX() + " " + safePos.getY() + " " + safePos.getZ()
                + " (offset Δ" + offsetX + ", Δ" + offsetY + ", Δ" + offsetZ + ")."));
        if (landingPlan.carvePocket()) {
            player.sendSystemMessage(Component.literal("⛏ An underground safety pocket will be prepared at the destination."));
        }
        scheduleCountdown(level, player, forcedChunks, targetPos, safePos, teleportAction);
    }

    public static BlockPos findSafeTeleportPosition(ServerLevel level, BlockPos targetPos) {
        return findLandingPlan(level, targetPos).position();
    }

    public static BlockPos findSurfaceSafeTeleportPosition(ServerLevel level, BlockPos targetPos) {
        return findSurfaceSafePosition(level, targetPos);
    }

    /**
     * OPTIMIZED: Uses downward ray-tracing to find the true surface,
     * ensuring players don't spawn inside decorations or transparent blocks.
     */
    private static BlockPos findSurfaceSafePosition(ServerLevel level, BlockPos targetPos) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(
                targetPos.getX(), level.getMaxBuildHeight() - 1, targetPos.getZ());

        while (cursor.getY() > level.getMinBuildHeight()) {
            BlockState state = level.getBlockState(cursor);

            // If we hit a solid block that isn't air, liquid, or leaves
            if (!state.isAir() && state.getFluidState().isEmpty() && !state.is(net.minecraft.tags.BlockTags.LEAVES)) {
                BlockPos ground = cursor.above().immutable();
                if (isSafePosition(level, ground)) {
                    return ground;
                }
            }
            cursor.move(net.minecraft.core.Direction.DOWN);
        }

        return targetPos; // Fallback to original position if no surface found
    }

    private static BlockPos findPreferredSurfacePosition(ServerLevel level, BlockPos targetPos) {
        for (int radius = 0; radius <= SAFE_SEARCH_HORIZONTAL; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    BlockPos columnTarget = targetPos.offset(dx, 0, dz);
                    BlockPos surface = findSurfaceSafePosition(level, columnTarget);
                    if (surface != null && !surface.equals(columnTarget) && isSafePosition(level, surface)) {
                        return surface;
                    }
                }
            }
        }
        return null;
    }

    private static BlockPos findNearbySafePosition(ServerLevel level, BlockPos targetPos) {
        BlockPos best = null;
        long bestDistanceSq = Long.MAX_VALUE;
        int minY = Math.max(level.getMinBuildHeight() + 1, targetPos.getY() - SAFE_SEARCH_DOWN);
        int maxY = Math.min(level.getMaxBuildHeight() - SAFE_AREA_HEIGHT, targetPos.getY() + SAFE_SEARCH_UP);

        for (int y = minY; y <= maxY; y++) {
            for (int dx = -SAFE_SEARCH_HORIZONTAL; dx <= SAFE_SEARCH_HORIZONTAL; dx++) {
                for (int dz = -SAFE_SEARCH_HORIZONTAL; dz <= SAFE_SEARCH_HORIZONTAL; dz++) {
                    BlockPos candidate = new BlockPos(targetPos.getX() + dx, y, targetPos.getZ() + dz);
                    long distanceSq = distanceSq(targetPos, candidate);
                    if (distanceSq < bestDistanceSq && isSafePosition(level, candidate)) {
                        best = candidate;
                        bestDistanceSq = distanceSq;
                    }
                }
            }
        }

        return best;
    }

    private static LandingPlan findLandingPlan(ServerLevel level, BlockPos targetPos) {
        // Prefer putting the player on top of the located biome or structure.
        // The surface search keeps the requested X/Z first and only expands a few
        // blocks when that column has no safe landing.
        BlockPos surface = findPreferredSurfacePosition(level, targetPos);
        if (surface != null) {
            return new LandingPlan(surface, false);
        }

        // Some dimensions and enclosed structures have no usable surface nearby.
        // In that case, keep the fallback as close to the locate coordinate as possible.
        BlockPos nearbySafe = findNearbySafePosition(level, targetPos);

        if (isUnderground(level, targetPos)) {
            BlockPos pocketAnchor = findNearestPocketAnchor(level, targetPos);
            if (pocketAnchor != null && (nearbySafe == null
                    || distanceSq(targetPos, pocketAnchor) < distanceSq(targetPos, nearbySafe))) {
                return new LandingPlan(pocketAnchor, true);
            }
        }

        if (nearbySafe != null) {
            return new LandingPlan(nearbySafe, false);
        }

        return new LandingPlan(targetPos, false);
    }

    private static BlockPos prepareLandingPosition(ServerLevel level, BlockPos targetPos) {
        LandingPlan finalPlan = findLandingPlan(level, targetPos);
        BlockPos finalPos = finalPlan.position();

        if (finalPlan.carvePocket() && !carveSafetyPocket(level, finalPos)) {
            // Re-scan in case the destination changed during the preload countdown.
            BlockPos naturalFallback = findNearbySafePosition(level, targetPos);
            if (naturalFallback != null) {
                finalPos = naturalFallback;
            } else {
                BlockPos surfaceFallback = findPreferredSurfacePosition(level, targetPos);
                if (surfaceFallback != null) {
                    finalPos = surfaceFallback;
                }
            }
        }

        if (!isSafePosition(level, finalPos)) {
            throw new IllegalStateException("No safe landing position could be prepared near the destination.");
        }
        return finalPos;
    }

    private static boolean isUnderground(ServerLevel level, BlockPos targetPos) {
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                targetPos.getX(), targetPos.getZ());
        return targetPos.getY() + SAFE_AREA_HEIGHT < surfaceY;
    }

    private static BlockPos findNearestPocketAnchor(ServerLevel level, BlockPos targetPos) {
        BlockPos best = null;
        long bestDistanceSq = Long.MAX_VALUE;
        int minY = Math.max(level.getMinBuildHeight() + 1, targetPos.getY() - SAFE_SEARCH_DOWN);
        int maxY = Math.min(level.getMaxBuildHeight() - POCKET_HEIGHT, targetPos.getY() + SAFE_SEARCH_UP);

        for (int y = minY; y <= maxY; y++) {
            for (int dx = -SAFE_SEARCH_HORIZONTAL; dx <= SAFE_SEARCH_HORIZONTAL; dx++) {
                for (int dz = -SAFE_SEARCH_HORIZONTAL; dz <= SAFE_SEARCH_HORIZONTAL; dz++) {
                    BlockPos candidate = new BlockPos(targetPos.getX() + dx, y, targetPos.getZ() + dz);
                    long distanceSq = distanceSq(targetPos, candidate);
                    if (distanceSq < bestDistanceSq && canCarveSafetyPocket(level, candidate)) {
                        best = candidate;
                        bestDistanceSq = distanceSq;
                    }
                }
            }
        }

        return best;
    }

    private static boolean canCarveSafetyPocket(ServerLevel level, BlockPos anchor) {
        BlockPos floorPos = anchor.below();
        BlockState floor = level.getBlockState(floorPos);
        if (!floor.isFaceSturdy(level, floorPos, net.minecraft.core.Direction.UP)
                || !floor.getFluidState().isEmpty()) {
            return false;
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -POCKET_RADIUS; dx <= POCKET_RADIUS; dx++) {
            for (int dz = -POCKET_RADIUS; dz <= POCKET_RADIUS; dz++) {
                for (int dy = 0; dy < POCKET_HEIGHT; dy++) {
                    cursor.set(anchor.getX() + dx, anchor.getY() + dy, anchor.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    if (!state.getFluidState().isEmpty()
                            || level.getBlockEntity(cursor) != null
                            || (!state.isAir() && state.getDestroySpeed(level, cursor) < 0.0F)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean carveSafetyPocket(ServerLevel level, BlockPos anchor) {
        if (!canCarveSafetyPocket(level, anchor)) {
            return false;
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -POCKET_RADIUS; dx <= POCKET_RADIUS; dx++) {
            for (int dz = -POCKET_RADIUS; dz <= POCKET_RADIUS; dz++) {
                for (int dy = 0; dy < POCKET_HEIGHT; dy++) {
                    cursor.set(anchor.getX() + dx, anchor.getY() + dy, anchor.getZ() + dz);
                    if (!level.isEmptyBlock(cursor)) {
                        level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }
        return isSafePosition(level, anchor);
    }

    private static long distanceSq(BlockPos first, BlockPos second) {
        long dx = (long) second.getX() - first.getX();
        long dy = (long) second.getY() - first.getY();
        long dz = (long) second.getZ() - first.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static void scheduleCountdown(ServerLevel level,
                                          ServerPlayer player,
                                          List<ChunkPos> forcedChunks,
                                          BlockPos targetPos,
                                          BlockPos safePos,
                                          Consumer<BlockPos> teleportAction) {
        CountdownTask task = new CountdownTask(level, player, forcedChunks, targetPos, safePos, teleportAction);
        ACTIVE_COUNTDOWNS.add(task);
        try {
            ScheduledFuture<?> future = PRELOAD_EXECUTOR.scheduleAtFixedRate(task, 0L, 1L, TimeUnit.SECONDS);
            task.attachFuture(future);
        } catch (RuntimeException schedulingFailure) {
            ACTIVE_COUNTDOWNS.remove(task);
            releaseChunks(level, forcedChunks);
            throw schedulingFailure;
        }
    }

    private static List<ChunkPos> forceChunks(ServerLevel level, BlockPos center) {
        List<ChunkPos> forced = new ArrayList<>();
        ChunkPos centerChunk = new ChunkPos(center);
        for (int dx = -PRELOAD_RADIUS_CHUNKS; dx <= PRELOAD_RADIUS_CHUNKS; dx++) {
            for (int dz = -PRELOAD_RADIUS_CHUNKS; dz <= PRELOAD_RADIUS_CHUNKS; dz++) {
                ChunkPos chunkPos = new ChunkPos(centerChunk.x + dx, centerChunk.z + dz);
                // Only release tickets that Locate Fixer actually added. Chunks that were
                // already force-loaded may belong to an admin or another mod.
                if (level.setChunkForced(chunkPos.x, chunkPos.z, true)) {
                    forced.add(chunkPos);
                }
            }
        }
        return forced;
    }

    private static void releaseChunks(ServerLevel level, List<ChunkPos> forcedChunks) {
        for (ChunkPos chunkPos : forcedChunks) {
            level.setChunkForced(chunkPos.x, chunkPos.z, false);
        }
    }

    /**
     * Cancels countdowns before their ServerLevel is torn down. Called from the
     * server-stopping event, which already runs on the owning server thread.
     */
    public static void shutdownForServerStop(MinecraftServer server) {
        for (CountdownTask task : List.copyOf(ACTIVE_COUNTDOWNS)) {
            if (task.level.getServer() == server) {
                task.cancelAndRelease("Teleport cancelled because the server is stopping.");
            }
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
        if (pos.getY() <= minY || pos.getY() > maxY) return false;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        cursor.set(pos.getX(), pos.getY() - 1, pos.getZ());
        BlockState belowState = level.getBlockState(cursor);
        if (!belowState.isFaceSturdy(level, cursor, net.minecraft.core.Direction.UP)) return false;
        if (belowState.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) return false;

        for (int dx = -SAFE_AREA_RADIUS; dx <= SAFE_AREA_RADIUS; dx++) {
            for (int dz = -SAFE_AREA_RADIUS; dz <= SAFE_AREA_RADIUS; dz++) {
                for (int dy = 0; dy < SAFE_AREA_HEIGHT; dy++) {
                    cursor.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (!level.isEmptyBlock(cursor)) return false;
                }
            }
        }
        return true;
    }

    private record LandingPlan(BlockPos position, boolean carvePocket) {
    }

    private static final class CountdownTask implements Runnable {
        private final ServerLevel level;
        private final ServerPlayer player;
        private final List<ChunkPos> forcedChunks;
        private final BlockPos targetPos;
        private final BlockPos safePos;
        private final Consumer<BlockPos> teleportAction;
        private int secondsLeft;
        private final AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();
        private final AtomicBoolean tickQueued = new AtomicBoolean();
        private boolean finished;

        private CountdownTask(ServerLevel level,
                              ServerPlayer player,
                              List<ChunkPos> forcedChunks,
                              BlockPos targetPos,
                              BlockPos safePos,
                              Consumer<BlockPos> teleportAction) {
            this.level = level;
            this.player = player;
            this.forcedChunks = forcedChunks;
            this.targetPos = targetPos;
            this.safePos = safePos;
            this.teleportAction = teleportAction;
            this.secondsLeft = COUNTDOWN_SECONDS;
        }

        private void attachFuture(ScheduledFuture<?> future) {
            futureRef.set(future);
        }

        @Override
        public void run() {
            if (!tickQueued.compareAndSet(false, true)) {
                return;
            }

            try {
                level.getServer().execute(() -> {
                    try {
                        tickOnServer();
                    } finally {
                        tickQueued.set(false);
                    }
                });
            } catch (RuntimeException rejected) {
                tickQueued.set(false);
                cancelFuture();
            }
        }

        private void tickOnServer() {
            if (finished) {
                return;
            }

            if (player.isRemoved()) {
                cancelAndRelease("Teleport cancelled.");
                return;
            }

            if (secondsLeft > 0) {
                int displaySeconds = secondsLeft--;
                int elapsed = COUNTDOWN_SECONDS - displaySeconds;
                int forcedEstimate = forcedChunks.isEmpty()
                        ? 0
                        : Math.max(1, (int) Math.round((elapsed / (double) COUNTDOWN_SECONDS) * forcedChunks.size()));
                int percent = Math.max(0, Math.min(100, (int) Math.round((elapsed * 100.0D) / COUNTDOWN_SECONDS)));
                player.sendSystemMessage(Component.literal("Teleporting in " + displaySeconds
                        + "... [preload " + forcedEstimate + "/" + forcedChunks.size() + ", "
                        + percent + "% | target " + safePos.getX() + " " + safePos.getY() + " " + safePos.getZ() + "]"));
                return;
            }

            try {
                if (!player.isRemoved()) {
                    BlockPos finalSafePos = prepareLandingPosition(level, targetPos);
                    sendActionBar(player, Component.literal("✅ Destination ready."));
                    teleportAction.accept(finalSafePos);
                }
            } catch (Exception e) {
                if (!player.isRemoved()) player.sendSystemMessage(Component.literal("Teleport failed: " + e.getMessage()));
            } finally {
                finishAndRelease();
            }
        }

        private void cancelAndRelease(String message) {
            if (finished) {
                return;
            }
            if (!player.isRemoved()) player.sendSystemMessage(Component.literal(message));
            finishAndRelease();
        }

        private void finishAndRelease() {
            if (finished) {
                return;
            }
            finished = true;
            releaseChunks(level, forcedChunks);
            cancelFuture();
            ACTIVE_COUNTDOWNS.remove(this);
        }

        private void cancelFuture() {
            ScheduledFuture<?> f = futureRef.get();
            if (f != null && !f.isCancelled()) f.cancel(false);
        }
    }

    private static void sendActionBar(ServerPlayer player, Component message) {
        player.displayClientMessage(message, true);
    }
}
