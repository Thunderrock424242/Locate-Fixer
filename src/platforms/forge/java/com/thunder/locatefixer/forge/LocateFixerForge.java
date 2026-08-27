package com.thunder.locatefixer.forge;

import com.thunder.locatefixer.LocateFixerMod;
import com.thunder.locatefixer.util.CommandErrorFixer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(LocateFixerMod.MOD_ID)
public final class LocateFixerForge {
    public LocateFixerForge() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ForgeConfigBridge.register(modBus);
        modBus.addListener(this::onCommonSetup);
        MinecraftForge.EVENT_BUS.register(this);
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
        if (event.shouldUpdateStaticData()) {
            LocateFixerMod.onServerDataReload();
        }
    }
}
