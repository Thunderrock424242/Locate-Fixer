# Changelog

## 4.0.0 — Development candidate

Changes since `3.2.0`. This update focuses on reliable locating, bounded search resources, world safety and a simpler player workflow.

### Locate and teleport

- Coordinate links fill the chat command line with `/tp @s <x> <y> <z>`. Press Enter to teleport using vanilla `/tp`; clicking alone does not execute it.
- Added an explicit `teleport` suffix for `/locate structure`, `/locate biome` and `/locate poi` to request safe landing preparation.
- Added `/locate status` and `/locate cancel` for active searches and travel preparation.
- Replaced terrain carving with bounded searches for naturally safe floors and headroom. Safe travel checks collision, fluids, hazards and the world border, then revalidates before moving the player. It never changes blocks and cancels if no safe landing exists.
- Safe travel waits for FULL chunks and uses independent temporary tickets, with cleanup after completion, cancellation, disconnect, dimension change, timeout, failure and shutdown.
- New configurations default to no teleport countdown. Existing configured countdowns are respected.
- Removed the global `/tp` interception. Safe travel is requested explicitly; ordinary `/tp` keeps vanilla behavior.

### Search correctness

- Added nearby-first adaptive planning, a bounded recent-result cache and a persistent world discovery index, enabled by default.
- Search history can add useful radii without skipping configured nearby stages. Historical failures do not prove closer terrain is empty.
- Persistent discoveries survive restarts and use dimension-aware spatial lookup. Indexed candidates require live searches before becoming results, so a remembered location cannot automatically hide a closer undiscovered target.
- Cache entries distinguish exact XYZ origin, target, search radius, world, dimension and registry lifetime. Reload and shutdown clear stale state.
- Added bounded index loading, pruning and expiry, migration for supported older data, isolation of malformed records and read-only preservation of unknown newer schemas.
- Corrected conversion of structure search limits to native placement-ring inputs and reject results beyond the requested bound.
- Removed extra chunk generation used only to refine ordinary structure coordinates. Native structure placement and biome sampling semantics are retained.
- Search feedback distinguishes targets unavailable in the dimension, no result within the limit, cancellation, timeout and execution errors.

### Reliability and operator tools

- Added a shared job manager with a bounded queue, deadlines and cancellation. Reconfiguration reuses one worker pool rather than accumulating executors.
- Worker waits for server callbacks are bounded and cancellation-aware. Cancelled queued calls are abandoned, late results are ignored and disconnected players' work is cancelled.
- Minecraft world queries, providers, saved data and teleport operations stay on the server thread. Already-running vanilla generation calls are never forcibly interrupted.
- Added `/locatefixer diagnostics` and opt-in `/locatefixer benchmark` reporting for job timing, server-call execution, queue waits, cache/index activity and tick intervals.
- Added unified provider and backend registration APIs with target types, dimension/radius limits, cache policies and cancellation. Existing structure-provider APIs remain available.
- Preserved native synchronous behavior for RCON and commands with result callbacks, including `/execute store`.

### Compatibility and builds

- Consolidated development and GitHub Actions on `main`, retaining all four targets: Forge and Fabric for Minecraft 1.20.1, and NeoForge and Fabric for Minecraft 1.21.1.
- Production artifact collection selects only the current version's installable JARs.
- Unified messages, logs, descriptions, worker names and logo paths under Locate Fixer. Administrative commands use the single `/locatefixer` root.
- Preserve the `locatefixer` mod ID, Java packages, configuration filename/keys and `world/data/locatefixer_index.dat` path. New Fabric configurations are simplified; retained legacy settings are documented.
- Added detection that yields overlapping native locate ownership to Async Locator Refined. Modded searches use active registries and vanilla generator hooks; compass mods retain their own searches.
- Removed active WorldEdit schematic tracking/commands, random dimension travel and unrelated give/summon/effect correction, along with WorldEdit build dependencies. Deprecated public helpers remain available for compatibility.
- Added an optional Forge 47.4.20 / Ixeris 4.6.5 development profile and disposable-world reproduction probe. Ixeris is not bundled in normal builds.
- Verified current Forge refmap packaging while investigating issue #89. The old 3.1.0 Forge artifact declared a missing refmap; the current minimal Ixeris profile passed world creation and a biome locate. The original modpack crash remains unverified.

### Validation and upgrade notes

The comparison baseline is the last `3.2.0` source commit, `f245b6b`. See [the validation report](docs/4.0-validation.md), [configuration guide](CONFIGURATION.md) and [API migration notes](API_DOCUMENTATION.md) for build evidence and integration changes.

Automated regression coverage includes search/cache/index correctness, cancellation and lifecycle cleanup, command permissions, bounded server waits, tick telemetry, natural landing fixtures and independent ticket ownership. Live player travel, multiplayer, the original issue-89 modpack and comparative performance acceptance remain pending. An already-running vanilla generation call can still stall a server tick; no overall speedup is claimed.

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
