package com.thunder.locatefixer;

import com.thunder.locatefixer.backend.LocatorBackendRegistry;
import com.thunder.locatefixer.backend.VanillaLocatorBackend;
import com.thunder.locatefixer.cache.LocatorResultMemoryCache;
import com.thunder.locatefixer.config.LocateFixerConfig;
import com.thunder.locatefixer.integration.LocateIntegrationRegistry;
import com.thunder.locatefixer.job.LocateJobManager;
import com.thunder.locatefixer.search.AdaptiveSearchPlanner;
import com.thunder.locatefixer.search.SearchHistoryTracker;

/** Shared owner for orchestration state used by every Minecraft version and loader. */
public final class LocateRuntime {
    private static final LocatorBackendRegistry BACKENDS = new LocatorBackendRegistry();
    private static final LocateIntegrationRegistry INTEGRATIONS = new LocateIntegrationRegistry();
    private static final AdaptiveSearchPlanner PLANNER = new AdaptiveSearchPlanner();
    private static final SearchHistoryTracker SEARCH_HISTORY = new SearchHistoryTracker();
    private static final LocatorResultMemoryCache PROVIDER_CACHE = new LocatorResultMemoryCache();
    private static final LocateJobManager JOBS = new LocateJobManager(1, 32, 120);
    private static boolean initialized;

    private LocateRuntime() {
    }

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        BACKENDS.register(new VanillaLocatorBackend());
        INTEGRATIONS.detectBuiltIns();
        reloadConfig();
    }

    public static void reloadConfig() {
        JOBS.reconfigure(
                LocateFixerConfig.SERVER.locateThreadCount.get(),
                LocateFixerConfig.SERVER.queueMaxPending.get(),
                LocateFixerConfig.SERVER.searchTimeoutSeconds.get());
    }

    public static LocatorBackendRegistry backends() {
        return BACKENDS;
    }

    public static LocateIntegrationRegistry integrations() {
        return INTEGRATIONS;
    }

    public static AdaptiveSearchPlanner planner() {
        return PLANNER;
    }

    public static SearchHistoryTracker searchHistory() {
        return SEARCH_HISTORY;
    }

    public static LocateJobManager jobs() {
        return JOBS;
    }

    public static LocatorResultMemoryCache providerCache() {
        return PROVIDER_CACHE;
    }

    public static boolean shouldInterceptVanillaLocate() {
        return !LocateFixerConfig.SERVER.enableAsyncLocatorConflictMode.get()
                || !INTEGRATIONS.detected("async_locator_refined");
    }

    public static void shutdown() {
        JOBS.shutdown();
        PROVIDER_CACHE.clear();
    }
}
