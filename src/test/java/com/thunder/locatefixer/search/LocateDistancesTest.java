package com.thunder.locatefixer.search;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocateDistancesTest {
    @Test
    void normalCoordinatesRetainExactSquaredDistance() {
        assertEquals(25L, LocateDistances.horizontalSquared(new BlockPos(-2, -64, -2),
                new BlockPos(1, 320, 2)));
    }

    @Test
    void extremeAxisAndDiagonalDistancesSaturate() {
        assertEquals(Long.MAX_VALUE, LocateDistances.horizontalSquared(
                new BlockPos(Integer.MIN_VALUE, 0, 0), new BlockPos(Integer.MAX_VALUE, 0, 0)));
        assertEquals(Long.MAX_VALUE, LocateDistances.horizontalSquared(new BlockPos(30_000_000, 0, 30_000_000),
                new BlockPos(Integer.MIN_VALUE, 0, Integer.MIN_VALUE)));
    }
}
