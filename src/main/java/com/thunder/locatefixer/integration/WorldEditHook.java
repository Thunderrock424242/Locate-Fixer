package com.thunder.locatefixer.integration;

import com.thunder.locatefixer.LocateFixerMod;

/** Announces the supported, explicit WorldEdit schematic workflow without binding to unstable events. */
public final class WorldEditHook {
    private static boolean announced;

    private WorldEditHook() {
    }

    public static synchronized void enable() {
        if (announced) {
            return;
        }
        announced = true;
        LocateFixerMod.LOGGER.info("[LocateUnbound] WorldEdit detected. After a paste, record its anchor with "
                + "/locate schematic record <name>.");
    }
}
