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

    private static final String COMMAND_PREFIX = "say [LocateFixer]TP ";
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
        return "/" + COMMAND_PREFIX + dimension.location() + " " + target.getX() + " " + target.getY() + " " + target.getZ();
    }

    public static void onCommand(CommandEvent event) {
        ParseResults<CommandSourceStack> results = event.getParseResults();
        CommandSourceStack source = results.getContext().getSource();

        String input = results.getReader().getString();
        if (input.startsWith("/")) {
            input = input.substring(1);
        }
        if (!input.startsWith(COMMAND_PREFIX)) {
            return;
        }

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        event.setCanceled(true);

        String data = input.substring(COMMAND_PREFIX.length()).trim();
        String[] tokens = data.split(" ");
        if (tokens.length != 4) {
            player.sendSystemMessage(Component.literal("Failed to parse teleport target."));
            return;
        }

        ResourceLocation dimensionId = ResourceLocation.tryParse(tokens[0]);
        if (dimensionId == null) {
            player.sendSystemMessage(Component.literal("Unknown dimension: " + tokens[0]));
            return;
        }

        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
        ServerLevel level = source.getServer().getLevel(dimension);
        if (level == null) {
            player.sendSystemMessage(Component.literal("Dimension not loaded: " + tokens[0]));
            return;
        }

        BlockPos target;
        try {
            int x = Integer.parseInt(tokens[1]);
            int y = Integer.parseInt(tokens[2]);
            int z = Integer.parseInt(tokens[3]);
            target = new BlockPos(x, y, z);
        } catch (NumberFormatException e) {
            player.sendSystemMessage(Component.literal("Invalid coordinates: " + data));
            return;
        }

        startTeleportWithPreload(player, level, target);
    }

    private static void startTeleportWithPreload(ServerPlayer player, ServerLevel level, BlockPos targetPos) {
        List<ChunkPos> forcedChunks = forceChunks(level, targetPos, PRELOAD_RADIUS_CHUNKS);
        player.sendSystemMessage(Component.literal("📦 Preloading destination chunks..."));

        CompletableFuture.runAsync(() -> runCountdown(level, player, targetPos, forcedChunks));
    }

    private static void runCountdown(ServerLevel level, ServerPlayer player, BlockPos targetPos, List<ChunkPos> forcedChunks) {
        try {
            for (int secondsLeft = COUNTDOWN_SECONDS; secondsLeft > 0; secondsLeft--) {
                int finalSecondsLeft = secondsLeft;
                level.getServer().execute(() -> player.sendSystemMessage(
                        Component.literal("Teleporting in " + finalSecondsLeft + "...")
                ));
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
                if (!player.isRemoved()) {
                    player.teleportTo(level, targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5,
                            player.getYRot(), player.getXRot());
                    player.sendSystemMessage(Component.literal("✅ Teleport complete."));
                }
                releaseChunks(level, forcedChunks);
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
