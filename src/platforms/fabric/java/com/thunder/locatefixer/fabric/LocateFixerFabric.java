package com.thunder.locatefixer.fabric;

import com.thunder.locatefixer.LocateFixerMod;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class LocateFixerFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        FabricConfigBridge.load();
        LocateFixerMod.initialize();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                LocateFixerMod.registerCommands(dispatcher));
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            FabricConfigBridge.load();
            LocateFixerMod.onServerStarting();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(LocateFixerMod::onServerStopping);
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
            if (success) {
                FabricConfigBridge.load();
                LocateFixerMod.onServerDataReload();
            }
        });
    }
}
