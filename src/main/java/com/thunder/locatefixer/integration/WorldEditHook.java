package com.thunder.locatefixer.integration;

import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.thunder.locatefixer.locatefixer;
import com.thunder.locatefixer.schematic.SchematicLocatorRegistry;
import net.minecraft.core.BlockPos;
import net.neoforged.bus.api.SubscribeEvent;


public class WorldEditHook {

    public static void enable() {
        locatefixer.LOGGER.info("[LocateFixer] WorldEdit detected. Hooking into schematic tracker.");
        // This assumes you’ve registered this listener somewhere using MinecraftForge.EVENT_BUS
    }

    @SubscribeEvent
    public void onSchematicPasted(EditSessionEvent event) {
        Actor actor = event.getActor();
        if (actor == null || actor.getName() == null) return;

        String schematicId = RecentSchematicTracker.getRecentSchematic(actor.getName());
        BlockPos position = RecentSchematicTracker.getRecentPosition(actor.getName());

        if (schematicId != null && position != null) {
            SchematicLocatorRegistry.registerSchematicPosition(schematicId, position);
            locatefixer.LOGGER.info("[LocateFixer] Registered schematic '{}' at {}", schematicId, position);
            RecentSchematicTracker.clear(actor.getName());
        }
    }

}
