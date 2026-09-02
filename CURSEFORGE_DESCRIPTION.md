# Locate Unbound

## Find more of your world without fighting `/locate`

Locate Unbound upgrades Minecraft's normal locate workflow for large worlds, modpacks, and long-running servers. It keeps the familiar vanilla commands, then adds staged long-distance searches, visible progress, cancellation, reusable results, better error messages, and a safer way to travel to what you find.

Whether a structure generated far beyond vanilla's practical search range, a modded biome is difficult to track down, or several players are asking the server to search at once, Locate Unbound keeps the work bounded and understandable.

## Why use Locate Unbound?

- **Search farther:** structure and biome searches expand through configurable distance stages, reaching up to **256,000 blocks by default**.
- **Keep the server responsive:** locate requests use a bounded queue and background orchestration while Minecraft world access remains on the server thread.
- **Stop repeating expensive searches:** recent results use a fast memory cache, while verified discoveries can be remembered in the world save across restarts.
- **See what is happening:** progress messages, `/locate status`, clean timeouts, and `/locate cancel` replace the feeling of a command silently hanging.
- **Work with modded world generation:** searches use the active world's registries, biome source, dimensions, and generator instead of relying on a hard dependency on one worldgen mod.
- **Travel more safely:** clickable locate results can preload the destination, check for hazards, prefer a safe surface, and cancel cleanly if the destination never becomes ready.
- **Recover from typos:** registry-aware suggestions improve errors for supported `/locate`, `/summon`, `/give`, and `/effect` IDs, including clickable corrections when a close match exists.

## Supported Minecraft versions and loaders

| Minecraft | Loader | Java | Additional requirement |
| --- | --- | --- | --- |
| 1.20.1 | Forge 47.4.10+ | Java 17 | None |
| 1.20.1 | Fabric | Java 17 | Fabric API |
| 1.21.1 | NeoForge 21.1.219+ | Java 21 | None |
| 1.21.1 | Fabric | Java 21 | Fabric API |

Download the file that matches **both** your Minecraft version and mod loader. Install only one Locate Unbound JAR in an instance. Fabric installations also need Fabric API.

Locate Unbound is the new public name for Locate Fixer. Its internal mod ID remains `locatefixer`, preserving the established config filename, resource namespace, API packages, and saved-world data identity.

## Familiar commands, stronger searches

The normal Minecraft commands remain the main way to use the mod:

- `/locate structure <structure>`
- `/locate biome <biome>`
- `/locate poi <point_of_interest>`
- `/locate status`
- `/locate cancel`

Locate Unbound also provides:

- `/locate dimension <dimension> [biome]` to find and safely travel to a destination in another dimension.
- `/locate schematic <name>` for WorldEdit schematic anchors recorded during the current server session.
- `/locate schematic record <name>` lets an operator record the current position after pasting that schematic.
- `/xlocate customstructure <id>` for locations exposed through the provider API or legacy custom-structure integration.
- `/locateunbound diagnostics` for an operator view of workers, queue state, caches, the world index, backends, integrations, and configured search range.

Two additional operator tools are disabled by default and can be enabled in the server config:

- `/locate nearest structure <count>` and `/locate nearest biome <count>`
- `/locate feature <namespace:id>` finds the nearest biome capable of generating the feature; it does not claim an exact placed feature exists at that position.

Benchmark capture is also optional. When enabled, `/locateunbound benchmark` reports metrics from the operator's latest search.

## Long-range discovery that learns from earlier searches

Locate Unbound searches through configurable radius stages instead of treating every request as one unbounded operation. The default stages are 6,400, 16,000, 32,000, 64,000, 128,000, and 256,000 blocks.

Successful and unsuccessful distance history can help later searches avoid stages already known to be unhelpful. Recent matches are kept in memory, and the persistent world index stores eligible verified discoveries in:

`world/data/locatefixer_index.dat`

The index is dimension-aware, bounded, expiration-aware, and tolerant of malformed entries. Turning it off does not delete existing index data.

## Safer travel after a locate

Coordinates returned by Locate Unbound can include a clickable teleport action. That action creates one short-lived authorization for the exact located destination; ordinary `/tp` commands are left alone.

Before moving the player, Locate Unbound:

- Temporarily preloads only the configured area around the destination.
- Reports actual chunk readiness and stops after a configurable timeout.
- Runs a countdown that can be cancelled with `/locate cancel`.
- Searches the destination surface first.
- For underground targets without a safe surface, evaluates nearby natural landings and a conservative small safety-pocket fallback.
- Rejects dangerous landings such as void space, suffocation, lava, fire, campfires, powder snow, unsupported positions, and falling-block floors by default.
- Avoids treating the Nether roof as a normal surface.
- Releases only the temporary chunk tickets created for that Locate Unbound travel request.

Water, lava, fire, and powder-snow landing rules can be changed by server administrators.

## Modded-world and optional-mod behavior

Locate Unbound keeps the active world generator and registries authoritative, including worlds created with registry-based or TerraBlender-powered biome mods.

It also detects several optional mods without making them required dependencies:

- **BiomeSpy:** Locate Unbound preserves Minecraft's normal lower-level biome and structure calls so BiomeSpy can continue optimizing them.
- **Nature's Compass and Explorer's Compass:** each compass mod keeps ownership of its own searches; Locate Unbound does not replace their worker systems.
- **Async Locator Refined:** conflict-safe mode yields vanilla `/locate` interception when a recognized installation is detected, preventing two mods from running the same search.
- **WorldEdit:** `.schem` names are discovered from `config/worldedit/schematics` for suggestions. After a paste, stand at its anchor and run `/locate schematic record <name>`; this explicit workflow avoids guessing from unstable WorldEdit edit-session events.

These are compatibility and coexistence paths, not hard dependencies. Third-party mods can expose additional targets through Locate Unbound's provider API.

## Configuration

All loaders retain the compatibility-friendly config filename:

`config/locatefixer-server.toml`

Server administrators can configure:

- Search distance stages and worker count
- Queue size and search timeout
- Memory-cache lifetime and capacity
- Persistent-index limits, expiry, and verification windows
- Biome sampling behavior and POI radius
- Teleport preload radius, countdown, timeout, and landing rules
- Command correction suggestions
- Optional nearest, feature, benchmark, and compatibility controls

Fabric rereads a valid config after `/reload`. Forge and NeoForge use their loader config-reload events; restarting the server remains the reliable fallback on every loader.

## Built for servers, useful everywhere

Locate Unbound does not promise that every target exists or that every distant search will finish instantly. World generation remains authoritative. What the mod provides is a controlled way to search farther, reuse discoveries, understand progress, stop work you no longer need, and travel without turning every locate result into a risky blind teleport.

Explore beyond vanilla's practical limits—and keep control of the journey.
