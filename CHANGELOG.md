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
- Routed structures, biomes, POIs, placed features, nearest-X searches, and custom providers through the shared job owner.
- Moved biome and feature world queries through the server executor.

### Persistent world index

- Added dimension-aware, deduplicated, bounded Minecraft SavedData at `world/data/locatefixer_index.dat`.
- Added schema versioning, malformed-entry isolation, age expiry, mutable-target verification windows, and automatic result writes.
- Kept the existing memory cache as the first lookup layer.

### API and integrations

- Added unified provider metadata for structures, biomes, POIs, features, and custom targets.
- Preserved the old custom-structure registry through a deprecated compatibility bridge.
- Added safe optional detection and diagnostics for BiomeSpy, Nature's Compass, Explorer's Compass, Async Locator Refined, and WorldEdit.
- Added conflict-safe vanilla `/locate` ownership when Async Locator Refined is detected.

### Commands and diagnostics

- Added `/locate status` and `/locate cancel`.
- Added `/locateunbound diagnostics` and the compatibility alias `/locatefixer diagnostics`.
- Added config-gated `/locateunbound benchmark` for the operator's latest search.

### Teleport safety

- Scoped preload handling to one-time recent locate coordinate grants; unrelated `/tp` commands remain vanilla.
- Replaced estimated preload progress with actual ready-chunk counts and a real timeout.
- Added configurable countdown, preload radius, landing search ranges, and hazard permissions.
- Added conservative checks for lava, water, fire, campfires, powder snow, void bounds, suffocation, unsupported positions, falling-block floors, and ceiling dimensions.
- Ensured only tickets added by Locate Unbound are released after every completion and cancellation path.

### Validation

- Added planner and persistent-index unit tests.
- Enabled the NeoForge ModDev JUnit test environment and added test-source Minecraft dependencies for the legacy Forge target.
- Added all-loader metadata icon declarations.
- Java compilation was checked for Forge 1.20.1 and NeoForge 1.21.1; the local Gradle run reached clean Java compiler output but its final task status remained blocked by Windows locks on ModDev artifact JARs. Fabric setup was separately blocked by a stale Loom intermediary mapping lock before compilation.
- The new unit tests remain unexecuted locally because the same locked ModDev artifact prevents Gradle from completing test compilation.
