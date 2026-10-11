# Changelog

## 4.0.0 development — Locate Fixer safety and correctness refactor

Based on the `1.21.1` development architecture. All features use Locate Fixer branding. The technical `locatefixer` ID, Java APIs, configuration keys and world index filename are preserved.

### Search

- Preserve every nearby configured stage; success history adds useful radii rather than skipping closer terrain. Failure history is scoped to exact origin and world lifetime.
- Treat persistent discoveries as candidates that require live search, with spatial lookup, bounded load/pruning, restart verification, datapack invalidation and read-only preservation of unknown future schemas.
- Scope memory results to exact XYZ, target, world/dimension, registry lifetime and search radius. Bound expiry/eviction and clear all lifecycle owners.
- Convert structure block limits to Minecraft placement-ring inputs and reject results beyond the requested bound. Keep native biome sampling and generation ownership.
- Remove extra chunk generation used only to refine normal structure output.
- Distinguish unavailable, not found, cancelled, timed out and failed jobs.

### Jobs and performance

- Reconfigure one bounded executor instead of accumulating worker pools; cancellation reclaims pending capacity.
- Bound worker waits for server callbacks and abandon cancelled queued world calls. Never interrupt an already-running generator call or wait on worker futures from the server thread.
- Ignore late cancelled results, clear shutdown/restart state and cancel disconnected players.
- Measure actual server-call execution, queue wait and elapsed intervals between ticks overlapping calls. Operator benchmarks also show recent native tick samples with explicit attribution limits.

### Safe travel

- Add explicit player-only `teleport` suffixes to native structure, biome and POI arguments.
- Ordinary locate only reports coordinates; coordinate clicks suggest text. Remove runtime `/tp` interception.
- Replace carving, forced-chunk sharing and the scheduled teleport executor with read-only natural landing searches on loader ticks.
- Use unique temporary tickets, actual FULL readiness, bounded operation/ticket/check budgets, underground-first search, collision/border/hazard checks and immediate revalidation.
- Release tickets after cancellation, disconnect, dimension change, timeout, failure, completion and shutdown; retain failed cleanup ownership for retry.
- Default countdown to zero while honoring existing configured values. Unsafe legacy hazard flags cannot relax the safe path.

### Scope and compatibility

- Use Locate Fixer throughout documentation, mod descriptions, logs, errors, worker names and logo paths. Administrative commands use the single `/locatefixer` root.
- Remove schematic tracking/commands, random dimension travel and give/summon/effect correction from the runtime. Retain public compatibility helpers and document replacements.
- Keep nearest/provider/feature-capability tools optional and diagnostics operator-invoked.
- Preserve conflict yielding to Async Locator Refined and use vanilla registries/generator hooks for other worldgen mods.
- Investigate issue #89 from its attached crash: the old 3.1.0 Forge artifact reported a missing injection target/refmap. This is not evidence of an Ixeris thread conflict.
- Simplify newly generated Fabric configuration and document retained loader-managed legacy keys.

### Validation

See [the validation report](docs/4.0-validation.md) for exact test/build/artifact evidence. Regression tests cover planner/index nearest invariants, exact cache scope, provider coordinate overflow, cancellation/reconfiguration/restart, bounded owner-thread waits, Brigadier attachment, tick telemetry, natural landing fixtures and independent ticket cleanup.

Automated checks do not establish live gameplay, multiplayer, custom dimension/modpack compatibility or comparative MSPT/CPU/memory results. These remain release acceptance work.

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
