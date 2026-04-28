package com.thunder.locatefixer.teleport;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.common.Tags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class LocateTeleportHandler {

    private static final int COUNTDOWN_SECONDS = 5;
    private static final int PRELOAD_RADIUS_CHUNKS = 1;
    private static final int SAFE_AREA_RADIUS = 1;
    private static final int SAFE_AREA_HEIGHT = 2;

    private static final int ABOVEGROUND_SEARCH_UP = 16;
    private static final int ABOVEGROUND_SEARCH_DOWN = 12;
    private static final int ABOVEGROUND_SEARCH_HORIZONTAL = 4;

    private static final int UNDERGROUND_SEARCH_UP = 32;
    private static final int UNDERGROUND_SEARCH_DOWN = 8;
    private static final int UNDERGROUND_SEARCH_HORIZONTAL = 6;
    private static final int UNDERGROUND_SURFACE_THRESHOLD = 8;
    private static final int UNDERGROUND_SURFACE_RADIUS = 6;
    private static final int SURFACE_FALLBACK_RADIUS = 8;
    private static final int CONFIRM_TIMEOUT_SECONDS = 30;
    private static final ScheduledExecutorService PRELOAD_EXECUTOR = Executors.newSingleThreadScheduledExecutor(buildThreadFactory());
    private static final TagKey<Biome> CAVE_BIOME_TAG = Tags.Biomes.IS_CAVE;
    private static final Map<UUID, PendingUnsafeTeleport> PENDING_UNSAFE_TELEPORTS = new ConcurrentHashMap<>();

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

        scheduleCountdown(level, player, forcedChunks, targetPos, teleportAction);
    }

    public static BlockPos findSafeTeleportPosition(ServerLevel level, BlockPos targetPos) {
        SearchProfile searchProfile = SearchProfile.ABOVEGROUND;

        if (isSafePosition(level, targetPos)) {
            return targetPos;
        }

        if (isUndergroundTarget(level, targetPos)) {
            searchProfile = SearchProfile.UNDERGROUND;
            BlockPos undergroundSafe = findSafePositionForUndergroundTarget(level, targetPos);
            if (undergroundSafe != null) {
                return undergroundSafe;
            }
        }

        // Prefer a safe spot near the located Y before any global surface fallback.
        BlockPos nearTarget = findNearestSafePositionAroundY(level, targetPos, searchProfile);
        if (nearTarget != null) {
            return nearTarget;
        }

        if (level.getBiome(targetPos).is(CAVE_BIOME_TAG)) {
            BlockPos caveCandidate = findCaveSafePosition(level, targetPos, searchProfile);
            if (!caveCandidate.equals(targetPos) || isSafePosition(level, caveCandidate)) {
                return caveCandidate;
            }
        }

        return findSurfaceSafePosition(level, targetPos);
    }

    private static boolean isUndergroundTarget(ServerLevel level, BlockPos targetPos) {
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, targetPos.getX(), targetPos.getZ());
        return targetPos.getY() <= surfaceY - UNDERGROUND_SURFACE_THRESHOLD;
    }

    private static BlockPos findSafePositionForUndergroundTarget(ServerLevel level, BlockPos targetPos) {
        int columnSurfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, targetPos.getX(), targetPos.getZ());
        int minY = Math.max(level.getMinBuildHeight() + 1, targetPos.getY());
        int maxY = Math.min(level.getMaxBuildHeight() - SAFE_AREA_HEIGHT, columnSurfaceY + 4);

        for (int y = minY; y <= maxY; y++) {
            BlockPos candidate = new BlockPos(targetPos.getX(), y, targetPos.getZ());
            if (isSafePosition(level, candidate)) {
                return candidate;
            }
        }

        for (int radius = 1; radius <= UNDERGROUND_SURFACE_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    int x = targetPos.getX() + dx;
                    int z = targetPos.getZ() + dz;
                    int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    int candidateY = Math.min(level.getMaxBuildHeight() - SAFE_AREA_HEIGHT, surfaceY + 1);
                    BlockPos candidate = new BlockPos(x, candidateY, z);
                    if (isSafePosition(level, candidate)) {
                        return candidate;
                    }
                }
            }
        }

        return null;
    }

    public static BlockPos findSurfaceSafeTeleportPosition(ServerLevel level, BlockPos targetPos) {
        return findSurfaceSafePosition(level, targetPos);
    }

    public static boolean respondToUnsafeTeleport(ServerPlayer player, boolean allowTeleport) {
        PendingUnsafeTeleport pending = PENDING_UNSAFE_TELEPORTS.remove(player.getUUID());
        if (pending == null) {
            return false;
        }

        pending.cancelTimeout();

        ServerLevel level = pending.level();
        level.getServer().execute(() -> {
            try {
                if (player.isRemoved()) {
                    return;
                }

                if (allowTeleport) {
                    player.sendSystemMessage(Component.literal("⚠️ Teleporting to the original position anyway."));
                    pending.teleportAction().accept(pending.unsafeTarget());
                } else {
                    player.sendSystemMessage(Component.literal("❌ Teleport cancelled."));
                }
            } catch (Exception e) {
                if (!player.isRemoved()) player.sendSystemMessage(Component.literal("Teleport failed: " + e.getMessage()));
            } finally {
                releaseChunks(level, pending.forcedChunks());
            }
        });
        return true;
    }

    private static BlockPos findSurfaceSafePosition(ServerLevel level, BlockPos targetPos) {
        SearchProfile searchProfile = isUndergroundTarget(level, targetPos) ? SearchProfile.UNDERGROUND : SearchProfile.ABOVEGROUND;
        BlockPos nearTarget = findNearestSafePositionAroundY(level, targetPos, searchProfile);
        if (nearTarget != null) {
            return nearTarget;
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(targetPos.getX(), level.getMaxBuildHeight(), targetPos.getZ());

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

        BlockPos nearbySurface = findNearestSafeSurfacePosition(level, targetPos, SURFACE_FALLBACK_RADIUS);
        if (nearbySurface != null) {
            return nearbySurface;
        }

        return targetPos; // Fallback to original position if no surface found
    }

    private static BlockPos findNearestSafeSurfacePosition(ServerLevel level, BlockPos targetPos, int maxRadius) {
        int maxY = level.getMaxBuildHeight() - SAFE_AREA_HEIGHT;
        for (int radius = 0; radius <= maxRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    int x = targetPos.getX() + dx;
                    int z = targetPos.getZ() + dz;
                    int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    int candidateY = Math.min(maxY, surfaceY + 1);
                    BlockPos candidate = new BlockPos(x, candidateY, z);
                    if (isSafePosition(level, candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private static BlockPos findNearestSafePositionAroundY(ServerLevel level, BlockPos targetPos, SearchProfile searchProfile) {
        int minY = level.getMinBuildHeight() + 1;
        int maxY = level.getMaxBuildHeight() - SAFE_AREA_HEIGHT;
        int centerY = Math.max(minY, Math.min(maxY, targetPos.getY()));

        int maxRange = Math.max(searchProfile.searchUp(), searchProfile.searchDown());
        for (int vertical = 0; vertical <= maxRange; vertical++) {
            if (vertical == 0) {
                BlockPos sameY = findCaveSafePositionAtYOffset(level, targetPos.atY(centerY), 0, searchProfile.searchHorizontal());
                if (sameY != null) return sameY;
                continue;
            }

            if (vertical <= searchProfile.searchUp() && centerY + vertical <= maxY) {
                BlockPos above = findCaveSafePositionAtYOffset(level, targetPos.atY(centerY), vertical, searchProfile.searchHorizontal());
                if (above != null) return above;
            }

            if (vertical <= searchProfile.searchDown() && centerY - vertical >= minY) {
                BlockPos below = findCaveSafePositionAtYOffset(level, targetPos.atY(centerY), -vertical, searchProfile.searchHorizontal());
                if (below != null) return below;
            }
        }
        return null;
    }

    private static BlockPos findCaveSafePosition(ServerLevel level, BlockPos targetPos, SearchProfile searchProfile) {
        if (isSafePosition(level, targetPos)) {
            return targetPos;
        }

        int maxRange = Math.max(searchProfile.searchUp(), searchProfile.searchDown());
        for (int vertical = 0; vertical <= maxRange; vertical++) {
            if (vertical == 0) {
                BlockPos match = findCaveSafePositionAtYOffset(level, targetPos, 0, searchProfile.searchHorizontal());
                if (match != null) return match;
                continue;
            }

            if (vertical <= searchProfile.searchUp()) {
                BlockPos match = findCaveSafePositionAtYOffset(level, targetPos, vertical, searchProfile.searchHorizontal());
                if (match != null) return match;
            }

            if (vertical <= searchProfile.searchDown()) {
                BlockPos match = findCaveSafePositionAtYOffset(level, targetPos, -vertical, searchProfile.searchHorizontal());
                if (match != null) return match;
            }
        }

        return targetPos;
    }

    private static BlockPos findCaveSafePositionAtYOffset(ServerLevel level, BlockPos targetPos, int yOffset, int horizontalRange) {
        BlockPos base = targetPos.offset(0, yOffset, 0);
        for (int radius = 0; radius <= horizontalRange; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    BlockPos candidate = base.offset(dx, 0, dz);
                    if (isSafePosition(level, candidate)) return candidate;
                }
            }
        }
        return null;
    }

    private static void scheduleCountdown(ServerLevel level,
                                          ServerPlayer player,
                                          List<ChunkPos> forcedChunks,
                                          BlockPos targetPos,
                                          Consumer<BlockPos> teleportAction) {
        CountdownTask task = new CountdownTask(level, player, forcedChunks, targetPos, teleportAction);
        // Store the future in the task via AtomicReference before the first tick can fire
        ScheduledFuture<?> future = PRELOAD_EXECUTOR.scheduleAtFixedRate(task, 0L, 1L, TimeUnit.SECONDS);
        task.attachFuture(future);
    }

    private static List<ChunkPos> forceChunks(ServerLevel level, BlockPos center) {
        List<ChunkPos> forced = new ArrayList<>();
        ChunkPos centerChunk = new ChunkPos(center);
        for (int dx = -PRELOAD_RADIUS_CHUNKS; dx <= PRELOAD_RADIUS_CHUNKS; dx++) {
            for (int dz = -PRELOAD_RADIUS_CHUNKS; dz <= PRELOAD_RADIUS_CHUNKS; dz++) {
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

    private static void requestUnsafeTeleportConfirmation(ServerLevel level,
                                                          ServerPlayer player,
                                                          List<ChunkPos> forcedChunks,
                                                          BlockPos unsafeTarget,
                                                          Consumer<BlockPos> teleportAction) {
        PendingUnsafeTeleport existing = PENDING_UNSAFE_TELEPORTS.remove(player.getUUID());
        if (existing != null) {
            existing.cancelTimeout();
            releaseChunks(existing.level(), existing.forcedChunks());
        }

        ScheduledFuture<?> timeoutFuture = PRELOAD_EXECUTOR.schedule(() -> {
            PendingUnsafeTeleport expired = PENDING_UNSAFE_TELEPORTS.remove(player.getUUID());
            if (expired == null) {
                return;
            }
            level.getServer().execute(() -> {
                if (!player.isRemoved()) {
                    player.sendSystemMessage(Component.literal("⏱ No answer received in 30 seconds. Teleport cancelled."));
                }
                releaseChunks(level, forcedChunks);
            });
        }, CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        PENDING_UNSAFE_TELEPORTS.put(player.getUUID(),
                new PendingUnsafeTeleport(level, forcedChunks, unsafeTarget, teleportAction, timeoutFuture));

        MutableComponent prompt = Component.literal("❗No safe position was found. Would you like to teleport anyway? ");
        MutableComponent yesButton = Component.literal("[Yes]")
                .withStyle(Style.EMPTY
                        .withColor(0x55FF55)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/locatefixerconfirm yes")));
        MutableComponent noButton = Component.literal("[No]")
                .withStyle(Style.EMPTY
                        .withColor(0xFF5555)
                        .withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/locatefixerconfirm no")));

        player.sendSystemMessage(prompt.append(yesButton).append(Component.literal(" ")).append(noButton));
        player.sendSystemMessage(Component.literal("Type /locatefixerconfirm yes or /locatefixerconfirm no. Auto-no in 30 seconds."));
    }

    private static final class CountdownTask implements Runnable {
        private final ServerLevel level;
        private final ServerPlayer player;
        private final List<ChunkPos> forcedChunks;
        private final BlockPos targetPos;
        private final Consumer<BlockPos> teleportAction;
        private int secondsLeft;
        private final AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();

        private CountdownTask(ServerLevel level,
                              ServerPlayer player,
                              List<ChunkPos> forcedChunks,
                              BlockPos targetPos,
                              Consumer<BlockPos> teleportAction) {
            this.level = level;
            this.player = player;
            this.forcedChunks = forcedChunks;
            this.targetPos = targetPos;
            this.teleportAction = teleportAction;
            this.secondsLeft = COUNTDOWN_SECONDS;
        }

        private void attachFuture(ScheduledFuture<?> future) {
            futureRef.set(future);
        }

        @Override
        public void run() {
            if (player.isRemoved()) {
                cancelAndRelease("Teleport cancelled.");
                return;
            }

            if (secondsLeft > 0) {
                int displaySeconds = secondsLeft--;
                level.getServer().execute(() -> player.sendSystemMessage(Component.literal("Teleporting in " + displaySeconds + "...")));
                return;
            }

            level.getServer().execute(() -> {
                boolean waitingForConfirmation = false;
                try {
                    if (!player.isRemoved()) {
                        BlockPos safePos = findSafeTeleportPosition(level, targetPos);
                        BlockPos finalPos = findSafeTeleportPosition(level, safePos);
                        if (!isSafePosition(level, finalPos)) {
                            requestUnsafeTeleportConfirmation(level, player, forcedChunks, targetPos, teleportAction);
                            waitingForConfirmation = true;
                            return;
                        }
                        sendActionBar(player, Component.literal("✅ Destination ready."));
                        teleportAction.accept(finalPos);
                    }
                } catch (Exception e) {
                    if (!player.isRemoved()) player.sendSystemMessage(Component.literal("Teleport failed: " + e.getMessage()));
                } finally {
                    if (!waitingForConfirmation) {
                        releaseChunks(level, forcedChunks);
                    }
                }
            });
            cancelFuture();
        }

        private void cancelAndRelease(String message) {
            level.getServer().execute(() -> {
                if (!player.isRemoved()) player.sendSystemMessage(Component.literal(message));
                releaseChunks(level, forcedChunks);
            });
            cancelFuture();
        }

        private void cancelFuture() {
            ScheduledFuture<?> f = futureRef.get();
            if (f != null && !f.isCancelled()) f.cancel(false);
        }
    }

    private static void sendActionBar(ServerPlayer player, Component message) {
        player.displayClientMessage(message, true);
    }

    private record PendingUnsafeTeleport(ServerLevel level,
                                         List<ChunkPos> forcedChunks,
                                         BlockPos unsafeTarget,
                                         Consumer<BlockPos> teleportAction,
                                         ScheduledFuture<?> timeoutFuture) {
        private void cancelTimeout() {
            if (timeoutFuture != null && !timeoutFuture.isCancelled()) {
                timeoutFuture.cancel(false);
            }
        }
    }

    private enum SearchProfile {
        ABOVEGROUND(ABOVEGROUND_SEARCH_UP, ABOVEGROUND_SEARCH_DOWN, ABOVEGROUND_SEARCH_HORIZONTAL),
        UNDERGROUND(UNDERGROUND_SEARCH_UP, UNDERGROUND_SEARCH_DOWN, UNDERGROUND_SEARCH_HORIZONTAL);

        private final int searchUp;
        private final int searchDown;
        private final int searchHorizontal;

        SearchProfile(int searchUp, int searchDown, int searchHorizontal) {
            this.searchUp = searchUp;
            this.searchDown = searchDown;
            this.searchHorizontal = searchHorizontal;
        }

        private int searchUp() {
            return searchUp;
        }

        private int searchDown() {
            return searchDown;
        }

        private int searchHorizontal() {
            return searchHorizontal;
        }
    }
}
