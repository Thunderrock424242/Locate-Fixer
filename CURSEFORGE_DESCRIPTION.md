# Locate Fixer (NeoForge 1.21.1)

Locate Fixer is a server-first quality-of-life mod that makes locating things in Minecraft reliable again on big or heavily-modded worlds.

If vanilla `/locate` times out, misses far targets, or makes staff run the same lookup repeatedly, this mod extends and stabilizes that workflow with async searching, caching, and better command utilities.

## Core improvements
- **Long-range locate rings** (default up to `256000` blocks) so distant structures/biomes are still discoverable.
- **Asynchronous locate scanning** so the server can stay responsive while searches run.
- **Locate result caching** to speed up repeated lookups in nearby areas.
- **Progress feedback in chat** while scans are in-flight.

## Command features

### Enhanced locate behavior
Use vanilla-style locate commands with improved reliability:
- `/locate structure ...`
- `/locate biome ...`

### Locate Nearest (multiple results)
Need more than one hit? Use:
- `/locate nearest structure <count>`
- `/locate nearest biome <count>`

This is especially useful for routing, exploration planning, and modpack progression design.

### Last Death (optional)
When enabled in config:
- `/locate lastdeath`

Great for recovery flows after deaths in hard worlds.

### Extra utility commands
- `/locate dimension`
- `/locate schematic <name>` (WorldEdit schematic folder integration)
- `/locate structure <namespace:id>` (custom provider/API integration)
- `/base`, `/home`, `/locate home <name>`, `/locate playerhome <player> [name]` (config-gated)

## WorldEdit integration
If WorldEdit is present, Locate Fixer scans `config/worldedit/schematics` so your `.schem` files can be located by name with `/locate schematic <name>`.

## Modpack & server-friendly configuration
Everything is server-configurable in `config/locatefixer-server.toml`, including:
- Locate ring distances
- Async worker thread count
- Cache duration and granularity
- Biome sampling multipliers
- POI search radius
- Feature toggles (including `nearest`, `lastdeath`, and base/home commands)

Config reload is supported, so you can tune behavior without full restarts.

## Who this is for
- **Players:** faster, more reliable locate workflow with less trial-and-error.
- **Server admins/staff:** better moderation/support tooling for helping players navigate.
- **Modpack authors:** predictable locate behavior across dense worldgen and large progression packs.
- **Mod developers:** register custom structure locators through the Locate Fixer API so your own generated content works with `/locate structure <id>`.

---

Locate Fixer keeps locate-based gameplay and admin support practical at scale.
