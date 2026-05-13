package com.thunder.locatefixer;

import com.mojang.brigadier.CommandDispatcher;
import com.thunder.locatefixer.command.*;
import com.thunder.locatefixer.api.StructureLocatorRegistry;
import com.thunder.locatefixer.config.LocateFixerConfig;
import com.thunder.locatefixer.integration.WorldEditHook;
import com.thunder.locatefixer.schematic.SchematicLocatorRegistry;
import com.thunder.locatefixer.util.AsyncLocateHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;


// The value here should match an entry in the META-INF/mods.toml file
@Mod(locatefixer.MOD_ID)
public class locatefixer {
    // Define mod id in a common place for everything to reference
    public static final String MOD_ID = "locatefixer";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();

    public locatefixer() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::onConfigLoad);
        modEventBus.addListener(this::onConfigReload);

        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, LocateFixerConfig.SERVER_SPEC);

        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        AsyncLocateHandler.runAsyncTask("schematic-scan", SchematicLocatorRegistry::scanWorldEditSchematicsFolder);
        if (ModList.get().isLoaded("worldedit")) {
            WorldEditHook.enable();
        }

    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("[LocateFixer] Server starting — async locate handler ready ({} rings configured).",
                com.thunder.locatefixer.config.LocateFixerConfig.SERVER.locateRings.get().size());
    }
    /**
     * On register commands.
     *
     * @param event the event
     */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        LocateFixerSchematicCommand.register(dispatcher);
        LocateDimensionCommand.register(dispatcher);
        LocateFixerNearestCommand.register(dispatcher);
        LocateFixerFeatureCommand.register(dispatcher);
        LocateFixerCustomStructureCommand.register(dispatcher);
    }

    private void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == LocateFixerConfig.SERVER_SPEC) {
            AsyncLocateHandler.reloadConfig();
        }
    }

    private void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == LocateFixerConfig.SERVER_SPEC) {
            AsyncLocateHandler.reloadConfig();
        }
    }
}
