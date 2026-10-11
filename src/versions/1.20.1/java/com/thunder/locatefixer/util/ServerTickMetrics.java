package com.thunder.locatefixer.util;

import com.thunder.locatefixer.diagnostics.TickTimingSummary;
import net.minecraft.server.MinecraftServer;

/** Reads the server's own samples when an operator asks for a benchmark. */
public final class ServerTickMetrics {
    private ServerTickMetrics() {
    }

    public static TickTimingSummary sample(MinecraftServer server) {
        return TickTimingSummary.of(server.tickTimes);
    }
}
