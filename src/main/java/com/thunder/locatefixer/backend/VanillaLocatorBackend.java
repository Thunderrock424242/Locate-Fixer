package com.thunder.locatefixer.backend;

import com.thunder.locatefixer.api.LocatorRequest;
import com.thunder.locatefixer.api.LocatorResult;
import com.thunder.locatefixer.api.LocatorTargetType;
import com.thunder.locatefixer.search.LocateCancellationToken;
import com.thunder.locatefixer.search.SearchPlan;
import com.thunder.locatefixer.search.SearchStage;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/** Default backend. World access remains in the supplied version-specific operation. */
public final class VanillaLocatorBackend implements LocatorBackend {
    @Override
    public String id() {
        return "locatefixer:vanilla";
    }

    @Override
    public String displayName() {
        return "Minecraft / compatible world generator";
    }

    @Override
    public Set<LocatorTargetType> supportedTargetTypes() {
        return EnumSet.allOf(LocatorTargetType.class);
    }

    @Override
    public Optional<LocatorResult> locate(LocatorRequest request,
                                          SearchPlan plan,
                                          LocateCancellationToken cancellationToken,
                                          LocatorSearchOperation operation) throws Exception {
        for (SearchStage stage : plan.stages()) {
            cancellationToken.throwIfCancelled();
            Optional<LocatorResult> result = operation.search(stage, cancellationToken);
            // A live Minecraft lookup can outlast the cooperative deadline. Never
            // accept a result until cancellation and timeout are checked again.
            cancellationToken.throwIfCancelled();
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }
}
