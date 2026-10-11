# Locate Fixer configuration

The existing `locatefixer-server.toml` filename and keys remain accepted. Adaptive search and persistent indexing default to enabled. New Fabric files show the practical settings below; Forge/NeoForge retain loader-managed legacy fields so existing configurations do not lose values. Their SERVER configuration can reside in a world's `serverconfig` directory.

Fabric reloads configuration on startup and `/reload`. Forge and NeoForge use loader config events. Restart after changing command availability because commands are registered when the dispatcher is built.

## Search and resources

Fabric uses the key shown; Forge/NeoForge retain the alternate nested path where listed.

| Key | Default | Range | Meaning |
| --- | ---: | ---: | --- |
| `locateRings` | `[6400,16000,32000,64000,128000,256000]` | Positive integers | Nearby-first block-distance stages; largest is the maximum search distance. |
| `locateThreadCount` | `1` | 1–8 | Orchestration workers. World calls remain serialized on the server thread. |
| `queueMaxPending` / `queue.maxPending` | `32` | 1–1024 | Pending job admission limit. Full queues reject new requests. |
| `searchTimeoutSeconds` / `queue.searchTimeoutSeconds` | `120` | 5–3600 | Cooperative deadline including queue wait. |
| `poiSearchRadius` | `256` | 16–4096 | Live POI search radius. |
| `cacheDurationMinutes` | `30` | 1–240 | Recent-search cache lifetime. |
| `cacheMaxEntries` / `cache.maxEntries` | `512` | 16–65536 | Limit for each bounded cache. |

One active search per source is allowed. Player ownership uses UUID, including when commands are executed in another dimension. Reducing queue capacity leaves previously admitted work to drain; it prevents new admission above the new limit. Cancellation removes queued jobs promptly and prevents late results. A vanilla world operation already executing cannot be interrupted safely.

## Persistent discoveries

The world-specific SavedData file is `world/data/locatefixer_index.dat`.

| Key | Default | Range | Meaning |
| --- | ---: | ---: | --- |
| `persistentIndexMaxEntries` / `index.maxEntries` | `8192` | 64–100000 | World-wide entry cap. Old discoveries are pruned first. |
| `persistentIndexExpiryDays` / `index.expiryDays` | `90` | 1–3650 | Maximum discovery age. |
| `persistentIndexVerificationMinutes` / `index.verificationMinutes` | `30` | 1–10080 | Recent verification window for every indexed target type. |

Only verified discoveries are stored. Index entries suggest a stage; they never prove the nearest answer. POI commands query the live POI manager. Index queries use spatial buckets for the requested dimension/type/target. Reverification writes are throttled and normal world saves persist dirty SavedData. There is no background save or per-query disk rewrite. Restarted entries need verification and datapack reload clears stale records. Unknown newer schema data is kept read-only so downgrading does not overwrite it.

## Explicit safe teleport

| Key | Default | Range | Meaning |
| --- | ---: | ---: | --- |
| `teleportCountdownSeconds` / `teleport.countdownSeconds` | `0` | 0–60 | Optional delay after finding a ready, safe destination. |
| `teleportTimeoutSeconds` / `teleport.timeoutSeconds` | `30` | 5–300 | Chunk readiness and landing-search deadline. |
| `safeHorizontalRadius` / `teleport.safeHorizontalRadius` | `8` | 1–32 | Nearby landing search extent in blocks. |
| `safeVerticalRange` / `teleport.safeVerticalRange` | `48` | 4–128 | Initial vertical range near target Y. |

Only an explicit `/locate … teleport` prepares travel. Existing five-second countdown values remain five seconds; new files default to zero. The optional countdown begins after preparation and is separate from the preparation timeout. Landing search first preserves underground height, then may try nearby loaded surface positions in dimensions without a ceiling. Natural terrain is never changed.

Travel uses at most eight active operations and 128 uniquely owned temporary tickets per server. Landing checks share a bounded server-tick budget and each search is capped. Large player hitboxes or unavailable safe terrain can cause a clear failure rather than expanding the search without limit.

## Optional tools

| Key | Default | Meaning |
| --- | ---: | --- |
| `enableNearestCommand` | `false` | Registers nearest aggregate searches. |
| `enableAsyncLocatorConflictMode` / `integrations.asyncLocatorConflictMode` | `true` | Yields native locate ownership to detected Async Locator Refined. |
| `benchmarkEnabled` / `benchmark.enabled` | `false` | Allows the operator benchmark report. |
| `enableCommandErrorFixer` / `commands.enableCommandErrorFixer` | `true` | Locate-only registry-aware suggestions when this mod owns native locate. |

Diagnostics require permission 2 and run only when invoked. Benchmark reporting reads measured calls and existing Minecraft tick samples. When enabled, the existing job/tick lifecycle also retains up to 256 jobs through their next overlapping tick to measure elapsed tick intervals, including work between ticks. There is no additional background manager; this interval capture is inactive when benchmarking is disabled.

## Retained compatibility keys

Existing files can keep these keys without migration or deletion:

- `adaptiveSearchEnabled` and `persistentIndexEnabled` / `index.enabled`: default true; false is a conservative troubleshooting fallback. Disabling the index does not delete its file.
- `cacheChunkGranularity`, `biomeSampleRadiusMultiplier`, `biomeSampleStepMultiplier`: accepted legacy tuning; exact-origin caches and vanilla biome sampling supersede them.
- `teleportPreloadRadiusChunks`: accepted; tickets now follow the actual bounded landing footprint.
- `teleportCountdownEnabled`: an existing false still disables the optional countdown.
- `allowWaterLanding`, `allowLavaLanding`, `allowFireLanding`, `allowPowderSnowLanding`: accepted but cannot relax the safety checks. Safe travel always rejects these hazards.
- `returnPointEnabled`, `enableBiomeSpyCompatibility`, `enableCompassCompatibility`: retained fields with no additional command, manager or interception.
- `enableFeatureLocateCommand`: preserves the disabled-by-default feature-capability lookup for API/command compatibility.

Values outside supported ranges are clamped. Invalid Fabric scalar values fall back to defaults; failure to read the file logs an error and keeps the last applied configuration. Existing configs are not rewritten by Fabric.
