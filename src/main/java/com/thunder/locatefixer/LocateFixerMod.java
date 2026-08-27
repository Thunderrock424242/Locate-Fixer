package com.thunder.locatefixer;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.logging.LogUtils;
import com.thunder.locatefixer.command.LocateDimensionCommand;
import com.thunder.locatefixer.command.LocateFixerCustomStructureCommand;
import com.thunder.locatefixer.command.LocateFixerFeatureCommand;
import com.thunder.locatefixer.command.LocateFixerNearestCommand;
import com.thunder.locatefixer.command.LocateFixerSchematicCommand;
import com.thunder.locatefixer.config.LocateFixerConfig;
import com.thunder.locatefixer.integration.WorldEditHook;
import com.thunder.locatefixer.platform.PlatformHooks;
import com.thunder.locatefixer.schematic.SchematicLocatorRegistry;
import com.thunder.locatefixer.teleport.LocateTeleportHandler;
import com.thunder.locatefixer.util.AsyncLocateHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

public final class LocateFixerMod {
    public static final String MOD_ID = "locatefixer";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static boolean initialized;

    private LocateFixerMod() {
    }

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;

        AsyncLocateHandler.runAsyncTask("schematic-scan", SchematicLocatorRegistry::scanWorldEditSchematicsFolder);
        if (PlatformHooks.isModLoaded("worldedit")) {
            WorldEditHook.enable();
        }
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        LocateFixerSchematicCommand.register(dispatcher);
        LocateDimensionCommand.register(dispatcher);
        LocateFixerNearestCommand.register(dispatcher);
        LocateFixerFeatureCommand.register(dispatcher);
        LocateFixerCustomStructureCommand.register(dispatcher);
    }

    public static void onServerStarting() {
        AsyncLocateHandler.reloadConfig();
        LOGGER.info("[LocateFixer] Server starting - async locate handler ready ({} rings configured).",
                LocateFixerConfig.SERVER.locateRings.get().size());
    }

    public static void onServerStopping(MinecraftServer server) {
        LocateTeleportHandler.shutdownForServerStop(server);
        AsyncLocateHandler.shutdownForServerStop();
    }

    public static void onServerDataReload() {
        AsyncLocateHandler.clearCaches();
    }
}
