package com.thunder.locatefixer.teleport;

import java.util.Comparator;
import java.util.PriorityQueue;

/** Read-only landing search; Minecraft access remains on the owning server thread. */
final class NaturalLandingSearch {
    record Position(int x, int y, int z) {}
    record Cell(boolean stableFloor, boolean clearBody, boolean hazardous) {}
    interface World {
        int minHeight();
        int maxHeight();
        Cell cell(int x, int y, int z);
        boolean collisionFreeAndWithinBorder(Position position);
    }
    private final World world;
    private final Position origin;
    private final int headroom;
    private final int floorRadius;
    private final int maxChecks;
    private final PriorityQueue<Column> columns;
    private int checked;

    NaturalLandingSearch(World world, Position origin, int horizontalRadius, int verticalRange,
                         int headroom, int floorRadius, int maxChecks) {
        this.world = world;
        this.origin = origin;
        this.headroom = headroom;
        this.floorRadius = floorRadius;
        this.maxChecks = Math.max(0, maxChecks);
        columns = new PriorityQueue<>(Comparator.comparingLong(Column::distanceSquared)
                .thenComparingInt(column -> column.x).thenComparingInt(column -> column.z));
        int minY = (int) Math.max(world.minHeight() + 1L, (long) origin.y() - verticalRange);
        int maxY = (int) Math.min(world.maxHeight() - (long) headroom, (long) origin.y() + verticalRange);
        if (minY > maxY) return;
        for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
            for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
                columns.add(new Column(origin.x() + dx, origin.z() + dz, minY, maxY));
            }
        }
    }

    Position advance(int budget) {
        for (int i = 0; i < budget && !exhausted(); i++) {
            Column column = columns.remove();
            Position candidate = new Position(column.x, column.nextY, column.z);
            if (column.advance()) columns.add(column);
            checked++;
            if (isSafe(world, candidate, headroom, floorRadius)) return candidate;
        }
        return null;
    }

    int checkedCandidates() { return checked; }
    boolean exhausted() { return checked >= maxChecks || columns.isEmpty(); }

    static boolean isSafe(World world, Position position, int headroom, int floorRadius) {
        if (position.y() <= world.minHeight() || (long) position.y() + headroom > world.maxHeight()) return false;
        // A stable platform around the player avoids an immediate step into a cliff or fluid.
        for (int dx = -floorRadius; dx <= floorRadius; dx++) {
            for (int dz = -floorRadius; dz <= floorRadius; dz++) {
                Cell floor = world.cell(position.x() + dx, position.y() - 1, position.z() + dz);
                if (floor == null || !floor.stableFloor() || floor.hazardous()) return false;
                for (int dy = 0; dy < headroom; dy++) {
                    Cell body = world.cell(position.x() + dx, position.y() + dy, position.z() + dz);
                    if (body == null || body.hazardous()
                            || (dx == 0 && dz == 0 && !body.clearBody())) return false;
                }
            }
        }
        return world.collisionFreeAndWithinBorder(position);
    }

    /** Each column supplies ascending vertical distances, keeping the heap bounded by horizontal area. */
    private final class Column {
        private final int x;
        private final int z;
        private final int minY;
        private final int maxY;
        private int below;
        private int above;
        private int nextY;

        private Column(int x, int z, int minY, int maxY) {
            this.x = x;
            this.z = z;
            this.minY = minY;
            this.maxY = maxY;
            nextY = Math.max(minY, Math.min(maxY, origin.y()));
            below = nextY - 1;
            above = nextY + 1;
        }

        private boolean advance() {
            if (below < minY && above > maxY) return false;
            if (below >= minY && (above > maxY
                    || Math.abs((long) below - origin.y()) <= Math.abs((long) above - origin.y()))) {
                nextY = below--;
            } else {
                nextY = above++;
            }
            return true;
        }

        private long distanceSquared() {
            long dx = (long) x - origin.x();
            long dy = (long) nextY - origin.y();
            long dz = (long) z - origin.z();
            return dx * dx + dy * dy + dz * dz;
        }
    }
}
