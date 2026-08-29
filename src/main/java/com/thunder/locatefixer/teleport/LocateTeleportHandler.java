package com.thunder.locatefixer.teleport;

import com.thunder.locatefixer.config.LocateFixerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Server-thread-owned safe travel with bounded temporary chunk tickets. */
public final class LocateTeleportHandler {
    private static final int SAFE_AREA_RADIUS = 0;
    private static final int SAFE_AREA_HEIGHT = 2;
    private static final int POCKET_RADIUS = 1;
    private static final int POCKET_HEIGHT = 3;
    private static final long AUTHORIZATION_LIFETIME_MS = TimeUnit.MINUTES.toMillis(10);

    private static final ScheduledExecutorService PRELOAD_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(buildThreadFactory());
    private static final Set<CountdownTask> ACTIVE_COUNTDOWNS = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, CountdownTask> ACTIVE_BY_PLAYER = new ConcurrentHashMap<>();
    private static final Map<UUID, TeleportAuthorization> AUTHORIZATIONS = new ConcurrentHashMap<>();

    private LocateTeleportHandler() {
    }

    public static String createCommand(ResourceKey<Level> dimension, BlockPos target) {
        return "/execute in " + dimension.location() + " run tp @s "
                + target.getX() + " " + target.getY() + " " + target.getZ();
    }

    /** Marks coordinates emitted by Locate Unbound as eligible for one preload teleport. */
    public static void authorize(ServerPlayer player, ServerLevel level, BlockPos target) {
        AUTHORIZATIONS.put(player.getUUID(), new TeleportAuthorization(
                level.dimension().location().toString(), target.immutable(),
                System.currentTimeMillis() + AUTHORIZATION_LIFETIME_MS));
    }

    /** A mismatched or expired grant leaves an ordinary vanilla /tp completely untouched. */
    public static boolean consumeAuthorization(ServerPlayer player, ServerLevel level, BlockPos target) {
        TeleportAuthorization authorization = AUTHORIZATIONS.get(player.getUUID());
        if (authorization == null) {
            return false;
        }
        if (authorization.expiresAtMs() < System.currentTimeMillis()) {
            AUTHORIZATIONS.remove(player.getUUID(), authorization);
            return false;
        }
        if (!authorization.dimensionId().equals(level.dimension().location().toString())
                || !authorization.position().equals(target)) {
            return false;
        }
        return AUTHORIZATIONS.remove(player.getUUID(), authorization);
    }

    public static boolean cancelFor(ServerPlayer player) {
        CountdownTask task = ACTIVE_BY_PLAYER.get(player.getUUID());
        if (task == null) {
            return false;
        }
        task.cancelAndRelease("Teleport cancelled.");
        return true;
    }

    public static void startTeleportWithPreload(ServerPlayer player,
                                                ServerLevel level,
                                                BlockPos targetPos,
                                                Consumer<BlockPos> teleportAction) {
        CountdownTask previous = ACTIVE_BY_PLAYER.get(player.getUUID());
        if (previous != null) {
            previous.cancelAndRelease("Previous locate teleport cancelled.");
        }

        ChunkPreload preload = forceChunks(level, targetPos);
        ChunkPos targetChunk = new ChunkPos(targetPos);
        int preloadRadius = LocateFixerConfig.SERVER.teleportPreloadRadiusChunks.get();
        player.sendSystemMessage(Component.literal("Preloading destination chunks around ["
                + targetChunk.x + ", " + targetChunk.z + "] (radius " + preloadRadius
                + ", " + preload.addedTickets().size() + " newly forced)."));
        sendActionBar(player, Component.literal("Chunk preload: warming up destination..."));

        try {
            scheduleCountdown(level, player, preload, targetPos, teleportAction);
        } catch (RuntimeException failure) {
            releaseChunks(level, preload.addedTickets());
            throw failure;
        }
    }

    public static BlockPos findSafeTeleportPosition(ServerLevel level, BlockPos targetPos) {
        return findLandingPlan(level, targetPos).position();
    }

    public static BlockPos findSurfaceSafeTeleportPosition(ServerLevel level, BlockPos targetPos) {
        BlockPos surface = findSurfaceSafePosition(level, targetPos);
        return surface == null ? targetPos : surface;
    }

    private static BlockPos findSurfaceSafePosition(ServerLevel level, BlockPos targetPos) {
        if (level.dimension().equals(Level.NETHER) || level.dimensionType().hasCeiling()) {
            return null;
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                targetPos.getX(), targetPos.getZ());
        BlockPos candidate = new BlockPos(targetPos.getX(), y, targetPos.getZ());
        return isSafePosition(level, candidate) ? candidate : null;
    }

    private static BlockPos findPreferredSurfacePosition(ServerLevel level, BlockPos targetPos) {
        int horizontalRadius = LocateFixerConfig.SERVER.safeHorizontalRadius.get();
        for (int radius = 0; radius <= horizontalRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    BlockPos surface = findSurfaceSafePosition(level, targetPos.offset(dx, 0, dz));
                    if (surface != null) return surface;
                }
            }
        }
        return null;
    }

    private static BlockPos findNearbySafePosition(ServerLevel level, BlockPos targetPos) {
        BlockPos best = null;
        long bestDistanceSq = Long.MAX_VALUE;
        int verticalRange = LocateFixerConfig.SERVER.safeVerticalRange.get();
        int horizontalRadius = LocateFixerConfig.SERVER.safeHorizontalRadius.get();
        int minY = Math.max(level.getMinBuildHeight() + 1, targetPos.getY() - verticalRange);
        int maxY = Math.min(level.getMaxBuildHeight() - SAFE_AREA_HEIGHT, targetPos.getY() + verticalRange);
        for (int y = minY; y <= maxY; y++) {
            for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
                for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
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
        BlockPos surface = findPreferredSurfacePosition(level, targetPos);
        if (surface != null) return new LandingPlan(surface, false);

        BlockPos nearbySafe = findNearbySafePosition(level, targetPos);
        if (isUnderground(level, targetPos)) {
            BlockPos pocketAnchor = findNearestPocketAnchor(level, targetPos);
            if (pocketAnchor != null && (nearbySafe == null
                    || distanceSq(targetPos, pocketAnchor) < distanceSq(targetPos, nearbySafe))) {
                return new LandingPlan(pocketAnchor, true);
            }
        }
        if (nearbySafe != null) return new LandingPlan(nearbySafe, false);
        throw new IllegalStateException("No safe landing position exists near the destination.");
    }

    private static BlockPos prepareLandingPosition(ServerLevel level, BlockPos targetPos) {
        LandingPlan finalPlan = findLandingPlan(level, targetPos);
        BlockPos finalPos = finalPlan.position();
        if (finalPlan.carvePocket() && !carveSafetyPocket(level, finalPos)) {
            BlockPos naturalFallback = findNearbySafePosition(level, targetPos);
            if (naturalFallback != null) {
                finalPos = naturalFallback;
            } else {
                BlockPos surfaceFallback = findPreferredSurfacePosition(level, targetPos);
                if (surfaceFallback != null) finalPos = surfaceFallback;
            }
        }
        if (!isSafePosition(level, finalPos)) {
            throw new IllegalStateException("No safe landing position could be prepared near the destination.");
        }
        return finalPos;
    }

    private static boolean isUnderground(ServerLevel level, BlockPos targetPos) {
        if (level.dimension().equals(Level.NETHER) || level.dimensionType().hasCeiling()) return true;
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                targetPos.getX(), targetPos.getZ());
        return targetPos.getY() + SAFE_AREA_HEIGHT < surfaceY;
    }

    private static BlockPos findNearestPocketAnchor(ServerLevel level, BlockPos targetPos) {
        BlockPos best = null;
        long bestDistanceSq = Long.MAX_VALUE;
        int verticalRange = LocateFixerConfig.SERVER.safeVerticalRange.get();
        int horizontalRadius = LocateFixerConfig.SERVER.safeHorizontalRadius.get();
        int minY = Math.max(level.getMinBuildHeight() + 1, targetPos.getY() - verticalRange);
        int maxY = Math.min(level.getMaxBuildHeight() - POCKET_HEIGHT, targetPos.getY() + verticalRange);
        for (int y = minY; y <= maxY; y++) {
            for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
                for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
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
        if (!floor.isFaceSturdy(level, floorPos, net.minecraft.core.Direction.UP) || !isAllowedFloor(floor)) {
            return false;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -POCKET_RADIUS; dx <= POCKET_RADIUS; dx++) {
            for (int dz = -POCKET_RADIUS; dz <= POCKET_RADIUS; dz++) {
                for (int dy = 0; dy < POCKET_HEIGHT; dy++) {
                    cursor.set(anchor.getX() + dx, anchor.getY() + dy, anchor.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    if (!state.getFluidState().isEmpty() || level.getBlockEntity(cursor) != null
                            || (!state.isAir() && state.getDestroySpeed(level, cursor) < 0.0F)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean carveSafetyPocket(ServerLevel level, BlockPos anchor) {
        if (!canCarveSafetyPocket(level, anchor)) return false;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -POCKET_RADIUS; dx <= POCKET_RADIUS; dx++) {
            for (int dz = -POCKET_RADIUS; dz <= POCKET_RADIUS; dz++) {
                for (int dy = 0; dy < POCKET_HEIGHT; dy++) {
                    cursor.set(anchor.getX() + dx, anchor.getY() + dy, anchor.getZ() + dz);
                    if (!level.isEmptyBlock(cursor)) level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 3);
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

    private static void scheduleCountdown(ServerLevel level, ServerPlayer player, ChunkPreload preload,
                                          BlockPos targetPos, Consumer<BlockPos> teleportAction) {
        CountdownTask task = new CountdownTask(level, player, preload, targetPos, teleportAction);
        ACTIVE_COUNTDOWNS.add(task);
        ACTIVE_BY_PLAYER.put(player.getUUID(), task);
        try {
            ScheduledFuture<?> future = PRELOAD_EXECUTOR.scheduleAtFixedRate(task, 0L, 1L, TimeUnit.SECONDS);
            task.attachFuture(future);
        } catch (RuntimeException failure) {
            ACTIVE_COUNTDOWNS.remove(task);
            ACTIVE_BY_PLAYER.remove(player.getUUID(), task);
            releaseChunks(level, preload.addedTickets());
            throw failure;
        }
    }

    private static ChunkPreload forceChunks(ServerLevel level, BlockPos center) {
        List<ChunkPos> requested = new ArrayList<>();
        List<ChunkPos> added = new ArrayList<>();
        ChunkPos centerChunk = new ChunkPos(center);
        int radius = LocateFixerConfig.SERVER.teleportPreloadRadiusChunks.get();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos chunkPos = new ChunkPos(centerChunk.x + dx, centerChunk.z + dz);
                requested.add(chunkPos);
                if (level.setChunkForced(chunkPos.x, chunkPos.z, true)) added.add(chunkPos);
            }
        }
        return new ChunkPreload(List.copyOf(requested), List.copyOf(added));
    }

    private static void releaseChunks(ServerLevel level, List<ChunkPos> chunks) {
        for (ChunkPos chunkPos : chunks) level.setChunkForced(chunkPos.x, chunkPos.z, false);
    }

    public static void shutdownForServerStop(MinecraftServer server) {
        for (CountdownTask task : List.copyOf(ACTIVE_COUNTDOWNS)) {
            if (task.level.getServer() == server) {
                task.cancelAndRelease("Teleport cancelled because the server is stopping.");
            }
        }
        AUTHORIZATIONS.clear();
    }

    private static ThreadFactory buildThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "LocateUnbound-Preload-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static boolean isSafePosition(ServerLevel level, BlockPos pos) {
        if (pos.getY() <= level.getMinBuildHeight()
                || pos.getY() > level.getMaxBuildHeight() - SAFE_AREA_HEIGHT) return false;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        cursor.set(pos.getX(), pos.getY() - 1, pos.getZ());
        BlockState floor = level.getBlockState(cursor);
        if (!floor.isFaceSturdy(level, cursor, net.minecraft.core.Direction.UP) || !isAllowedFloor(floor)) {
            return false;
        }
        for (int dx = -SAFE_AREA_RADIUS; dx <= SAFE_AREA_RADIUS; dx++) {
            for (int dz = -SAFE_AREA_RADIUS; dz <= SAFE_AREA_RADIUS; dz++) {
                for (int dy = 0; dy < SAFE_AREA_HEIGHT; dy++) {
                    cursor.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (!isAllowedBodySpace(level.getBlockState(cursor))) return false;
                }
            }
        }
        return true;
    }

    private static boolean isAllowedFloor(BlockState state) {
        if (state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)
                && !LocateFixerConfig.SERVER.allowLavaLanding.get()) return false;
        if (state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)
                && !LocateFixerConfig.SERVER.allowWaterLanding.get()) return false;
        if ((state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE))
                && !LocateFixerConfig.SERVER.allowFireLanding.get()) return false;
        if (state.is(Blocks.POWDER_SNOW)
                && !LocateFixerConfig.SERVER.allowPowderSnowLanding.get()) return false;
        return !(state.getBlock() instanceof FallingBlock);
    }

    private static boolean isAllowedBodySpace(BlockState state) {
        if (state.isAir()) return true;
        if (state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
            return LocateFixerConfig.SERVER.allowWaterLanding.get();
        }
        if (state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
            return LocateFixerConfig.SERVER.allowLavaLanding.get();
        }
        if ((state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE))
                && LocateFixerConfig.SERVER.allowFireLanding.get()) return true;
        return state.is(Blocks.POWDER_SNOW) && LocateFixerConfig.SERVER.allowPowderSnowLanding.get();
    }

    private static void sendActionBar(ServerPlayer player, Component message) {
        player.displayClientMessage(message, true);
    }

    private record LandingPlan(BlockPos position, boolean carvePocket) {}
    private record ChunkPreload(List<ChunkPos> requestedChunks, List<ChunkPos> addedTickets) {}
    private record TeleportAuthorization(String dimensionId, BlockPos position, long expiresAtMs) {}

    private static final class CountdownTask implements Runnable {
        private final ServerLevel level;
        private final ServerPlayer player;
        private final ChunkPreload preload;
        private final BlockPos targetPos;
        private final Consumer<BlockPos> teleportAction;
        private final long deadlineNanos;
        private final AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();
        private final AtomicBoolean tickQueued = new AtomicBoolean();
        private int secondsLeft;
        private boolean finished;

        private CountdownTask(ServerLevel level, ServerPlayer player, ChunkPreload preload,
                              BlockPos targetPos, Consumer<BlockPos> teleportAction) {
            this.level = level;
            this.player = player;
            this.preload = preload;
            this.targetPos = targetPos;
            this.teleportAction = teleportAction;
            this.secondsLeft = LocateFixerConfig.SERVER.teleportCountdownEnabled.get()
                    ? LocateFixerConfig.SERVER.teleportCountdownSeconds.get() : 0;
            this.deadlineNanos = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(LocateFixerConfig.SERVER.teleportTimeoutSeconds.get());
        }

        private void attachFuture(ScheduledFuture<?> future) { futureRef.set(future); }

        @Override
        public void run() {
            if (!tickQueued.compareAndSet(false, true)) return;
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
            if (finished) return;
            if (player.isRemoved()) {
                cancelAndRelease("Teleport cancelled.");
                return;
            }
            if (System.nanoTime() >= deadlineNanos) {
                cancelAndRelease("Teleport cancelled because destination chunks did not become ready in time.");
                return;
            }
            int ready = readyChunkCount();
            int total = preload.requestedChunks().size();
            if (secondsLeft > 0) {
                int displaySeconds = secondsLeft--;
                int percent = total == 0 ? 100 : ready * 100 / total;
                player.sendSystemMessage(Component.literal("Teleporting in " + displaySeconds
                        + "... [preload " + ready + "/" + total + ", " + percent + "% | target "
                        + targetPos.getX() + " " + targetPos.getY() + " " + targetPos.getZ() + "]"));
                return;
            }
            if (ready < total) {
                sendActionBar(player, Component.literal("Waiting for destination chunks..."));
                return;
            }
            try {
                BlockPos finalSafePos = prepareLandingPosition(level, targetPos);
                int offsetX = finalSafePos.getX() - targetPos.getX();
                int offsetY = finalSafePos.getY() - targetPos.getY();
                int offsetZ = finalSafePos.getZ() - targetPos.getZ();
                player.sendSystemMessage(Component.literal("Teleport safety scan: "
                        + finalSafePos.getX() + " " + finalSafePos.getY() + " " + finalSafePos.getZ()
                        + " (offset " + offsetX + ", " + offsetY + ", " + offsetZ + ")."));
                sendActionBar(player, Component.literal("Destination ready."));
                teleportAction.accept(finalSafePos);
            } catch (Exception failure) {
                player.sendSystemMessage(Component.literal("Teleport failed: " + failure.getMessage()));
            } finally {
                finishAndRelease();
            }
        }

        private int readyChunkCount() {
            int ready = 0;
            for (ChunkPos chunkPos : preload.requestedChunks()) {
                if (level.hasChunk(chunkPos.x, chunkPos.z)) ready++;
            }
            return ready;
        }

        private void cancelAndRelease(String message) {
            if (finished) return;
            if (!player.isRemoved()) player.sendSystemMessage(Component.literal(message));
            finishAndRelease();
        }

        private void finishAndRelease() {
            if (finished) return;
            finished = true;
            releaseChunks(level, preload.addedTickets());
            cancelFuture();
            ACTIVE_COUNTDOWNS.remove(this);
            ACTIVE_BY_PLAYER.remove(player.getUUID(), this);
        }

        private void cancelFuture() {
            ScheduledFuture<?> future = futureRef.get();
            if (future != null && !future.isCancelled()) future.cancel(false);
        }
    }
}
