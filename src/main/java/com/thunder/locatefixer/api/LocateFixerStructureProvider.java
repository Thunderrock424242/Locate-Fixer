package com.thunder.locatefixer.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Optional;

/**
 * Implement this interface in your own structure handler class, then call
 * {@link StructureLocatorRegistry#register(LocateFixerStructureProvider)} during mod setup.
 */
public interface LocateFixerStructureProvider {

    /**
     * @return Unique structure id (example: "mymod:sky_fortress")
     */
    String locateFixerStructureId();

    /**
     * @param level     world to search
     * @param origin    player command position
     * @param maxRadius maximum allowed search radius in blocks
     * @return the located position, or empty if no match is found
     */
    Optional<BlockPos> locateNearest(ServerLevel level, BlockPos origin, int maxRadius);
}
