package com.thunder.locatefixer.index;

import com.thunder.locatefixer.api.LocatorResult;
import com.thunder.locatefixer.api.LocatorTargetType;
import net.minecraft.core.BlockPos;

import java.time.Instant;
import java.util.Objects;

/** Persistent, versioned representation of one discovered location. */
public record LocatorIndexEntry(
        LocatorTargetType targetType,
        String targetId,
        String dimensionId,
        BlockPos position,
        String discoverySource,
        long discoveredAtMs,
        long lastVerifiedAtMs,
        boolean generated,
        boolean verified,
        String backendId
) {
    public LocatorIndexEntry {
        Objects.requireNonNull(targetType, "targetType");
        targetId = bounded(targetId, 512);
        dimensionId = bounded(dimensionId, 256);
        position = Objects.requireNonNull(position, "position").immutable();
        discoverySource = bounded(discoverySource, 256);
        backendId = bounded(backendId, 256);
    }

    public static LocatorIndexEntry fromResult(LocatorResult result) {
        long discovered = result.discoveredAt().toEpochMilli();
        return new LocatorIndexEntry(result.targetType(), result.targetId(), result.dimensionId(),
                result.position(), result.discoverySource(), discovered,
                result.verified() ? System.currentTimeMillis() : 0L,
                result.generated(), result.verified(), result.backendId());
    }

    public LocatorResult toResult() {
        return new LocatorResult(targetType, targetId, dimensionId, position, backendId,
                discoverySource, Instant.ofEpochMilli(discoveredAtMs), generated, verified,
                java.util.Map.of("index", "persistent"));
    }

    public String deduplicationKey() {
        return targetType.name() + '|' + targetId + '|' + dimensionId + '|'
                + position.getX() + '|' + position.getY() + '|' + position.getZ();
    }

    private static String bounded(String value, int maxLength) {
        String safe = Objects.requireNonNull(value, "value");
        if (safe.isBlank() || safe.length() > maxLength) {
            throw new IllegalArgumentException("Invalid index string length");
        }
        return safe;
    }
}
