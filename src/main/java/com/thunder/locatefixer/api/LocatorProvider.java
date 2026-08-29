package com.thunder.locatefixer.api;

import com.thunder.locatefixer.search.LocateCancellationToken;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Optional;
import java.util.Set;

/**
 * Public provider contract for structures, biomes, POIs, features, and custom targets.
 * Providers are registered during common setup through {@link LocatorProviderRegistry}.
 */
public interface LocatorProvider {
    String id();

    String displayName();

    LocatorTargetType targetType();

    default Set<String> supportedDimensions() {
        return Set.of();
    }

    default int maximumRadius() {
        return Integer.MAX_VALUE;
    }

    default LocatorCachePolicy cachePolicy() {
        return LocatorCachePolicy.PERSISTENT;
    }

    default LocatorThreadSafety threadSafety() {
        return LocatorThreadSafety.SERVER_THREAD_ONLY;
    }

    default int estimatedSearchCost() {
        return 50;
    }

    default boolean safelyTeleportable() {
        return true;
    }

    Optional<LocatorResult> locate(ServerLevel level,
                                   String targetId,
                                   BlockPos origin,
                                   int maxRadius,
                                   LocateCancellationToken cancellationToken) throws Exception;
}
