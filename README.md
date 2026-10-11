# Locate Fixer

![Locate Unbound engine logo](branding/locate-unbound-logo-128.png)

Locate Fixer is a server-first locate utility powered by the Locate Unbound discovery engine. Install it and use Minecraft's familiar `/locate` commands. Adaptive planning, recent-search caching, and a persistent world discovery index are enabled by default. The technical ID remains `locatefixer`.

## Supported targets

Install the JAR matching the server's exact Minecraft version and loader. No Locate Fixer client installation is required.

| Minecraft | Loader | Java | Extra requirement |
| --- | --- | --- | --- |
| 1.20.1 | Forge 47.4.10+ | 17 | None |
| 1.20.1 | Fabric | 17 | Fabric API |
| 1.21.1 | NeoForge 21.1.219+ | 21 | None |
| 1.21.1 | Fabric | 21 | Fabric API |

## Locate and optional travel

These retain vanilla argument parsing and operator permission level 2:

```text
/locate structure minecraft:stronghold
/locate biome minecraft:cherry_grove
/locate poi minecraft:home
```

Ordinary locate reports coordinates and distance. Clicking coordinates suggests text for copying; it does not start teleport preparation. To request safe travel explicitly, a player can add `teleport`:

```text
/locate structure minecraft:stronghold teleport
/locate biome minecraft:cherry_grove teleport
/locate poi minecraft:home teleport
/locate status
/locate cancel
```

Travel prepares only the chunks covering a bounded landing search. It waits for FULL chunks, searches naturally safe floors and headroom near the target height, checks collision, fluids, hazards and the world border, then revalidates immediately before moving the player. It never places, removes or replaces blocks. If no safe landing exists, it cancels. New configurations use no countdown; an existing configured countdown is respected. `/locate cancel` cancels either searching or travel preparation. Other `/tp` commands retain vanilla behavior.

Searches distinguish a target unavailable in the dimension, no result within the search limit, cancellation, timeout, and an execution error. Native biome search keeps vanilla sampling; it does not exhaustively inspect every block. Structure results use Minecraft's native placement search semantics.

Vanilla commands with result callbacks, including `/execute store`, keep synchronous native handling. RCON also uses native handling because its response must be returned immediately. The explicit teleport suffix is an asynchronous player action.

## Search ownership and performance

The shared path uses a bounded job queue, compatible recent results, indexed candidates, nearby-first search stages, and the selected backend. Previous successes may add a useful radius; speculative failures never remove nearby stages. An indexed location adds a candidate search radius rather than being returned directly as the nearest result. Live generator/provider queries still establish the answer.

Caches distinguish exact XYZ origin, target, dimension, radius, world and registry lifetime. The world index in `world/data/locatefixer_index.dat` is bounded, dimension-aware and spatially partitioned. Restarted or expired entries require live verification; datapack reload invalidates discoveries and cancels old jobs. Malformed records are isolated, old supported data migrates, and unknown newer schemas are preserved without writes.

Workers handle orchestration. Minecraft world generation, biome/POI queries, providers, SavedData and teleporting stay on the server thread. Worker waits check cancellation and deadlines. A queued call can be abandoned; an already-running vanilla worldgen call cannot be safely interrupted and can stall a server tick. More workers do not make those calls parallel or eliminate this limitation. Normal output does not generate extra chunks to refine a structure center.

## Optional operator tools

- `/locate nearest` remains opt-in with `enableNearestCommand`; it is an aggregate sampling tool.
- `/xlocate customstructure <id>` supports unified and legacy providers.
- `/locatefixer diagnostics` shows resource limits, backend ownership and detected integrations. `/locateunbound` is retained as an alias.
- `/locatefixer benchmark` requires `benchmarkEnabled`. It reports job wall time, server-call/wait timings, cache/index counters, elapsed intervals between ticks overlapping those calls, and Minecraft's recent tick samples. Neither measure attributes other server work exclusively to the job.
- The legacy feature-capability command remains opt-in. It identifies a suitable biome rather than proving an exact placed feature.

Async Locator Refined detection yields overlapping native locate ownership by default. The optional teleport suffix is omitted in that mode. Modded structures and biomes use the active registries and biome source; compass mods keep their own searches. See the [compatibility and validation report](docs/4.0-validation.md) for source review, issue #89, build evidence and remaining live tests.

## Migration

The mod ID, Java packages, provider registration APIs, config keys and world-data filename remain stable. WorldEdit schematic commands/tracking, random dimension travel and generic give/summon/effect correction are no longer registered or started. Use WorldEdit's own tools or vanilla `/execute in <dimension> run tp ...` for those tasks. Deprecated public helper classes remain available; they are not active integrations. Legacy teleport coordinate grants no longer activate `/tp` interception; integrations should use the explicit safe-travel entry point. See [API_DOCUMENTATION.md](API_DOCUMENTATION.md).

The config filename remains `locatefixer-server.toml`. Forge/NeoForge server configs may be world-scoped by the loader. See [CONFIGURATION.md](CONFIGURATION.md) for practical settings and retained compatibility keys.

## Building

`main` is the development branch for all supported Minecraft versions and loaders. One checkout contains the shared implementation and all four Gradle targets; develop and submit pull requests against `main`.

With JDK 21 available on Windows:

```powershell
.\gradlew.bat test collectArtifacts --no-daemon --console=plain
```

This command tests and builds every target, then collects its production JAR in `build/distributions/`. For a single target, use its Gradle project:

| Target | Build command |
| --- | --- |
| Forge 1.20.1 | `.\gradlew.bat :forge-1.20.1:build` |
| Fabric 1.20.1 | `.\gradlew.bat :fabric-1.20.1:build` |
| NeoForge 1.21.1 | `.\gradlew.bat :neoforge-1.21.1:build` |
| Fabric 1.21.1 | `.\gradlew.bat :fabric-1.21.1:build` |

GitHub Actions builds this same matrix for pushes to `main`, pull requests targeting `main`, and manual runs. Source JARs are not installable mods. Shared sources live under `src/main/`, with loader and Minecraft adapters under `src/platforms/` and `src/versions/`. The 4.0 code remains a development candidate; automated build evidence does not establish gameplay, multiplayer, modpack or comparative performance acceptance.

For the optional Forge 1.20.1 / Ixeris 4.6.5 development client:

```powershell
.\gradlew.bat :forge-1.20.1:runClientIxeris -PixerisTest --no-daemon --console=plain
```

This profile uses Forge 47.4.20 and a separate game directory under `build/smoke/forge-ixeris-client/`. See the [issue #89 reproduction notes](docs/ixeris-issue-89.md) for the automated disposable-world probe and observed results. Ixeris is a development runtime dependency only; normal builds keep Forge 47.4.10 and do not include it.
