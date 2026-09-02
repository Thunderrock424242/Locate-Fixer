# Changelog

## 4.0.0 — Locate Unbound architecture upgrade

### Branding

- Renamed the public mod name from Locate Fixer to Locate Unbound.
- Added new master, 128-pixel, and packaged loader icons.
- Preserved `locatefixer` as the mod ID, package namespace, resource namespace, config filename, SavedData filename prefix, and compatibility command alias.

### Discovery engine

- Added normalized locator requests/results and target types.
- Added pluggable backend metadata and deterministic backend selection.
- Added adaptive search plans with configured-ring fallback and bounded distance history.
- Added a bounded job queue, named daemon workers, cooperative cancellation/timeouts, per-source flood protection, status history, and progress metrics.
- Routed structures, biomes, POIs, feature-capability lookups, dimension travel, schematic lookups, nearest-X searches, and custom providers through the shared job owner.
- Moved biome, feature, dimension, schematic, and provider world queries through the server executor.
- Rechecked cancellation and deadlines after every backend world operation so a late result cannot be accepted after timeout.

### Persistent world index

- Added dimension-aware, deduplicated, bounded Minecraft SavedData at `world/data/locatefixer_index.dat`.
- Added schema versioning, malformed-entry isolation, age expiry, mutable-target verification windows, and verified-only result writes.
- Kept the existing structure and biome memory caches and completed provider `MEMORY`/`PERSISTENT` cache-policy handling.
- Prevented unverified and legacy predictive feature records from being served as exact discoveries.

### API and integrations

- Added unified provider metadata for structures, biomes, POIs, features, and custom targets.
- Preserved the old custom-structure registry through a deprecated compatibility bridge.
- Added safe optional detection and diagnostics for BiomeSpy, Nature's Compass, Explorer's Compass, Async Locator Refined, and WorldEdit.
- Added conflict-safe vanilla `/locate` ownership when Async Locator Refined is detected.
- Replaced the nonfunctional WorldEdit edit-session guess with `/locate schematic record <name>` and dimension-aware session lookup.

### Commands and diagnostics

- Added `/locate status` and `/locate cancel`.
- Added `/locateunbound diagnostics` and the compatibility alias `/locatefixer diagnostics`.
- Added config-gated `/locateunbound benchmark` for the operator's latest search.
- Added tracked-future diagnostics and fixed the fast-completion race that could retain finished futures.

### Teleport safety

- Scoped preload handling to one-time recent locate coordinate grants; unrelated `/tp` commands remain vanilla.
- Replaced estimated preload progress with actual ready-chunk counts and a real timeout.
- Added configurable countdown, preload radius, landing search ranges, and hazard permissions.
- Added conservative checks for lava, water, fire, campfires, powder snow, void bounds, suffocation, unsupported positions, falling-block floors, and ceiling dimensions.
- Ensured only tickets added by Locate Unbound are released after every completion and cancellation path.

### Validation

- Added planner, progress-message, persistent-index, provider-memory-cache, backend deadline, and job-cleanup unit tests.
- Enabled the NeoForge ModDev JUnit test environment and added test-source Minecraft dependencies for the legacy Forge target.
- Added all-loader metadata icon declarations.
- The four-target Gradle test suite passes 11 tests per target (44 executions total) for Forge 1.20.1, Fabric 1.20.1, NeoForge 1.21.1, and Fabric 1.21.1.
- Live client, dedicated-server, multiplayer, command, teleport, WorldEdit, and gameplay acceptance checks remain release-candidate validation tasks.
