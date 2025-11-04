# Locate Fixer Configuration Guide

Locate Fixer exposes a server-side configuration file that lets you tune the way the mod searches for structures, biomes, and points of interest. This document walks through where to find the config, how it is reloaded, and what every option does.

## Finding the config file

When the mod starts for the first time it writes a `locatefixer-server.toml` file inside the standard NeoForge config directory:

- **Dedicated server:** `<server root>/config/locatefixer-server.toml`
- **Single-player / client-hosted worlds:** `<minecraft directory>/config/locatefixer-server.toml`

Changes to this file automatically propagate when the server reloads configs (for example with `/reload`) or whenever the file is saved while the game is running. The mod also refreshes its cache whenever the config is loaded, so you do not need to restart the server after editing it.

## Editing tips

1. Stop the server or pause the single-player game before doing large edits. This ensures the file is not overwritten while you work.
2. Keep the file in valid [TOML](https://toml.io/en/) format. Every setting is nested inside either the `[locate]` or `[poi]` section.
3. After saving, watch the server log for messages about the config reloading. Any syntax error will appear there and the previous values will remain active.

## Available settings

All values listed below match the defaults that ship with the mod.

### `[locate]`

| Key | Default | Description |
| --- | --- | --- |
| `locateRings` | `[6400, 16000, 32000, 64000, 128000, 256000]` | Ordered list of radii (in blocks) that the locate command searches through. Increase or decrease entries to scan different distances. Each value must be a positive integer. |
| `locateThreadCount` | `1` | Number of asynchronous worker threads that process locate requests. Set to a higher value (up to 8) to parallelize searches on large servers, or leave at `1` to process sequentially. |
| `cacheDurationMinutes` | `30` | Minutes that successful locate results stay cached before expiring. Longer durations reduce work at the cost of stale data. Allowed range is 1–240 minutes. |
| `cacheChunkGranularity` | `8` | Chunk granularity used when caching locate results. Higher numbers share cached results across a wider area; lower numbers increase precision. Valid range is 1–128. |
| `biomeSampleRadiusMultiplier` | `1.5` | Multiplier applied to the computed biome sample radius to reduce sample density. Increase it to check fewer sample points per ring (minimum 1.0, maximum 8.0). |
| `biomeSampleStepMultiplier` | `1.75` | Multiplier applied to the computed biome sample step to reduce sample density. Higher values increase the step size between samples (minimum 1.0, maximum 8.0). |

### `[poi]`

| Key | Default | Description |
| --- | --- | --- |
| `poiSearchRadius` | `256` | Radius in blocks used when scanning for points of interest. Raise it to look farther away or lower it for faster but more localized searches. Allowed range is 16–4096 blocks. |

## Advanced usage

- The `locateRings` list can be tailored for custom world scales. For example, skyblock-style worlds can use a much smaller set like `[1024, 2048, 4096]` to reduce wasted scans.
- If you install heavy structure packs, increase `locateThreadCount` and `cacheDurationMinutes` to balance the additional work.
- Combining a higher `cacheChunkGranularity` with longer cache durations is useful on exploration-heavy servers where many players run locate commands in the same region.

Feel free to experiment—the mod clamps every value to the safe ranges listed above, so an out-of-range number will snap to the nearest valid bound instead of crashing the server.
