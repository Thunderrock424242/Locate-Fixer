# Locate Fixer (Forge, NeoForge, and Fabric)

Locate Fixer is a server-first quality-of-life mod that makes locating things in Minecraft reliable again on big or heavily modded worlds.

If vanilla `/locate` times out, misses distant targets, or makes staff repeat the same lookup, this mod extends the workflow with bounded search orchestration, caching, progress messages, safer follow-up teleports, and better command errors.

## Supported versions

- Minecraft 1.20.1 — Forge and Fabric
- Minecraft 1.21.1 — NeoForge and Fabric
- Fabric builds require Fabric API.
- WorldEdit is optional and enables schematic-folder integration.

Download the file matching both your exact Minecraft version and loader. Do not install more than one Locate Fixer JAR at the same time.

## Core improvements

- **Long-range locate rings** (up to 256,000 blocks by default) for distant structures and biomes.
- **Bounded locate orchestration** with background workers, server-thread-safe world access, and chat progress.
- **Locate result caching** to reduce repeated nearby searches.
- **Command error fixes** for mistyped `/locate`, `/summon`, `/give`, and `/effect` registry IDs, including clickable suggestions.
- **Safe follow-up teleports** that prepare the destination chunks before moving the player.

## Commands

Use the normal vanilla-style commands:

- `/locate structure ...`
- `/locate biome ...`

Optional config-gated additions include:

- `/locate nearest structure <count>`
- `/locate nearest biome <count>`
- `/locate feature <namespace:id>`

Other utilities include `/locate dimension`, `/locate schematic <name>`, and the custom-provider API command `/xlocate customstructure <id>`.

## WorldEdit integration

When WorldEdit is present, Locate Fixer scans `config/worldedit/schematics` so `.schem` files can be located by name with `/locate schematic <name>`.

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

Locate Fixer keeps locate-based exploration and server support practical at scale.
