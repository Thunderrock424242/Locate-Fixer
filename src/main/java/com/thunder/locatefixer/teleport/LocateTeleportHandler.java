package com.thunder.locatefixer.teleport;

import com.thunder.locatefixer.LocateFixerMod;
import com.thunder.locatefixer.config.LocateFixerConfig;
import com.thunder.locatefixer.platform.PlatformHooks;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Bounded, server-tick-owned travel. Landing preparation only reads the world. */
public final class LocateTeleportHandler {
    private static final int MAX_ACTIVE = 8;
    private static final int MAX_TICKETS = 128;
    private static final int CHECKS_PER_TICK = 256;
    private static final int MAX_LANDING_CHECKS = 32_768;
    private static final long AUTHORIZATION_LIFETIME_MS = TimeUnit.MINUTES.toMillis(10);
    // Distance zero requests FULL for exactly this chunk, without forcing block/entity ticking.
    private static final int TICKET_DISTANCE = 0;
    private static final TicketType<UUID> TELEPORT_TICKET =
            TicketType.create("locatefixer_teleport", Comparator.<UUID>naturalOrder());
    private static final Map<MinecraftServer, ServerState> SERVERS = new IdentityHashMap<>();
    private static final Map<UUID, TeleportAuthorization> AUTHORIZATIONS = new HashMap<>();

    private LocateTeleportHandler() {}

    /** @deprecated Ordinary /tp is no longer intercepted. Use requestTeleport for safe locate travel. */
    @Deprecated
    public static String createCommand(ResourceKey<Level> dimension, BlockPos target) {
        return "/execute in " + dimension.location() + " run tp @s "
                + target.getX() + " " + target.getY() + " " + target.getZ();
    }

    /** @deprecated Compatibility grant only; safe travel now requires an explicit locate teleport request. */
    @Deprecated
    public static void authorize(ServerPlayer player, ServerLevel level, BlockPos target) {
        requireServerThread(level.getServer());
        AUTHORIZATIONS.put(player.getUUID(), new TeleportAuthorization(level.getServer(),
                level.dimension(), target.immutable(), System.currentTimeMillis() + AUTHORIZATION_LIFETIME_MS));
    }

    /** @deprecated Retained for integrations; vanilla /tp no longer consumes these grants. */
    @Deprecated
    public static boolean consumeAuthorization(ServerPlayer player, ServerLevel level, BlockPos target) {
        requireServerThread(level.getServer());
        TeleportAuthorization grant = AUTHORIZATIONS.get(player.getUUID());
        if (grant == null) return false;
        if (grant.expiresAtMs() < System.currentTimeMillis()) {
            AUTHORIZATIONS.remove(player.getUUID());
            return false;
        }
        return grant.server() == level.getServer() && grant.dimension().equals(level.dimension())
                && grant.position().equals(target) && AUTHORIZATIONS.remove(player.getUUID(), grant);
    }

    /** Player-only bridge used after an explicitly requested locate operation succeeds. */
    public static boolean requestTeleport(CommandSourceStack source, ServerLevel level, BlockPos target) {
        if (!source.hasPermission(2) || !(source.getEntity() instanceof ServerPlayer player)
                || source.getServer() != level.getServer()) {
            source.sendFailure(Component.literal("Safe locate teleport requires a player with locate permission."));
            return false;
        }
        return begin(player, level, target, safe -> {
            PlatformHooks.TeleportTarget adjusted = PlatformHooks.adjustTeleport(player,
                    safe.getX() + 0.5D, safe.getY(), safe.getZ() + 0.5D);
            if (!adjusted.allowed()) throw new IllegalStateException("Teleport cancelled by another mod.");
            if (!Double.isFinite(adjusted.x()) || !Double.isFinite(adjusted.y()) || !Double.isFinite(adjusted.z())) {
                throw new IllegalStateException("Another mod supplied an invalid destination.");
            }
            LoadedWorld world = new LoadedWorld(level, player);
            BlockPos adjustedPos = BlockPos.containing(adjusted.x(), adjusted.y(), adjusted.z());
            if (!Level.isInSpawnableBounds(adjustedPos) || adjusted.y() != adjustedPos.getY()
                    || !NaturalLandingSearch.isSafe(world, position(adjustedPos), world.headroom, world.floorRadius)
                    || !world.collisionFreeAt(adjusted.x(), adjusted.y(), adjusted.z())) {
                throw new IllegalStateException("Another mod changed the destination to an unsafe position.");
            }
            if (!source.hasPermission(2) || !player.teleportTo(level, adjusted.x(), adjusted.y(), adjusted.z(),
                    Set.of(), player.getYRot(), player.getXRot())) throw new IllegalStateException("The teleport was rejected.");
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0.0F;
            player.setOnGround(true);
        });
    }

    /** Compatibility entry point. The supplied action runs only after immediate destination revalidation. */
    public static void startTeleportWithPreload(ServerPlayer player, ServerLevel level, BlockPos target,
                                                Consumer<BlockPos> teleportAction) {
        begin(player, level, target, teleportAction);
    }

    private static boolean begin(ServerPlayer player, ServerLevel level, BlockPos target,
                                 Consumer<BlockPos> teleportAction) {
        MinecraftServer server = level.getServer();
        requireServerThread(server);
        if (player.getServer() != server || player.isRemoved() || !Level.isInSpawnableBounds(target)) {
            player.sendSystemMessage(Component.literal("Teleport failed: the destination or player is unavailable."));
            return false;
        }
        ServerState state = SERVERS.computeIfAbsent(server, ignored -> new ServerState());
        if (state.active.containsKey(player.getUUID())) {
            player.sendSystemMessage(Component.literal("A locate teleport is already preparing. Use /locate cancel first."));
            return false;
        }
        TeleportLeaseLedger.Lease lease = null;
        Preparation task = null;
        try {
            LoadedWorld world = new LoadedWorld(level, player);
            int radius = Math.max(1, Math.min(32, LocateFixerConfig.SERVER.safeHorizontalRadius.get()));
            List<Long> chunks = chunksForArea(target, radius + world.floorRadius);
            lease = state.ledger.acquire(player.getUUID(), chunks, new TeleportLeaseLedger.Tickets() {
                public void add(long chunk, UUID operation) {
                    level.getChunkSource().addRegionTicket(TELEPORT_TICKET, new ChunkPos(chunk), TICKET_DISTANCE, operation);
                }
                public void remove(long chunk, UUID operation) {
                    level.getChunkSource().removeRegionTicket(TELEPORT_TICKET, new ChunkPos(chunk), TICKET_DISTANCE, operation);
                }
            });
            if (lease == null) {
                player.sendSystemMessage(Component.literal("Teleport preparation is busy. Try again shortly."));
                return false;
            }
            task = new Preparation(state, player, level, target.immutable(), teleportAction,
                    lease, chunks, world, radius);
            state.active.put(player.getUUID(), task);
            player.sendSystemMessage(Component.literal("Preparing a safe teleport destination..."));
            return true;
        } catch (RuntimeException failure) {
            if (task != null) state.active.remove(player.getUUID(), task);
            if (lease != null) closeLease(lease);
            player.sendSystemMessage(Component.literal("Teleport failed: destination preparation could not start."));
            LocateFixerMod.LOGGER.warn("Safe teleport preparation failed", failure);
            return false;
        }
    }

    /** Called once per server tick by each loader; no worker waits, scheduled executors, or chunk joins. */
    public static void tick(MinecraftServer server) {
        requireServerThread(server);
        ServerState state = SERVERS.get(server);
        if (state == null) return;
        retryCleanup(state);
        if (state.active.isEmpty()) return;
        List<Preparation> tasks = List.copyOf(state.active.values());
        int budget = Math.max(1, CHECKS_PER_TICK / tasks.size());
        for (Preparation task : tasks) {
            try { task.tick(budget); }
            catch (RuntimeException failure) {
                LocateFixerMod.LOGGER.warn("Safe teleport preparation failed", failure);
                task.cancel("Teleport failed while checking the destination.");
            }
        }
    }

    public static boolean cancelFor(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        requireServerThread(server);
        AUTHORIZATIONS.remove(player.getUUID());
        ServerState state = SERVERS.get(server);
        Preparation task = state == null ? null : state.active.get(player.getUUID());
        if (task == null) return false;
        task.cancel("Teleport cancelled.");
        return true;
    }

    public static void shutdownForServerStop(MinecraftServer server) {
        requireServerThread(server);
        ServerState state = SERVERS.remove(server);
        if (state != null) {
            for (Preparation task : List.copyOf(state.active.values())) {
                try { task.cancel("Teleport cancelled because the server is stopping."); }
                catch (RuntimeException failure) { LocateFixerMod.LOGGER.warn("Could not notify player of cancelled teleport", failure); }
            }
            retryCleanup(state);
        }
        AUTHORIZATIONS.entrySet().removeIf(entry -> entry.getValue().server() == server);
    }

    /** Loaded-only, read-only compatibility query; throws when no natural safe landing is available. */
    public static BlockPos findSafeTeleportPosition(ServerLevel level, BlockPos target) {
        requireServerThread(level.getServer());
        LoadedWorld world = new LoadedWorld(level, null);
        int radius = Math.max(1, Math.min(32, LocateFixerConfig.SERVER.safeHorizontalRadius.get()));
        NaturalLandingSearch search = new NaturalLandingSearch(world, position(target), radius,
                LocateFixerConfig.SERVER.safeVerticalRange.get(), world.headroom, world.floorRadius, MAX_LANDING_CHECKS);
        NaturalLandingSearch.Position safe = search.advance(MAX_LANDING_CHECKS);
        if (safe != null) return blockPos(safe);
        throw new IllegalStateException("No naturally safe loaded position exists near the destination.");
    }

    /** @deprecated Surface-only query retained for callers; it never loads chunks or modifies the world. */
    @Deprecated
    public static BlockPos findSurfaceSafeTeleportPosition(ServerLevel level, BlockPos target) {
        requireServerThread(level.getServer());
        LoadedWorld world = new LoadedWorld(level, null);
        NaturalLandingSearch.Position safe = surfaceAt(world, target.getX(), target.getZ());
        if (safe != null) return blockPos(safe);
        throw new IllegalStateException("No naturally safe loaded surface exists at the destination.");
    }

    private static NaturalLandingSearch.Position surfaceAt(LoadedWorld world, int x, int z) {
        if (world.level.dimensionType().hasCeiling()) return null;
        LevelChunk chunk = world.fullChunk(x >> 4, z >> 4);
        if (chunk == null) return null;
        NaturalLandingSearch.Position candidate = new NaturalLandingSearch.Position(x,
                chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1, z);
        return NaturalLandingSearch.isSafe(world, candidate, world.headroom, world.floorRadius) ? candidate : null;
    }

    private static List<Long> chunksForArea(BlockPos target, int radius) {
        List<Long> chunks = new ArrayList<>();
        for (int x = (target.getX() - radius) >> 4; x <= (target.getX() + radius) >> 4; x++) {
            for (int z = (target.getZ() - radius) >> 4; z <= (target.getZ() + radius) >> 4; z++) {
                chunks.add(ChunkPos.asLong(x, z));
            }
        }
        return List.copyOf(chunks);
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Safe teleport must run on the server thread.");
    }
    private static void closeLease(TeleportLeaseLedger.Lease lease) {
        try { lease.close(); }
        catch (RuntimeException failure) { LocateFixerMod.LOGGER.error("Could not release every teleport chunk ticket", failure); }
    }
    private static void retryCleanup(ServerState state) {
        try { state.ledger.retryClosingLeases(); }
        catch (RuntimeException failure) { LocateFixerMod.LOGGER.error("Could not release pending teleport tickets", failure); }
    }
    private static NaturalLandingSearch.Position position(BlockPos pos) {
        return new NaturalLandingSearch.Position(pos.getX(), pos.getY(), pos.getZ());
    }
    private static BlockPos blockPos(NaturalLandingSearch.Position pos) { return new BlockPos(pos.x(), pos.y(), pos.z()); }
    private record TeleportAuthorization(MinecraftServer server, ResourceKey<Level> dimension,
                                         BlockPos position, long expiresAtMs) {}
    private static final class ServerState {
        private final TeleportLeaseLedger ledger = new TeleportLeaseLedger(MAX_ACTIVE, MAX_TICKETS);
        private final Map<UUID, Preparation> active = new LinkedHashMap<>();
    }

    private static final class Preparation {
        private final ServerState state;
        private final ServerPlayer player;
        private final ServerLevel originalLevel;
        private final ServerLevel level;
        private final Consumer<BlockPos> action;
        private final TeleportLeaseLedger.Lease lease;
        private final List<Long> chunks;
        private final LoadedWorld world;
        private final NaturalLandingSearch search;
        private final List<BlockPos> surfaceColumns;
        private final long deadline;
        private final long countdownNanos;
        private NaturalLandingSearch.Position landing;
        private long countdownDeadline;
        private long lastProgress;
        private int surfaceIndex;
        private boolean finished;

        private Preparation(ServerState state, ServerPlayer player, ServerLevel level, BlockPos target,
                            Consumer<BlockPos> action, TeleportLeaseLedger.Lease lease, List<Long> chunks,
                            LoadedWorld world, int radius) {
            this.state = state;
            this.player = player;
            this.originalLevel = player.serverLevel();
            this.level = level;
            this.action = action;
            this.lease = lease;
            this.chunks = chunks;
            this.world = world;
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(
                    Math.max(5, Math.min(300, LocateFixerConfig.SERVER.teleportTimeoutSeconds.get())));
            countdownNanos = LocateFixerConfig.SERVER.teleportCountdownEnabled.get()
                    ? TimeUnit.SECONDS.toNanos(Math.max(0, Math.min(60, LocateFixerConfig.SERVER.teleportCountdownSeconds.get()))) : 0L;
            surfaceColumns = new ArrayList<>();
            if (!level.dimensionType().hasCeiling()) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) surfaceColumns.add(target.offset(dx, 0, dz));
                }
                surfaceColumns.sort(Comparator.comparingLong(column -> {
                    long dx = (long) column.getX() - target.getX();
                    long dz = (long) column.getZ() - target.getZ();
                    return dx * dx + dz * dz;
                }));
            }
            search = new NaturalLandingSearch(world, position(target), radius,
                    Math.max(4, Math.min(128, LocateFixerConfig.SERVER.safeVerticalRange.get())),
                    world.headroom, world.floorRadius, MAX_LANDING_CHECKS - surfaceColumns.size());
        }

        private void tick(int budget) {
            if (finished) return;
            if (player.isRemoved() || player.serverLevel() != originalLevel
                    || level.getServer().getPlayerList().getPlayer(player.getUUID()) != player) {
                cancel("Teleport cancelled because the player moved dimensions or disconnected.");
                return;
            }
            long now = System.nanoTime();
            if (landing == null && now >= deadline) {
                cancel("Teleport timed out while preparing a safe destination.");
                return;
            }
            int ready = 0;
            for (long packed : chunks) {
                ChunkPos chunk = new ChunkPos(packed);
                if (world.fullChunk(chunk.x, chunk.z) != null) ready++;
            }
            if (ready != chunks.size()) {
                if (landing != null) {
                    cancel("Teleport cancelled because the destination chunks are no longer ready.");
                    return;
                }
                progress(now, "Loading destination chunks: " + ready + "/" + chunks.size());
                return;
            }
            if (landing == null) {
                int checkedBefore = search.checkedCandidates();
                landing = search.advance(budget);
                int remaining = budget - (search.checkedCandidates() - checkedBefore);
                if (landing == null && search.exhausted()) {
                    for (int i = 0; i < remaining && surfaceIndex < surfaceColumns.size(); i++) {
                        BlockPos column = surfaceColumns.get(surfaceIndex++);
                        landing = surfaceAt(world, column.getX(), column.getZ());
                        if (landing != null) break;
                    }
                    if (landing == null && surfaceIndex == surfaceColumns.size()) {
                        cancel("No naturally safe landing was found near the destination. Teleport cancelled.");
                        return;
                    }
                }
                if (landing == null) { progress(now, "Checking for a safe landing..."); return; }
                countdownDeadline = now + countdownNanos;
            }
            if (now < countdownDeadline) {
                long seconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(countdownDeadline - now) + 1);
                progress(now, "Teleporting in " + seconds + "...");
                return;
            }
            // Player size, blocks, entities and border can change during loading or a countdown.
            LoadedWorld finalWorld = new LoadedWorld(level, player);
            if (!NaturalLandingSearch.isSafe(finalWorld, landing, finalWorld.headroom, finalWorld.floorRadius)) {
                cancel("Teleport cancelled because the destination is no longer safe.");
                return;
            }
            try {
                action.accept(blockPos(landing));
                player.sendSystemMessage(Component.literal("Teleported safely near "
                        + landing.x() + " " + landing.y() + " " + landing.z() + "."));
            } catch (RuntimeException failure) {
                player.sendSystemMessage(Component.literal("Teleport failed: " + failure.getMessage()));
            } finally { finish(); }
        }

        private void progress(long now, String text) {
            if (now - lastProgress >= TimeUnit.SECONDS.toNanos(1)) {
                player.displayClientMessage(Component.literal(text), true);
                lastProgress = now;
            }
        }
        private void cancel(String text) {
            if (finished) return;
            try { if (!player.isRemoved()) player.sendSystemMessage(Component.literal(text)); }
            finally { finish(); }
        }
        private void finish() {
            if (finished) return;
            finished = true;
            state.active.remove(player.getUUID(), this);
            closeLease(lease);
        }
    }

    /** Absent data is never air. Collision shapes and neighborhood reads use only ready chunks. */
    private static final class LoadedWorld implements NaturalLandingSearch.World, CollisionGetter {
        private final ServerLevel level;
        private final ServerPlayer player;
        private final AABB standingBox;
        private final int headroom;
        private final int floorRadius;

        private LoadedWorld(ServerLevel level, ServerPlayer player) {
            this.level = level;
            this.player = player;
            standingBox = player == null ? new AABB(0.2D, 0.0D, 0.2D, 0.8D, 1.8D, 0.8D)
                    : player.getDimensions(Pose.STANDING).makeBoundingBox(0.5D, 0.0D, 0.5D);
            headroom = (int) Math.ceil(standingBox.getYsize());
            floorRadius = Math.max(1, (int) Math.ceil(Math.max(standingBox.getXsize(), standingBox.getZsize()) / 2.0D + 0.5D));
            if (headroom < 1 || headroom > 8 || floorRadius > 4) {
                throw new IllegalStateException("Player dimensions exceed the bounded safe landing area.");
            }
        }

        private LevelChunk fullChunk(int x, int z) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
            return chunk != null && chunk.getFullStatus().isOrAfter(FullChunkStatus.FULL) ? chunk : null;
        }
        public int minHeight() { return level.getMinBuildHeight(); }
        public int maxHeight() {
            return level.dimensionType().hasCeiling()
                    ? Math.min(level.getMaxBuildHeight(), minHeight() + level.dimensionType().logicalHeight())
                    : level.getMaxBuildHeight();
        }
        public NaturalLandingSearch.Cell cell(int x, int y, int z) {
            LevelChunk chunk = fullChunk(x >> 4, z >> 4);
            if (chunk == null) return null;
            BlockPos pos = new BlockPos(x, y, z);
            BlockState block = chunk.getBlockState(pos);
            boolean hazardous = isHazardous(block);
            return new NaturalLandingSearch.Cell(!hazardous && !(block.getBlock() instanceof FallingBlock)
                    && block.isFaceSturdy(this, pos, Direction.UP),
                    block.getCollisionShape(this, pos, player == null ? CollisionContext.empty() : CollisionContext.of(player)).isEmpty(),
                    hazardous);
        }
        public boolean collisionFreeAndWithinBorder(NaturalLandingSearch.Position pos) {
            return collisionFreeAt(pos.x() + 0.5D, pos.y(), pos.z() + 0.5D);
        }
        private boolean collisionFreeAt(double x, double y, double z) {
            AABB box = standingBox.move(x - 0.5D, y, z - 0.5D);
            if (box.minY <= minHeight() || box.maxY > maxHeight() || !getWorldBorder().isWithinBounds(box)) return false;
            // Vanilla collision iteration reads one extra block around the bounding box.
            for (int cx = ((int) Math.floor(box.minX - 1.0E-7D) - 1) >> 4;
                 cx <= ((int) Math.floor(box.maxX + 1.0E-7D) + 1) >> 4; cx++) {
                for (int cz = ((int) Math.floor(box.minZ - 1.0E-7D) - 1) >> 4;
                     cz <= ((int) Math.floor(box.maxZ + 1.0E-7D) + 1) >> 4; cz++) {
                    if (fullChunk(cx, cz) == null) return false;
                }
            }
            return noCollision(player, box);
        }
        public BlockGetter getChunkForCollisions(int x, int z) { return fullChunk(x, z); }
        public WorldBorder getWorldBorder() { return level.getWorldBorder(); }
        public List<VoxelShape> getEntityCollisions(Entity entity, AABB box) { return level.getEntityCollisions(entity, box); }
        public BlockState getBlockState(BlockPos pos) {
            LevelChunk chunk = fullChunk(pos.getX() >> 4, pos.getZ() >> 4);
            return chunk == null ? Blocks.BEDROCK.defaultBlockState() : chunk.getBlockState(pos);
        }
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        public BlockEntity getBlockEntity(BlockPos pos) {
            LevelChunk chunk = fullChunk(pos.getX() >> 4, pos.getZ() >> 4);
            return chunk == null ? null : chunk.getBlockEntities().get(pos);
        }
        public int getHeight() { return maxHeight() - minHeight(); }
        public int getMinBuildHeight() { return minHeight(); }
    }

    private static boolean isHazardous(BlockState state) {
        return !state.getFluidState().isEmpty() || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS)
                || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.WITHER_ROSE) || state.is(Blocks.POINTED_DRIPSTONE) || state.is(Blocks.SCAFFOLDING)
                || state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_PORTAL) || state.is(Blocks.END_GATEWAY);
    }
}
