package com.thunder.locatefixer.neoforge;

import com.thunder.locatefixer.LocateFixerMod;
import com.thunder.locatefixer.util.CommandErrorFixer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod(LocateFixerMod.MOD_ID)
public final class LocateFixerNeoForge {
    public LocateFixerNeoForge(IEventBus modBus, ModContainer modContainer) {
        NeoForgeConfigBridge.register(modBus, modContainer);
        modBus.addListener(this::onCommonSetup);
        NeoForge.EVENT_BUS.register(this);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        LocateFixerMod.initialize();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        LocateFixerMod.registerCommands(event.getDispatcher());
    }

    @SubscribeEvent
    public void onCommand(CommandEvent event) {
        if (CommandErrorFixer.handle(event.getParseResults())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LocateFixerMod.onServerStarting();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LocateFixerMod.onServerStopping(event.getServer());
    }

    @SubscribeEvent
    public void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            LocateFixerMod.onServerDataReload();
        }
    }
}
