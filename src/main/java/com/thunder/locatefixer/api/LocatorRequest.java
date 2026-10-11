package com.thunder.locatefixer.api;

import net.minecraft.core.BlockPos;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable description of a locate request, independent of its selected backend. */
public record LocatorRequest(
        UUID jobId,
        String sourceKey,
        LocatorTargetType targetType,
        String targetId,
        String dimensionId,
        BlockPos origin,
        int maxRadius,
        Instant createdAt
) {
    public LocatorRequest {
        Objects.requireNonNull(jobId, "jobId");
        sourceKey = Objects.requireNonNull(sourceKey, "sourceKey");
        Objects.requireNonNull(targetType, "targetType");
        targetId = Objects.requireNonNull(targetId, "targetId");
        dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        origin = Objects.requireNonNull(origin, "origin").immutable();
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (maxRadius <= 0) {
            throw new IllegalArgumentException("maxRadius must be positive");
        }
    }

    public static LocatorRequest create(String sourceKey,
                                        LocatorTargetType targetType,
                                        String targetId,
                                        String dimensionId,
                                        BlockPos origin,
                                        int maxRadius) {
        return new LocatorRequest(UUID.randomUUID(), sourceKey, targetType, targetId,
                dimensionId, origin, maxRadius, Instant.now());
    }
}
