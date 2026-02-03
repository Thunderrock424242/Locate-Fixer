package com.thunder.locatefixer.integration;

import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import com.thunder.locatefixer.locatefixer;
import com.thunder.locatefixer.schematic.SchematicLocatorRegistry;
import net.minecraft.core.BlockPos;


public class WorldEditHook {
    private static boolean registered = false;

    public static void enable() {
        locatefixer.LOGGER.info("[LocateFixer] WorldEdit detected. Hooking into schematic tracker.");
        if (!registered) {
            WorldEdit.getInstance().getEventBus().register(new WorldEditHook());
            registered = true;
        }
    }

    @Subscribe
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
