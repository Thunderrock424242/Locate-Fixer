# Locate Unbound configuration

Locate Unbound keeps the established filename `config/locatefixer-server.toml`. Existing keys remain accepted; newly generated Fabric configs group the 4.0 options by subsystem. Forge and NeoForge retain legacy paths for existing settings and add the new category paths.

Fabric reloads this file on server startup and `/reload`. Forge and NeoForge use their loader config reload events. A restart is the reliable fallback on every loader.

## Search

| Key | Default | Safe range | Purpose |
| --- | ---: | ---: | --- |
| `locateRings` | `[6400, 16000, 32000, 64000, 128000, 256000]` | positive integers | Fallback radii and absolute search limit. |
| `locateThreadCount` | `1` | 1–8 | Bounded locate worker count. World access is still marshalled to the server thread. |
| `biomeSampleRadiusMultiplier` | `1.5` | 1.0–8.0 | Biome sample-radius scaling. |
| `biomeSampleStepMultiplier` | `1.75` | 1.0–8.0 | Biome step scaling. |
| `adaptiveSearchEnabled` | `true` | boolean | Uses distance history to skip already-covered rings. Disable to use configured rings exactly. |
| `enableFeatureLocateCommand` | `false` | boolean | Enables operator-only feature-capable-biome lookup. It does not verify an exact placed feature. |

The legacy nearest toggle remains `enableNearestCommand = false`. POI range remains `poiSearchRadius = 256` with a safe range of 16–4096.

## Queue

| Key | Default | Safe range | Purpose |
| --- | ---: | ---: | --- |
| `queueMaxPending` / `queue.maxPending` | `32` | 1–1024 | Pending job capacity. New requests are rejected cleanly when full. |
| `searchTimeoutSeconds` / `queue.searchTimeoutSeconds` | `120` | 5–3600 | Cooperative timeout including queue wait. |

One active request per command source is allowed. `/locate cancel` sets the cancellation token; it never interrupts a Minecraft world call midway.

## Cache

| Key | Default | Safe range | Purpose |
| --- | ---: | ---: | --- |
| `cacheDurationMinutes` | `30` | 1–240 | Memory-cache lifetime. |
| `cacheChunkGranularity` | `8` | 1–128 | Coarse origin grouping for reusable results. |
| `cacheMaxEntries` / `cache.maxEntries` | `512` | 16–65536 | Maximum entries in each bounded in-memory cache. |

## Persistent index

The index is standard Minecraft SavedData at `world/data/locatefixer_index.dat`.

| Key | Default | Safe range | Purpose |
| --- | ---: | ---: | --- |
| `persistentIndexEnabled` / `index.enabled` | `true` | boolean | Enables lookup and writes. Disabling does not delete existing data. |
| `persistentIndexMaxEntries` / `index.maxEntries` | `8192` | 64–100000 | World-wide cap; oldest discoveries are removed first. |
| `persistentIndexExpiryDays` / `index.expiryDays` | `90` | 1–3650 | Maximum age for stable structure and biome discoveries. |
| `persistentIndexVerificationMinutes` / `index.verificationMinutes` | `30` | 1–10080 | Re-search window for POI, feature, and custom entries that may change. |

Only verified results are written or served. Predictive results from the built-in feature-capability lookup are not persisted. Malformed records are skipped during load, and legacy predictive feature entries are retained on disk but ignored. Unknown newer schema versions are ignored instead of being rewritten.

## Teleport

| Key | Default | Safe range | Purpose |
| --- | ---: | ---: | --- |
| `teleportPreloadRadiusChunks` / `teleport.preloadRadiusChunks` | `1` | 0–8 | Temporary ticket radius around the destination. |
| `teleportCountdownSeconds` / `teleport.countdownSeconds` | `5` | 0–60 | Countdown length. |
| `teleportCountdownEnabled` / `teleport.countdownEnabled` | `true` | boolean | Skips the countdown when false, while still waiting for readiness. |
| `teleportTimeoutSeconds` / `teleport.timeoutSeconds` | `30` | 5–300 | Cancels travel if chunks never become ready. |
| `safeHorizontalRadius` / `teleport.safeHorizontalRadius` | `8` | 1–32 | Landing search radius. |
| `safeVerticalRange` / `teleport.safeVerticalRange` | `48` | 4–128 | Vertical search above and below the target. |
| `allowWaterLanding` | `false` | boolean | Allows water in the player space. |
| `allowLavaLanding` | `false` | boolean | Allows lava. This is intentionally unsafe. |
| `allowFireLanding` | `false` | boolean | Allows fire body spaces and dangerous fire blocks. |
| `allowPowderSnowLanding` | `false` | boolean | Allows powder snow. |
| `returnPointEnabled` | `false` | boolean | Reserved for the planned return command; no return command is registered in 4.0.0. |

Only a short-lived coordinate grant emitted by Locate Unbound activates preload handling. Other `/tp` commands use vanilla behavior.

## Integrations and benchmark

| Key | Default | Purpose |
| --- | ---: | --- |
| `enableBiomeSpyCompatibility` / `integrations.biomeSpy` | `true` | Documents and reports transparent BiomeSpy influence. |
| `enableCompassCompatibility` / `integrations.compassMods` | `true` | Reserved control for compass adapters; current behavior is safe coexistence. |
| `enableAsyncLocatorConflictMode` / `integrations.asyncLocatorConflictMode` | `true` | Yields vanilla locate interception when Async Locator Refined is detected. |
| `benchmarkEnabled` / `benchmark.enabled` | `false` | Allows `/locateunbound benchmark` to report the operator's latest job metrics. |
| `commands.enableCommandErrorFixer` | `true` | Registry-aware correction suggestions for supported commands. |

Values outside safe ranges are clamped. Invalid Fabric values fall back to the last valid/default value and produce a server log error.
