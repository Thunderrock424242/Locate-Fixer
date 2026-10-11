package com.thunder.locatefixer.util;

import com.thunder.locatefixer.mixin.CommandSourceStackAccessor;
import net.minecraft.commands.CommandSourceStack;

/** Keep synchronous native results when execute/store or return attaches a consumer. */
public final class NativeLocatePolicy {
    private NativeLocatePolicy() {}
    public static boolean hasResultCallback(CommandSourceStack source) {
        // RCON has one synchronous response packet and cannot deliver a deferred locate result.
        if (((com.thunder.locatefixer.mixin.CommandSourceStackAccessor) source).locatefixer$getSource()
                instanceof net.minecraft.server.rcon.RconConsoleSource) return true;
        CommandSourceStack ordinary = source.getServer().createCommandSourceStack();
        return ((CommandSourceStackAccessor) source).locatefixer$getConsumer()
                != ((CommandSourceStackAccessor) ordinary).locatefixer$getConsumer()
                || source.getReturnValueConsumer() != ordinary.getReturnValueConsumer();
    }
}
