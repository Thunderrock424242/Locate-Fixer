package com.thunder.locatefixer.util;

import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSourceStack;

/** Callback-bearing native commands require a synchronous result. */
public final class NativeLocatePolicy {
    private NativeLocatePolicy() {}
    public static boolean hasResultCallback(CommandSourceStack source) {
        // RCON has one synchronous response packet and cannot deliver a deferred locate result.
        if (((com.thunder.locatefixer.mixin.CommandSourceStackAccessor) source).locatefixer$getSource()
                instanceof net.minecraft.server.rcon.RconConsoleSource) return true;
        return source.callback() != CommandResultCallback.EMPTY;
    }
}
