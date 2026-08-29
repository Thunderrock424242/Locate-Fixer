# Locate Unbound (Forge, NeoForge, and Fabric)

Locate Unbound is a server-first world discovery engine that makes locating things in Minecraft reliable on large or heavily modded worlds.

If vanilla `/locate` times out, misses distant targets, or makes staff repeat the same lookup, this mod extends the workflow with bounded search orchestration, caching, progress messages, safer follow-up teleports, and better command errors.

## Supported versions

- Minecraft 1.20.1 — Forge and Fabric
- Minecraft 1.21.1 — NeoForge and Fabric
- Fabric builds require Fabric API.
- WorldEdit is optional and enables schematic-folder integration.

Download the file matching both your exact Minecraft version and loader. Do not install more than one target JAR at the same time. The internal mod ID remains `locatefixer` for compatibility.

## Core improvements

- **Long-range locate rings** (up to 256,000 blocks by default) for distant structures and biomes.
- **Bounded locate orchestration** with background workers, server-thread-safe world access, and chat progress.
- **Memory caching and a persistent per-world index** to reduce repeated searches across restarts.
- **Adaptive search plans, cancellation, and queue status** through `/locate status` and `/locate cancel`.
- **Command error fixes** for mistyped `/locate`, `/summon`, `/give`, and `/effect` registry IDs, including clickable suggestions.
- **Safe follow-up teleports** that report actual chunk readiness, reject hazards, time out cleanly, and never intercept unrelated `/tp` commands.

## Commands

Use the normal vanilla-style commands:

- `/locate structure ...`
- `/locate biome ...`

Optional config-gated additions include:

- `/locate nearest structure <count>`
- `/locate nearest biome <count>`
- `/locate feature <namespace:id>`

Other utilities include `/locate dimension`, `/locate schematic <name>`, and the custom-provider API command `/xlocate customstructure <id>`.

Operators can use `/locateunbound diagnostics` and config-gated `/locateunbound benchmark`.

## WorldEdit integration

When WorldEdit is present, Locate Unbound scans `config/worldedit/schematics` so `.schem` files can be located by name with `/locate schematic <name>`.

## Configuration

Every loader uses `config/locatefixer-server.toml`. It controls:

- Locate ring distances
- Async worker count
- Cache duration and granularity
- Biome sampling multipliers
- POI search radius
- Registry-aware error suggestions
- Optional nearest and feature commands

Fabric rereads the file after a successful `/reload`; Forge and NeoForge use their loader config-reload events.

Locate Unbound keeps locate-based exploration and server support practical at scale.
