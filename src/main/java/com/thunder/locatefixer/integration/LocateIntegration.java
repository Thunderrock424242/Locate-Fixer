package com.thunder.locatefixer.integration;

import java.util.List;

/** Optional-mod integration descriptor. Implementations must not load classes from absent mods. */
public interface LocateIntegration {
    String id();

    String displayName();

    List<String> candidateModIds();

    String behavior();

    default boolean enabledByDefault() {
        return true;
    }
}
