package com.thunder.locatefixer.search;

import net.minecraft.core.BlockPos;

/** Overflow-safe block-radius comparisons, including malformed third-party coordinates. */
public final class LocateDistances {
    private static final long MAX_SQUARE_ROOT = 3_037_000_499L;

    private LocateDistances() {}

    public static long horizontalSquared(BlockPos first, BlockPos second) {
        long dx = (long) second.getX() - first.getX();
        long dz = (long) second.getZ() - first.getZ();
        if (Math.abs(dx) > MAX_SQUARE_ROOT || Math.abs(dz) > MAX_SQUARE_ROOT) return Long.MAX_VALUE;
        long xSquared = dx * dx;
        long zSquared = dz * dz;
        return Long.MAX_VALUE - xSquared < zSquared ? Long.MAX_VALUE : xSquared + zSquared;
    }
}
