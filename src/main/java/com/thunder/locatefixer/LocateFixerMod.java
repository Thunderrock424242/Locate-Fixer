package com.thunder.locatefixer;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.logging.LogUtils;
import com.thunder.locatefixer.command.LocateTeleportCommand;
import com.thunder.locatefixer.command.LocateControlCommand;
import com.thunder.locatefixer.command.LocateFixerCustomStructureCommand;
import com.thunder.locatefixer.command.LocateFixerFeatureCommand;
import com.thunder.locatefixer.command.LocateFixerNearestCommand;
import com.thunder.locatefixer.config.LocateFixerConfig;
import com.thunder.locatefixer.teleport.LocateTeleportHandler;
import com.thunder.locatefixer.util.AsyncLocateHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import com.thunder.locatefixer.index.WorldLocatorIndex;
import org.slf4j.Logger;

public final class LocateFixerMod {
    public static final String MOD_ID = "locatefixer";
    public static final String DISPLAY_NAME = "Locate Fixer";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static boolean initialized;

    private LocateFixerMod() {
    }

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;

        LocateRuntime.initialize();

    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        LocateControlCommand.register(dispatcher);
        LocateTeleportCommand.register(dispatcher);
        LocateFixerNearestCommand.register(dispatcher);
        LocateFixerFeatureCommand.register(dispatcher);
        LocateFixerCustomStructureCommand.register(dispatcher);
    }

    public static void onServerStarting() {
        AsyncLocateHandler.reloadConfig();
        LocateRuntime.reloadConfig();
        LOGGER.info("[LocateUnbound] Server starting - locate engine ready ({} rings configured).",
                LocateFixerConfig.SERVER.locateRings.get().size());
    }

    public static void onServerStopping(MinecraftServer server) {
        LocateTeleportHandler.shutdownForServerStop(server);
        AsyncLocateHandler.shutdownForServerStop();
        LocateRuntime.shutdown();
    }

    public static void onServerDataReload() {
        LocateRuntime.jobs().cancelAll();
        AsyncLocateHandler.clearCaches();
        LocateRuntime.searchHistory().clear();
    }

    public static void onServerDataReload(MinecraftServer server) {
        onServerDataReload();
        for (var level : server.getAllLevels()) {
            WorldLocatorIndex.invalidateForReload(level);
        }
    }

    public static void onServerTick(MinecraftServer server) {
        if (LocateFixerConfig.SERVER.benchmarkEnabled.get()) {
            LocateRuntime.jobs().recordServerTick(System.nanoTime());
        }
        LocateTeleportHandler.tick(server);
    }

    public static void onPlayerDisconnect(ServerPlayer player) {
        LocateRuntime.jobs().cancelFor("player:" + player.getUUID());
        LocateTeleportHandler.cancelFor(player);
    }
}
