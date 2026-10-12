# Changelog

## 4.0.0

Full changes since `3.2.0`. Locate Fixer keeps the familiar Minecraft locate commands while adding adaptive planning, persistent discoveries, bounded job management and explicit safe travel. The update removes unrelated runtime features and keeps existing mod identity and integration APIs compatible.

### 3.2.0 compared with 4.0.0

| Area | 3.2.0 | 4.0.0 |
| --- | --- | --- |
| Ordinary locate | Asynchronous structure, biome and POI searches with configured rings. | Same familiar commands, with shared job controls, nearby-first adaptive planning and clearer outcomes. |
| Coordinate clicks | Suggested `/tp @s ...`, with locate-related teleport interception. | Suggests the complete `/tp @s ...` command; Enter runs vanilla teleport. Safe preparation is a separate explicit action. |
| Safe travel | Locate-related `/tp` handling, fixed countdown and scheduled preload work. | `/locate ... teleport`, tick-driven preparation and configurable countdown that defaults to zero. |
| Landing protection | Could carve a safety pocket when natural landing searches failed. | Searches natural terrain only; cancels when no safe landing exists. |
| Destination chunks | Fixed preload footprint and shared forced-chunk state. | Bounded landing footprint, actual FULL readiness and independently owned temporary tickets. |
| Search history | Configured rings and cache-derived starting points. | Origin-scoped hints can add useful radii without skipping nearby configured stages. |
| Recent-result cache | Timed structure/biome caches grouped by coarse XZ origin. | Exact XYZ/request/lifetime scope, configurable capacity and lifecycle invalidation. |
| Persistent discoveries | No shared persistent discovery index. | Bounded world-specific index with spatial queries, expiry, live verification and schema handling. |
| Queue and waits | Unbounded executor queue and indefinite server-callback waits. | Bounded admission, cancellation-aware waits, deadlines and status tracking. |
| Extensions | Legacy custom-structure provider API. | Unified provider API alongside legacy adapters; internal backend selection boundary. |
| Runtime scope | Included schematic tracking, dimension travel and broader command correction. | Focuses on locating and explicit safe travel; unrelated runtime integrations are removed. |
| Versions and loaders | Four Gradle targets. | The same four targets, consolidated on `main` with shared build and CI entry points. |

### Player commands and feedback

- Retained `/locate structure <target>`, `/locate biome <target>` and `/locate poi <target>`, including native target parsing, tag support and operator permission level 2.
- Ordinary locate reports coordinates and distance without automatically moving the player or preparing teleport chunks.
- Clicking a coordinate link fills chat with `/tp @s <x> <y> <z>`. The player presses Enter to teleport. The hover text explains this action, and the link does not run a command immediately.
- Added an optional player-only `teleport` suffix to the existing native structure, biome and POI arguments. Existing command handlers are preserved, and conflicting command owners are not replaced.
- Added `/locate status` to inspect a request and `/locate cancel` to cancel searching or safe-travel preparation.
- Distinguish a target unavailable in the current dimension, no result within the search limit, cancellation, timeout and an execution error.
- Keep registry-aware suggestions for invalid locate targets, controlled by the existing command-error setting.
- Retained `/xlocate customstructure <id>` for legacy and unified custom providers. Optional nearest and feature tools remain available.

Example safe-travel commands:

```text
/locate structure minecraft:stronghold teleport
/locate biome minecraft:cherry_grove teleport
/locate poi minecraft:home teleport
```

### Adaptive searching and native search correctness

- Added adaptive search planning and scoped search history, enabled by default.
- Search configured nearby stages before farther stages. Previous successes can add useful radii, but speculative failure history cannot establish that closer terrain is empty.
- Scope historical hints to the request origin and world/registry lifetime instead of sharing them across unrelated searches.
- Keep a conservative configured-ring fallback when adaptive planning is disabled for troubleshooting.
- Convert structure block-distance limits to Minecraft's native placement-ring inputs correctly and reject returned candidates outside the requested bound.
- Preserve native structure placement semantics and vanilla biome sampling. Biome searches do not claim an exhaustive search of every block.
- Check available biome sources, registries and provider dimension declarations before performing unnecessary searches for unavailable targets.
- Removed extra chunk generation used solely to refine ordinary structure output to a generated bounding-box center. Results use the native generator coordinates.
- Keep POI queries on the live POI manager rather than substituting remembered discoveries.

### Recent-result caching and persistent discoveries

- Refined recent-result caches to distinguish exact X, Y and Z origin, target, dimension, search radius, world and registry lifetime. A nearby player's cached answer is not automatically treated as the nearest answer for another origin.
- Added configurable cache capacity, bounded expiry/eviction and cache cleanup on reload and shutdown. Providers can declare whether their results permit memory or persistent reuse.
- Added the world-specific persistent index at `world/data/locatefixer_index.dat`, enabled by default.
- Store verified discoveries with target, dimension, source and verification metadata. Discoveries survive normal saves and restarts.
- Use spatial buckets, deduplication, entry caps, expiry and bounded loading/pruning to limit index work and storage.
- Treat indexed locations as candidates or useful search bounds. A live generator/provider search still establishes the returned result; a remembered farther location cannot automatically hide a closer undiscovered target.
- Require live verification after restart and invalidate supported discovery state on datapack reload.
- Isolate malformed records, migrate supported older schemas and preserve unknown newer schemas without overwriting them.
- Throttle repeated verification writes and let Minecraft's normal SavedData save lifecycle persist dirty index state. Searches do not introduce a separate background saver or rewrite the disk file on every query.

### Job management, cancellation and lifecycle

- Added a shared job manager with bounded pending admission, one active search per source and structured status snapshots.
- Track queued work, index lookup, backend searching and terminal outcomes separately. Full queues reject new work with feedback.
- Apply cooperative deadlines that include queue wait. Worker waits for server callbacks check cancellation and timeouts rather than waiting indefinitely.
- Cancel queued work promptly, prevent abandoned queued world calls from running, and ignore results arriving after cancellation.
- Reconfigure the existing search executor instead of accumulating replacement worker pools. Reducing queue capacity lets admitted work drain while restricting new admission.
- Use player UUID ownership across command execution dimensions, cancel disconnected players' work, and clear job/cache/history state across shutdown and restart.
- Datapack reload cancels old searches and invalidates stale caches, history and discoveries.
- Keep world generation, biome/POI queries, providers, SavedData and teleport operations on the owning server thread. Workers handle orchestration; live world calls remain serialized.
- Preserve synchronous native handling for RCON and result-callback commands such as `/execute store`, so callers receive the actual native result rather than asynchronous admission value `1`.

### Safe landing and teleport preparation

- Replaced global `/tp` interception with explicit safe-travel requests. Clicking coordinates and pressing Enter uses vanilla teleport; the `teleport` suffix requests Locate Fixer's preparation and landing checks.
- Replaced the scheduled teleport executor with the loader's server-tick lifecycle.
- Request only the chunks covering the bounded landing search, wait for actual FULL readiness, and enforce operation, ticket, timeout and landing-check budgets.
- Search near the target's original height first, retaining underground context. A bounded loaded-surface fallback may be used in dimensions without a ceiling.
- Check stable floor support, sufficient headroom, player collision, fluids, hazards, world height and the world border. Larger player hitboxes must also fit the bounded loaded footprint.
- Revalidate player/world state and the final landing immediately before moving the player, and preserve orientation.
- Never carve terrain or place, remove or replace blocks. Preparation cancels if it cannot find a naturally safe position.
- Give overlapping operations independent temporary ticket ownership. One player's cleanup cannot release another operation's destination lease.
- Release tickets after success, cancellation, disconnect, dimension change, timeout, failure and shutdown. Failed cleanup remains owned for retry.
- Default new configurations to no countdown. Existing configured countdown values remain honored, and countdown begins after destination preparation.
- Retained legacy hazard flags cannot relax safe-travel checks for water, lava, fire or powder snow.

### Configuration and optional tools

Normal locate, coordinate command suggestions and explicit safe travel require no configuration changes. Adaptive planning, recent caching and persistent discoveries use their defaults immediately.

| Setting or tool | 4.0.0 default |
| --- | --- |
| Adaptive planning / persistent index | Enabled |
| Orchestration workers | 1 |
| Pending job capacity | 32 |
| Search deadline | 120 seconds |
| Each bounded recent-result cache | 512 entries, 30-minute lifetime |
| Persistent index | 8,192 entries, 90-day expiry, 30-minute verification window |
| Safe-travel preparation timeout | 30 seconds |
| Initial safe-landing horizontal / vertical range | 8 / 48 blocks |
| Teleport countdown | 0 seconds |
| `/locate nearest` | Disabled; enable with `enableNearestCommand` |
| `/locate feature` | Disabled; enable with `enableFeatureLocateCommand` |
| Benchmark capture/reporting | Disabled; enable with `benchmarkEnabled` |
| Locate error suggestions | Enabled |
| Async Locator Refined conflict yielding | Enabled |

- Search rings, POI radius, queue limits, timeouts, cache/index budgets and safe-landing ranges remain tunable. Values are clamped to supported ranges.
- Retained optional nearest aggregate searches. These are sampling tools, not an exhaustive enumeration of every nearest target.
- Retained optional placed-feature capability lookup. It reports a suitable biome rather than proving an exact placed-feature occurrence; its result is unverified, ungenerated, non-clickable and excluded from persistent indexing.
- Added `/locatefixer diagnostics` for resource limits, selected backend and detected integrations. It requires permission level 2 and runs only when invoked.
- Added opt-in `/locatefixer benchmark` reporting for job wall time, server-call execution/wait, cache/index counters, elapsed tick intervals and recent native tick samples. Interval capture is inactive when benchmarking is disabled.
- Simplified newly generated Fabric configuration while retaining accepted legacy keys and the `locatefixer-server.toml` filename. Existing Fabric files are not rewritten; Forge/NeoForge retain loader-managed fields and world-scoped server configuration.
- Legacy cache-granularity, biome-sampling and preload-radius fields remain accepted where documented, while exact-origin caching, native sampling and actual landing footprints govern current behavior.
- Retained return-point and older integration fields do not start additional commands or managers. See [CONFIGURATION.md](CONFIGURATION.md) for exact keys and loader-specific paths.

### Provider APIs and mod coexistence

- Added a unified provider contract for structures, biomes, POIs, features and custom targets, with stable namespaced IDs and display names.
- Providers declare dimension/radius limits, cache policy, estimated search cost, travel suitability and cooperative cancellation. Out-of-scope coordinates are rejected, including extreme-coordinate distance cases.
- Added structured requests/results with target, dimension, source, backend, discovery time, verification and generation metadata.
- Require verified provider results before writing persistent discoveries. Predictive results may use memory caching only when the provider's policy permits it.
- Keep legacy structure registration callbacks available through adapters. Legacy callbacks and unified providers are conservatively invoked on the server thread.
- Added an internal backend registry/selection boundary. Providers are the supported third-party extension point; this release does not promise a public global backend-registration API.
- Detect Async Locator Refined and yield overlapping native locate ownership by default. Omit Locate Fixer's native teleport suffix in that conflict mode.
- Use active registries and generator hooks for modded terrain, structures and biomes. Nature's Compass and Explorer's Compass retain ownership of their own searches; detection does not replace their worker systems.

### Removed runtime behavior and migration

- Removed automatic WorldEdit schematic folder scans, recent schematic tracking and active schematic commands, along with WorldEdit build dependencies.
- Removed random dimension-travel command registration. Vanilla `/execute in <dimension> run tp ...` remains the replacement for dimension-specific teleport commands.
- Removed generic give/summon/effect correction from the runtime; command suggestions focus on locate targets.
- Removed the obsolete administrative alias. Operator tools use the single `/locatefixer` command root.
- Retained deprecated public schematic, dimension, WorldEdit and teleport helpers as compatibility seams. Their presence does not enable the removed runtime integrations.
- Legacy teleport authorization grants no longer intercept `/tp`. Integrations needing safe travel should use `requestTeleport(source, level, target)` or the retained preload entry point and its documented contract.
- Keep the `locatefixer` mod ID, Java package names, provider registration seams, configuration filename/accepted keys and established index filename. The index schema can migrate without changing its file path.
- Use Locate Fixer consistently in descriptions, messages, errors, logs, worker names, documentation and logo paths. Existing artwork is retained.

### Supported targets, build tooling and compatibility investigation

- Retained the four version/loader targets already present in 3.2.0: Forge and Fabric for Minecraft 1.20.1, and NeoForge and Fabric for Minecraft 1.21.1.
- Keep Java 17 for 1.20.1 and Java 21 for 1.21.1. Fabric requires Fabric API. Server installation does not require Locate Fixer on connecting clients.
- Consolidated both version branch histories and development on `main`. Shared sources and version/loader adapters are built from one checkout using the existing Gradle multi-project setup.
- GitHub Actions builds the four-target matrix for pushes and pull requests to `main`, with a manual-run option.
- Updated the Gradle wrapper from 9.7.1 to 9.8.0.
- Production artifact collection selects only the current version's installable JARs and excludes source/development JARs and stale older-version outputs.
- Verified the current Forge production refmap and locate mappings while investigating issue #89. The older reported 3.1.0 Forge artifact declared a refmap that was absent from its JAR.
- Added an optional Forge 47.4.20 / Ixeris 4.6.5 development profile and a disposable integrated-world probe. The minimal profile passed world creation and a biome locate. Ixeris and the probe are not bundled in normal artifacts; normal Forge builds retain 47.4.10.
- The minimal probe does not establish the original issue-89 modpack's outcome, and no speculative Ixeris threading patch was added. See [the reproduction notes](docs/ixeris-issue-89.md).

### Validation and comparison scope

This comparison uses `f245b6b`, the last source commit declaring `3.2.0`, through the current 4.0.0 tree, including the Gradle update in `78ec3f7`. Features already present in that baseline are identified as retained or changed rather than newly added.

Recorded automated validation covers 60 shared regression tests across four target environments: 240 executions, with zero failures, errors or skips. Coverage includes planning, cache/index correctness, coordinate overflow, cancellation/reconfiguration/restart, command permissions, server waits, tick telemetry, natural landing fixtures and independent ticket ownership. Production JAR inspection and isolated server/client checks are documented in [the validation report](docs/4.0-validation.md).

On 2026-10-11, the maintainer reported that the updated behavior works as intended in their testing. This confirmation adds user testing evidence to the recorded automated checks; it does not specify every loader, multiplayer case or modpack combination. This changelog update itself changes documentation only and does not publish a release.

An already-running vanilla generation call can still stall a server tick. Native biome sampling and structure placement retain their own search semantics. Benchmark timing includes its documented attribution limits; no numerical overall speedup or universal modpack compatibility claim is made.

For upgrade and integration details, see [README.md](README.md), [CONFIGURATION.md](CONFIGURATION.md) and [API_DOCUMENTATION.md](API_DOCUMENTATION.md).

## 3.1.0

Changes since `3.0.0`.

### Added

- Added an optional, config-gated `/locate feature <placed_feature_id>` command for finding nearby biome regions that can generate a requested placed feature.

### Changed

- Moved custom registered structure lookup to `/xlocate customstructure <id>` to avoid conflicts with vanilla locate behavior, and updated the related docs.
- Improved locate and teleport feedback with clearer start, progress, completion, chunk-preload, and teleport-target status messages.
- Refined teleport destination handling with safer nearby-position checks, better underground and surface fallbacks, and wider destination chunk preloading.
- Updated build/runtime dependencies from NeoForge `21.1.219` to `21.1.227` and ModDevGradle `2.0.140` to `2.0.141`.

### Fixed

- Prevented structure locate cache collisions between different structure queries.
- Validated located structure candidates against the originally requested structure set before reporting a result.
- Preserved teleport preloading for locate-generated `/tp` coordinates when vanilla supplies an implicit rotation object.
- Restored placed-feature ID suggestions for the new feature locate command.
- Tightened several teleport target and locate-result paths so reported coordinates and prepared teleport targets stay aligned.
