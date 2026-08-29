package com.thunder.locatefixer.backend;

import com.thunder.locatefixer.api.LocatorRequest;
import com.thunder.locatefixer.api.LocatorResult;
import com.thunder.locatefixer.api.LocatorTargetType;
import com.thunder.locatefixer.search.LocateCancellationToken;
import com.thunder.locatefixer.search.SearchPlan;

import java.util.Optional;
import java.util.Set;

/** Pluggable search executor selected by the orchestration layer. */
public interface LocatorBackend {
    String id();

    String displayName();

    Set<LocatorTargetType> supportedTargetTypes();

    default int priority() {
        return 0;
    }

    default boolean isAvailable() {
        return true;
    }

    default boolean supportsAsyncExecution() {
        return true;
    }

    /** Relative cost from 0 (cheap) to 100 (expensive). */
    default int estimatedSearchCost() {
        return 50;
    }

    Optional<LocatorResult> locate(LocatorRequest request,
                                   SearchPlan plan,
                                   LocateCancellationToken cancellationToken,
                                   LocatorSearchOperation operation) throws Exception;
}
