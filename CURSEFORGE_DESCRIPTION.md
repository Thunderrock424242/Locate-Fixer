Locate Fixer is a lightweight Minecraft utility mod for NeoForge 1.21.1 that improves the functionality of the /locate and /locate biome commands. Instead of giving up after a short distance, Locate Fixer intelligently expands the search radius up to 64,000 blocks (and beyond when needed), helping players and admins find biomes and structures even in rare or modded world generation setups.

Features:

Extended Locate Radius
Overrides Minecraft’s default 6,400-block search limit for biomes and structures, increasing it to 64,000 blocks with additional fallback rings up to 256,000 blocks for extreme cases.

Optimized Async Search Engine
Runs locate lookups on a dedicated async thread pool with smart radius escalation and live status updates, so the server thread stays responsive while searches continue in the background.

Smart Result Caching
Remembers the most recent locate hits for ten minutes and instantly reuses them when someone repeats the same search, cutting down on redundant scans in busy servers.

Preloaded Teleport Support
Pairs with /tp after a locate to preload the destination chunks, show a short countdown, and teleport the player once the area is safely loaded—no more void falls or lag spikes on arrival.

No New Commands
Seamlessly replaces the behavior of /locate and /locate biome using mixins. Players continue using vanilla commands with no extra setup.

Schematic/Schem Compat
Adds /locate schematic support for WorldEdit-style schematic trackers, making it easy to jump to registered builds in your world.

Improved Failure Messages
Provides more helpful error messages when no results are found—ideal for debugging or finding missing content in modpacks.

Vanilla-Compatible
Works with all existing world types and structure sets. No dependencies, configs, or GUIs required.
