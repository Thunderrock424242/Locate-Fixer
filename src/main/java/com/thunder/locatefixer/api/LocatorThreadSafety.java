package com.thunder.locatefixer.api;

/** Declares how Locate Unbound may invoke a third-party provider. */
public enum LocatorThreadSafety {
    /** The provider must be invoked through the Minecraft server executor. */
    SERVER_THREAD_ONLY,
    /** The provider has explicitly documented that its search is safe on a worker. */
    WORKER_SAFE
}
