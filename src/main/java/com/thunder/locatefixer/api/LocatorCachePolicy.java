package com.thunder.locatefixer.api;

/** Controls whether provider results may enter the memory cache and persistent index. */
public enum LocatorCachePolicy {
    NONE(false, false),
    MEMORY(true, false),
    PERSISTENT(true, true);

    private final boolean memory;
    private final boolean persistent;

    LocatorCachePolicy(boolean memory, boolean persistent) {
        this.memory = memory;
        this.persistent = persistent;
    }

    public boolean allowsMemoryCache() {
        return memory;
    }

    public boolean allowsPersistentIndex() {
        return persistent;
    }
}
