# Locate Fixer

Locate Fixer is a lightweight quality-of-life mod for NeoForge 1.21.1 that makes Minecraft's `/locate`, `/locate biome`, and `/tp` commands reliable for players, staff, and pack makers. The mod keeps searching long after vanilla would give up, streams progress back to chat, and makes teleporting to the result safe even on busy servers.

## Highlights
- **Escalating search radii.** Locate rings climb from 6,400 blocks up to 256,000 blocks, so far-flung structures and modded biomes are actually discoverable.
- **Async locate workers.** Scans run in a background thread pool while the main server thread stays responsive and keeps players moving.
- **Smart caching.** Recently found structures and biomes are cached and instantly reused for nearby requests, saving repeated scans.
- **Nearest X mode.** Use `/locatex structure <namespace:id> <count>` or `/locatex biome <namespace:id> <count>` to list multiple closest matches instead of a single locate hit.
- **Base and home tracking.** Players can save named spots with `/base set <name>` / `/home set <name>` and find them later with `/locate base <name>` or `/locate home <name>` (including shared lookups with `/locate player <player> base|home`).
- **Last death recall.** `/locate last death` returns your latest death coordinates with clickable teleport suggestions, including cross-dimension guidance.
- **Dimension and block helpers.** `/locate dimension <dimension>` picks and teleports to a random biome in the target dimension, while `/locate block <namespace:block>` scans loaded chunks for matching blocks.
- **Safer teleports.** `/tp` to a locate result preloads the destination chunks, shows a 5-second countdown, and only moves the player once everything is ready.
- **Schematic helpers.** `/locate schematic <name>` hooks into the WorldEdit `config/worldedit/schematics` folder so custom builds are easy to revisit.

## Locate Fixer vs Async Locator Mods
If you have seen mods like "Async Locator" (or similarly named async locate utilities), the goals overlap, but Locate Fixer focuses on a broader command workflow:

- **Not just async `/locate`.** Locate Fixer improves locate reliability with configurable rings and cache reuse, then ties results into safer follow-up actions.
- **Teleport safety built in.** `/tp` to locate results is chunk-preloaded with a short countdown before movement.
- **More than structures/biomes.** Includes `/locatex` nearest-multiple results plus helpers for bases/homes, last death, block lookups, dimensions, and schematics.
- **Server-ops quality of life.** Designed for pack/server administration where discoverability, chat feedback, and predictable command behavior matter.

## Configuration
Locate Fixer is configurable through the generated `config/locatefixer-server.toml` file.

- `locate.locateRings` — Ordered list of block radii to search. You can shrink or expand the search ceiling to fit your world size.
- `locate.locateThreadCount` — Number of async worker threads (1–8) that handle structure and biome scans.
- `locate.cacheDurationMinutes` — How long locate results stay cached before expiring.
- `locate.cacheChunkGranularity` — How broadly cached results can be reused around the original request.
- `locate.biomeSampleRadiusMultiplier` & `locate.biomeSampleStepMultiplier` — Tune biome sampling density for faster or more precise biome scans.
- `poi.poiSearchRadius` — Maximum radius the mod uses when scanning for points of interest.

Changes can be reloaded on the fly with standard NeoForge config reloads—no restart required.

## Usage
1. Install Locate Fixer on the server (optional but recommended on clients for consistent chat messages).
2. Run `/locate` or `/locate biome` as usual. Progress messages show which radius is currently scanning.
3. For multiple nearest matches, use `/locatex structure <namespace:id> <count>` or `/locatex biome <namespace:id> <count>`.
4. Save important spots with `/base set <name>` or `/home set <name>`, then retrieve them with `/locate base <name>` / `/locate home <name>`.
5. Use utility commands like `/locate last death`, `/locate dimension <dimension>`, `/locate block <namespace:block>`, and `/locate schematic <name>` when needed.
6. Use `/tp` immediately after locate results; the mod preloads the target area and teleports you safely once it's chunk-loaded.

## Compatibility
- NeoForge 1.21.1
- No hard dependencies
- Designed to coexist with optimization mods and structure/worldgen content packs

Whether you're hunting a single rare biome, confirming a structure generated, or guiding new players across a modded server, Locate Fixer keeps the locate workflow fast, chatty, and safe from start to finish.
