package com.thunder.locatefixer.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;

/**
 * Legacy entry point retained for binary/source compatibility.
 *
 * <p>The /locate nearest command tree was removed as it is no longer used.
 */
public final class LocateFixerNearestCommand {

    private LocateFixerNearestCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Intentionally no-op: /locate nearest has been removed.
    }
}
