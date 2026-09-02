# Locate Unbound

![Locate Unbound logo](branding/locate-unbound-logo-128.png)

Locate Unbound is a server-first world discovery engine for Forge, NeoForge, and Fabric. It keeps the established technical ID `locatefixer` so existing worlds, server configs, API consumers, and resource paths continue to work after the rename.

## Supported targets

Install only the JAR matching the server's exact Minecraft version and loader.

| Minecraft | Loader | Java | Extra requirement |
| --- | --- | --- | --- |
| 1.20.1 | Forge 47.4.10+ | 17 | None |
| 1.20.1 | Fabric | 17 | Fabric API |
| 1.21.1 | NeoForge 21.1.219+ | 21 | None |
| 1.21.1 | Fabric | 21 | Fabric API |

## Architecture

Primary single-target discovery routes now follow the same ownership chain:

```text
command or provider API
  -> bounded LocateJob queue
  -> memory cache
  -> per-world persistent index
  -> adaptive SearchPlan
  -> selected LocatorBackend
  -> normalized LocatorResult
  -> cache and index update
```

Background workers own planning, cancellation, progress, and result bookkeeping. Live `ServerLevel`, chunk generator, biome, POI, SavedData, forced-chunk, and teleport operations are marshalled through Minecraft's server executor.

### Discovery systems

- **Backend registry.** `LocatorBackendRegistry` chooses the highest-priority available backend for structures, biomes, POIs, features, or custom targets. The built-in backend preserves Minecraft's standard world-generator methods, so lower-level optimizers can still influence them.
- **Adaptive plans.** Configured rings remain the fallback. Successful and failed distance history lets later searches skip radii already known to be unhelpful while respecting the configured maximum.
- **Persistent index.** Verified results are deduplicated in `world/data/locatefixer_index.dat`. The index is dimension-aware, versioned, bounded, expiration-aware, and tolerant of malformed entries.
- **Memory cache.** Recent structure, biome, and eligible provider results remain the fastest lookup layer. Provider `MEMORY` entries expire with the configured cache lifetime and are never written to the world save.
- **Unified providers.** Third-party providers declare type, dimension support, radius, cache policy, thread safety, estimated cost, and teleport suitability. The old custom-structure API remains available through a compatibility bridge.

## Commands

Normal Minecraft commands remain the primary interface:

- `/locate structure ...`
- `/locate biome ...`
- `/locate poi ...`
- `/locate status`
- `/locate cancel`

Additional commands:

- `/locateunbound diagnostics` — operator report for backends, integrations, queue, caches, index, and radius.
- `/locateunbound benchmark` — reports captured metrics for the operator's latest search when benchmarking is enabled.
- `/locatefixer ...` — compatibility alias for Locate Unbound control commands.
- `/xlocate customstructure <id>` — searches a registered generic or legacy custom provider.
- `/locate nearest structure <count>` and `/locate nearest biome <count>` — optional operator commands.
- `/locate feature <namespace:id>` — optional search for the nearest biome whose generation settings can contain the feature. It does not claim that an exact placed feature exists at the reported coordinates.
- `/locate dimension <dimension> [biome]` — queued, cancellable destination search followed by safe travel.
- `/locate schematic <name>` — finds a schematic anchor recorded in the current server session.
- `/locate schematic record <name>` — operator command to record the current position after a WorldEdit paste.
- Existing command-correction utilities remain registered.

## Safe travel

Clicking coordinates produced by a recent Locate Unbound result grants one short-lived preload teleport. Ordinary absolute `/tp` commands are no longer intercepted.

The travel pipeline:

- Adds bounded temporary tickets only around the destination.
- Reports actual ready chunks rather than an estimated percentage.
- Waits for readiness with a configurable timeout and countdown.
- Rechecks the landing immediately before moving the player.
- Rejects void, suffocation, lava, fire, campfire, powder-snow, unsupported, and falling-block floors by default.
- Avoids treating the Nether roof as a normal surface.
- Releases only tickets added by Locate Unbound after success, failure, cancellation, disconnect, exception, or server stop.

Use `/locate cancel` to stop either the current search or countdown.

## Optional-mod behavior

- **BiomeSpy:** detected without a dependency. Locate Unbound deliberately continues through Minecraft's standard biome and structure methods, allowing BiomeSpy's lower-level mixins to optimize those calls. No BiomeSpy code or reflection hook is copied.
- **Nature's Compass / Explorer's Compass:** detected and reported. Their existing worker managers remain owners of compass searches; Locate Unbound does not overwrite them with fragile optional mixins.
- **Async Locator Refined:** conflict-safe mode yields vanilla `/locate` interception to the other mod when a recognized mod ID is detected, avoiding duplicate searches.
- **WorldEdit:** `.schem` names are discovered for suggestions. Because WorldEdit does not expose a stable public paste event containing both the schematic name and destination, an operator explicitly records the pasted anchor with `/locate schematic record <name>`.
- **Registry and TerraBlender world generation:** active registries and biome sources remain authoritative.

Cartographer maps, dolphins, and third-party exploration items are not mixin-rerouted in this release because no stable cross-loader hook exists. The generic provider/backend API is the supported integration seam for those systems.

The optional nearest-X commands are aggregate sampling tools. They use the shared bounded job owner, cancellation, metrics, server-thread marshalling, and index writes, while coordinating multiple vanilla candidates above the single-result backend boundary.

## Configuration and API

The config filename stays `config/locatefixer-server.toml` for compatibility. See [CONFIGURATION.md](CONFIGURATION.md) for all categories and [API_DOCUMENTATION.md](API_DOCUMENTATION.md) for provider examples and the legacy bridge.

## Building

On Windows with JDK 21 available:

```powershell
.\gradlew.bat collectArtifacts --no-daemon
```

Production JARs are collected in `build/distributions/`; `-sources.jar` files are development sources and are not installable mod artifacts.

This repository uses shared sources plus loader and Minecraft-version adapters under `src/platforms/` and `src/versions/`.
