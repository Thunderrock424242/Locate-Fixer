package com.thunder.locatefixer.integration;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.OperationQueue;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.thunder.locatefixer.schematic.SchematicLocatorRegistry;
import net.minecraft.core.BlockPos;

import java.lang.reflect.Field;
import java.util.Locale;

public class WorldEditHook {

    public static void enable() {
        System.out.println("[LocateFixer] WorldEdit detected. Hooking into schematic pastes...");

        // Simple patch to capture pasted schematics
        WorldEdit.getInstance().getEventBus().register(new Object() {
            @com.sk89q.worldedit.event.extent.EditSessionEvent
            public void onEditSession(EditSession editSession) {
                ClipboardHolder clipboard = extractClipboardHolder(editSession);
                if (clipboard != null) {
                    try {
                        BlockVector3 origin = clipboard.getOrigin();
                        String fileName = extractFileName(clipboard);
                        if (fileName != null) {
                            String id = fileName.replace(".schem", "").toLowerCase(Locale.ROOT);
                            SchematicLocatorRegistry.register(id, (SchematicLocatorRegistry.CustomStructureLocator) new BlockPos(origin.getX(), origin.getY(), origin.getZ()));
                            System.out.println("[LocateFixer] Registered schematic '" + id + "' at " + origin);
                        }
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    private static ClipboardHolder extractClipboardHolder(EditSession session) {
        try {
            Field queueField = EditSession.class.getDeclaredField("operationQueue");
            queueField.setAccessible(true);
            OperationQueue queue = (OperationQueue) queueField.get(session);

            for (Operation op : queue.getOperations()) {
                if (op instanceof ClipboardHolder) {
                    return (ClipboardHolder) op;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String extractFileName(ClipboardHolder clipboard) {
        try {
            Object metadata = clipboard.getClipboard().getOrigin(); // Not reliable
            return clipboard.toString(); // Hack: override this with a known loader or logger
        } catch (Exception ignored) {}
        return null;
    }
}