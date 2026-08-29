package com.thunder.locatefixer.api;

import net.minecraft.core.BlockPos;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** A normalized result that can be cached, indexed, diagnosed, and safely presented. */
public record LocatorResult(
        LocatorTargetType targetType,
        String targetId,
        String dimensionId,
        BlockPos position,
        String backendId,
        String discoverySource,
        Instant discoveredAt,
        boolean generated,
        boolean verified,
        Map<String, String> metadata
) {
    public LocatorResult {
        Objects.requireNonNull(targetType, "targetType");
        targetId = Objects.requireNonNull(targetId, "targetId");
        dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        position = Objects.requireNonNull(position, "position").immutable();
        backendId = Objects.requireNonNull(backendId, "backendId");
        discoverySource = Objects.requireNonNull(discoverySource, "discoverySource");
        discoveredAt = Objects.requireNonNull(discoveredAt, "discoveredAt");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
