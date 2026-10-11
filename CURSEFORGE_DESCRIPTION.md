# Locate Fixer

Locate Fixer improves Minecraft's familiar `/locate structure`, `/locate biome` and `/locate poi` workflow with nearby-first search planning, a bounded queue, recent-result caches and a persistent discovery index.

Ordinary locate reports coordinates. Add `teleport` explicitly to request safe player travel:

```text
/locate structure minecraft:stronghold teleport
/locate biome minecraft:cherry_grove teleport
/locate poi minecraft:home teleport
/locate status
/locate cancel
```

Safe travel waits for actual loaded chunks and finds a naturally safe landing. It checks floor support, headroom, collision, fluids, hazards and the world border. It preserves orientation and never places, removes or replaces blocks. If the bounded search cannot find a safe destination, travel cancels. New configs have no countdown.

| Minecraft | Loader | Java | Extra requirement |
| --- | --- | --- | --- |
| 1.20.1 | Forge 47.4.10+ | 17 | None |
| 1.20.1 | Fabric | 17 | Fabric API |
| 1.21.1 | NeoForge 21.1.219+ | 21 | None |
| 1.21.1 | Fabric | 21 | Fabric API |

Install the matching JAR on the server. Locate Fixer does not require its own client installation. The `locatefixer` mod ID and existing config/world data paths stay unchanged.

Searches use active registries and vanilla generation hooks. Historical hints and indexed candidates never replace a live nearest search. Async Locator Refined conflict mode yields overlapping locate ownership; compass mods keep their own search systems.

Operator diagnostics, benchmarks, provider integration and optional nearest tools remain available. WorldEdit tracking, random dimension travel and unrelated command correction are removed from the runtime.

Workers orchestrate bounded requests, while live world queries stay on Minecraft's server thread. A generator call can still cause tick lag; background dispatch does not make world generation completely nonblocking. This development candidate still needs live modpack, multiplayer and comparative performance acceptance. See the repository's validation report before publishing.
