package com.thunder.locatefixer.backend;

import com.thunder.locatefixer.api.LocatorResult;
import com.thunder.locatefixer.search.LocateCancellationToken;
import com.thunder.locatefixer.search.SearchStage;

import java.util.Optional;

/** Version-specific world lookup supplied to a selected backend. */
@FunctionalInterface
public interface LocatorSearchOperation {
    Optional<LocatorResult> search(SearchStage stage, LocateCancellationToken cancellationToken) throws Exception;
}
