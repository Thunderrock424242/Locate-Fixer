# Locate Fixer

Locate Fixer is a server-first quality-of-life mod for Forge, NeoForge, and Fabric. It makes Minecraft's `/locate`, `/locate biome`, and `/tp` workflows more reliable on large or heavily modded worlds by extending searches, reporting progress, caching results, and preparing safe teleports.

## Supported targets

Each Minecraft version and loader has its own JAR. Install only the file matching the server's exact combination.

| Minecraft | Loader | Java | Extra requirement |
| --- | --- | --- | --- |
| 1.20.1 | Forge 47.4.10+ | 17 | None |
| 1.20.1 | Fabric | 17 | Fabric API |
| 1.21.1 | NeoForge 21.1.219+ | 21 | None |
| 1.21.1 | Fabric | 21 | Fabric API |

WorldEdit is optional on every target. When present, Locate Fixer enables its schematic-folder integration.

## Highlights

- **Escalating search radii.** Locate rings climb from 6,400 blocks up to 256,000 blocks by default, so far-away structures and modded biomes can be discovered.
- **Bounded locate workers.** Commands are orchestrated in a bounded background pool while access to live chunks, POIs, and mod-owned world state is handed to the server thread.
- **Smart caching.** Recently found structures and biomes can be reused for nearby requests instead of repeating the same search.
- **Nearest X mode.** When enabled, `/locate nearest structure <count>` and `/locate nearest biome <count>` list multiple matches.
- **Command error fixer.** Mistyped registry IDs in `/locate`, `/summon`, `/give`, and `/effect` receive clearer errors with clickable suggestions.
- **Safer teleports.** Teleporting to a recent locate result preloads the destination chunks, shows a countdown, and moves the player after the destination is ready.
- **Schematic helpers.** `/locate schematic <name>` discovers files in WorldEdit's `config/worldedit/schematics` folder.

## Installation

1. Choose the JAR matching the Minecraft version and loader in the table above.
2. Install it in the server's `mods` directory. Install Fabric API as well on Fabric.
3. Client installation is optional, but using the same mod set on both sides is recommended.
4. Start the game or server once to generate `config/locatefixer-server.toml`.

Do not install two Locate Fixer target JARs together.

## Modded world-generation compatibility

Locate Fixer resolves targets from the active world's registries and biome source instead of using a vanilla-only biome or structure list. Registry-based world-generation mods, including TerraBlender-based biome mods, therefore participate in normal `/locate structure` and `/locate biome` searches without a dedicated integration.

Custom dimensions are supported when their chunk generator exposes its possible biomes and implements Minecraft's standard locate behavior. Structures placed outside Minecraft's structure registry can use Locate Fixer's custom provider API instead.

## Configuration

Locate Fixer uses `config/locatefixer-server.toml` on every supported loader. Important settings include:

- `locate.locateRings` — ordered block radii used by escalating searches.
- `locate.locateThreadCount` — async worker count from 1 to 8.
- `locate.cacheDurationMinutes` — how long successful results stay cached.
- `locate.cacheChunkGranularity` — how broadly nearby requests share cached results.
- `locate.biomeSampleRadiusMultiplier` and `locate.biomeSampleStepMultiplier` — biome sampling controls.
- `locate.enableFeatureLocateCommand` — enables `/locate feature <placed_feature_id>`; default `false`.
- `enableNearestCommand` — enables the operator-only `/locate nearest` branch; default `false`.
- `commands.enableCommandErrorFixer` — enables registry-aware suggestions; default `true`.
- `poi.poiSearchRadius` — maximum point-of-interest search radius.

Forge and NeoForge apply loader config reload events. Fabric rereads the same file after a successful `/reload`. See [CONFIGURATION.md](CONFIGURATION.md) for the complete guide.

## Usage

1. Run `/locate structure` or `/locate biome` normally. Progress messages show the active search radius.
2. Enable `enableNearestCommand` to use `/locate nearest structure <count>` or `/locate nearest biome <count>` as an operator.
3. Optionally enable `/locate feature <namespace:id>` through its config setting.
4. Click a suggested correction after mistyping a supported biome, structure, entity, item, or effect ID.
5. Use `/tp` immediately after a locate result to let Locate Fixer prepare the target area before teleporting.
6. Put `.schem` files in `config/worldedit/schematics/` to discover them with `/locate schematic <name>`.

## API integration

Mods that place structures through custom systems can register a provider for `/xlocate customstructure <id>`. See [API_DOCUMENTATION.md](API_DOCUMENTATION.md) for the interface and examples.

## Building all targets

The repository is one Gradle project with shared sources plus small loader- and version-specific source sets:

```text
src/main/                    shared implementation
src/versions/                Minecraft-version adapters
src/platforms/               loader adapters and metadata
targets/                     four build targets
```

On Windows:

```powershell
.\gradlew.bat collectArtifacts --no-daemon --no-problems-report
```

On Linux or macOS:

```bash
./gradlew collectArtifacts --no-daemon --no-problems-report
```

The four production JARs are collected in `build/distributions/`. Individual targets can be built with tasks such as `:forge-1.20.1:build` or `:fabric-1.21.1:build`.

For an opt-in NeoForge 1.21.1 development check with TerraBlender and Biomes O' Plenty on the runtime classpath, use:

```powershell
.\gradlew.bat :neoforge-1.21.1:runServer -PterrablenderTest --no-daemon
```

The profile is test-only; TerraBlender and Biomes O' Plenty are not bundled or required by Locate Fixer.
