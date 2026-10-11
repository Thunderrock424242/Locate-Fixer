package com.thunder.locatefixer.teleport;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NaturalLandingSearchTest {
    @Test
    void undergroundLandingWinsOverDistantSurfaceWithoutChangingBlocks() {
        ReadOnlyWorld world = new ReadOnlyWorld(-64, 320);
        world.platform(0, 12, 0);
        world.platform(0, 90, 0);
        NaturalLandingSearch search = new NaturalLandingSearch(world,
                new NaturalLandingSearch.Position(0, 10, 0), 4, 16, 2, 1, 1000);
        assertEquals(new NaturalLandingSearch.Position(0, 12, 0), search.advance(1000));
        assertEquals(18, world.cells.size());
    }

    @Test
    void rejectsUnknownChunksCliffsHazardsAndHeadroom() {
        ReadOnlyWorld world = new ReadOnlyWorld(-64, 320);
        NaturalLandingSearch.Position target = new NaturalLandingSearch.Position(0, 10, 0);
        world.platform(0, 10, 0);
        assertTrue(NaturalLandingSearch.isSafe(world, target, 2, 1));
        world.cells.put(new NaturalLandingSearch.Position(1, 9, 0), ReadOnlyWorld.AIR);
        assertFalse(NaturalLandingSearch.isSafe(world, target, 2, 1));
        world.platform(0, 10, 0);
        world.cells.put(new NaturalLandingSearch.Position(0, 11, 0), ReadOnlyWorld.SOLID);
        assertFalse(NaturalLandingSearch.isSafe(world, target, 2, 1));
        world.cells.put(new NaturalLandingSearch.Position(0, 11, 0), ReadOnlyWorld.HAZARD);
        assertFalse(NaturalLandingSearch.isSafe(world, target, 2, 1));
        world.cells.put(new NaturalLandingSearch.Position(0, 11, 0), ReadOnlyWorld.AIR);
        world.unloaded = true;
        assertFalse(NaturalLandingSearch.isSafe(world, target, 2, 1));
    }

    @Test
    void rejectsCollisionAndWorldBorderEvenWhenBlockCellsLookSafe() {
        ReadOnlyWorld world = new ReadOnlyWorld(0, 128);
        world.platform(0, 10, 0);
        NaturalLandingSearch.Position target = new NaturalLandingSearch.Position(0, 10, 0);
        assertTrue(NaturalLandingSearch.isSafe(world, target, 2, 1));
        world.collision = true;
        assertFalse(NaturalLandingSearch.isSafe(world, target, 2, 1));
        world.collision = false;
        world.outsideBorder = true;
        assertFalse(NaturalLandingSearch.isSafe(world, target, 2, 1));
    }

    @Test
    void honorsCustomMinimumAndCeilingRatherThanOverworldHeightAssumptions() {
        ReadOnlyWorld world = new ReadOnlyWorld(32, 128);
        world.platform(0, 33, 0);
        world.platform(0, 127, 0);
        assertTrue(NaturalLandingSearch.isSafe(world, new NaturalLandingSearch.Position(0, 33, 0), 2, 1));
        assertFalse(NaturalLandingSearch.isSafe(world, new NaturalLandingSearch.Position(0, 127, 0), 2, 1));
        NaturalLandingSearch search = new NaturalLandingSearch(world,
                new NaturalLandingSearch.Position(0, 31, 0), 2, 4, 2, 1, 200);
        assertEquals(new NaturalLandingSearch.Position(0, 33, 0), search.advance(200));
    }

    @Test
    void scansIncrementallyAndStopsAtTotalBudget() {
        ReadOnlyWorld world = new ReadOnlyWorld(-64, 320);
        NaturalLandingSearch search = new NaturalLandingSearch(world,
                new NaturalLandingSearch.Position(0, 10, 0), 32, 128, 2, 1, 100);
        assertNull(search.advance(7));
        assertEquals(7, search.checkedCandidates());
        assertFalse(search.exhausted());
        assertNull(search.advance(1000));
        assertEquals(100, search.checkedCandidates());
        assertTrue(search.exhausted());
    }

    @Test
    void revalidationRejectsAFloorThatChangedAfterSelection() {
        ReadOnlyWorld world = new ReadOnlyWorld(-64, 320);
        world.platform(0, 10, 0);
        NaturalLandingSearch search = new NaturalLandingSearch(world,
                new NaturalLandingSearch.Position(0, 10, 0), 1, 4, 2, 1, 100);
        NaturalLandingSearch.Position landing = search.advance(1);
        assertNotNull(landing);
        world.cells.put(new NaturalLandingSearch.Position(0, 9, 0), ReadOnlyWorld.HAZARD);
        assertFalse(NaturalLandingSearch.isSafe(world, landing, 2, 1));
    }

    private static final class ReadOnlyWorld implements NaturalLandingSearch.World {
        private static final NaturalLandingSearch.Cell AIR = new NaturalLandingSearch.Cell(false, true, false);
        private static final NaturalLandingSearch.Cell SOLID = new NaturalLandingSearch.Cell(true, false, false);
        private static final NaturalLandingSearch.Cell HAZARD = new NaturalLandingSearch.Cell(true, true, true);
        private final int min;
        private final int max;
        private final Map<NaturalLandingSearch.Position, NaturalLandingSearch.Cell> cells = new HashMap<>();
        private boolean unloaded;
        private boolean collision;
        private boolean outsideBorder;

        private ReadOnlyWorld(int min, int max) { this.min = min; this.max = max; }

        private void platform(int x, int y, int z) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    cells.put(new NaturalLandingSearch.Position(x + dx, y - 1, z + dz), SOLID);
                }
            }
        }

        public int minHeight() { return min; }
        public int maxHeight() { return max; }
        public NaturalLandingSearch.Cell cell(int x, int y, int z) {
            return unloaded ? null : cells.getOrDefault(new NaturalLandingSearch.Position(x, y, z), AIR);
        }
        public boolean collisionFreeAndWithinBorder(NaturalLandingSearch.Position position) {
            return !collision && !outsideBorder;
        }
    }
}
