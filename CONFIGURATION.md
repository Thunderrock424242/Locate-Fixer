# Locate Fixer Configuration Guide

Locate Fixer exposes a server-side configuration file that lets you tune the way the mod searches for structures, biomes, and points of interest. The same settings and file name are used on Forge, NeoForge, and Fabric.

## Finding the config file

When the mod starts for the first time it writes `locatefixer-server.toml` inside the loader's standard config directory:

- **Dedicated server:** `<server root>/config/locatefixer-server.toml`
- **Single-player / client-hosted worlds:** `<minecraft directory>/config/locatefixer-server.toml`

Forge and NeoForge apply changes when their loader config-reload event fires. Fabric rereads the file when the server starts and after a successful `/reload`. Restarting the server remains a reliable fallback on every loader.

## Editing tips

1. Stop the server or pause the single-player game before doing large edits. This ensures the file is not overwritten while you work.
2. Keep the file in valid [TOML](https://toml.io/en/) format. Most settings are nested inside the `[locate]`, `[commands]`, or `[poi]` section; legacy toggles may appear at the top level.
3. On Fabric, run `/reload` after saving. On Forge or NeoForge, use the loader's normal config-reload workflow. Watch the server log for errors; Fabric keeps the last valid values when it cannot read the file.

## Available settings

All values listed below match the defaults that ship with the mod.

### `[locate]`

| Key | Default | Description |
| --- | --- | --- |
| `locateRings` | `[6400, 16000, 32000, 64000, 128000, 256000]` | Ordered list of radii (in blocks) that the locate command searches through. Increase or decrease entries to scan different distances. Each value must be a positive integer. |
| `locateThreadCount` | `1` | Number of background workers that orchestrate locate requests. Live chunk, POI, and mod-owned world access is still serialized onto Minecraft's server thread for safety. |
| `cacheDurationMinutes` | `30` | Minutes that successful locate results stay cached before expiring. Longer durations reduce work at the cost of stale data. Allowed range is 1–240 minutes. |
| `cacheChunkGranularity` | `8` | Chunk granularity used when caching locate results. Higher numbers share cached results across a wider area; lower numbers increase precision. Valid range is 1–128. |
| `biomeSampleRadiusMultiplier` | `1.5` | Multiplier applied to the computed biome sample radius to reduce sample density. Increase it to check fewer sample points per ring (minimum 1.0, maximum 8.0). |
| `biomeSampleStepMultiplier` | `1.75` | Multiplier applied to the computed biome sample step to reduce sample density. Higher values increase the step size between samples (minimum 1.0, maximum 8.0). |
| `enableFeatureLocateCommand` | `false` | Enables `/locate feature <placed_feature_id>` for scanning nearby biome generation settings for a matching placed feature (for example tree features). |

### Top-level settings

| Key | Default | Description |
| --- | --- | --- |
| `enableNearestCommand` | `false` | Enables the operator-only `/locate nearest structure <count>` and `/locate nearest biome <count>` commands. |

### `[commands]`

| Key | Default | Description |
| --- | --- | --- |
| `enableCommandErrorFixer` | `true` | Rewrites vague command parse errors for `/locate`, `/summon`, `/give`, and `/effect` with registry-aware fuzzy suggestions that can be clicked to refill the fixed command. |

### `[poi]`

| Key | Default | Description |
| --- | --- | --- |
| `poiSearchRadius` | `256` | Radius in blocks used when scanning for points of interest. Raise it to look farther away or lower it for faster but more localized searches. Allowed range is 16–4096 blocks. |

## Advanced usage

- The `locateRings` list can be tailored for custom world scales. For example, skyblock-style worlds can use a much smaller set like `[1024, 2048, 4096]` to reduce wasted scans.
- If you install heavy structure packs, increase `locateThreadCount` and `cacheDurationMinutes` to balance the additional work.
- Combining a higher `cacheChunkGranularity` with longer cache durations is useful on exploration-heavy servers where many players run locate commands in the same region.

Feel free to experiment—the mod clamps every value to the safe ranges listed above, so an out-of-range number will snap to the nearest valid bound instead of crashing the server.
