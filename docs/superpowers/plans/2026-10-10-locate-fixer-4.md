# Locate Fixer 4.0 implementation plan

> **For agentic workers:** Use superpowers:executing-plans or focused parallel
> agents to implement the independent stages with regression tests first.

**Goal:** Deliver the requested safe, simple Locate Fixer utility while retaining
the discovery functionality and public compatibility seams.

**Architecture:** Keep shared backends, a bounded job owner, nearby-first planner,
memory cache and world index. Live world access stays on the server thread.
Explicit teleport uses loader ticks and operation-owned temporary region tickets.

**Tech Stack:** Java 17/21, Brigadier, Minecraft 1.20.1/1.21.1,
Forge/Fabric/NeoForge, Gradle and JUnit 5.

**Spec:** User's supplied Locate Fixer 4.0 task; implementation decisions and audit
are recorded in `docs/4.0-audit.md`.

## Global constraints

- Base on `1.21.1`; preserve older `main` and published releases.
- Preserve `locatefixer`, world compatibility and existing configuration keys.
- Never modify blocks for teleportation or run unsafe generation concurrently.
- Indexed candidates and historical hints cannot override closer undiscovered results.
- Ordinary locate preserves permission 2 and never teleports automatically.
- No claim of completely nonblocking searches or fabricated performance results.

## Review focus

- Datapack reload during a queued world callback must not repopulate stale caches.
- Same-name players/console sources and repeated server starts must not share jobs.
- Cancelling a queued search must reclaim queue capacity immediately.
- Overlapping teleports must retain independent destination tickets.
- Nether/underground/custom-height landing searches must remain read-only and bounded.

### Stage 1: Search correctness and persistence

Files: shared search/cache/index classes and tests; both AsyncLocateHandler and
WorldLocatorIndex adapters.

- [x] Write and run regressions for near-stage retention, exact origin cache scope,
  expiry, index reload/corruption and candidates that cannot establish nearest.
- [x] Fix scope and planning without speculative skipped stages; keep configured bounds.
- [x] Treat index entries as candidates, invalidate on reload and bound target queries.
- [x] Bound server waits and record actual server call duration; avoid extra output loads.
- [x] Run shared tests on every supported target.

### Stage 2: Natural safe teleport

Files: shared teleport handler, read-only landing and ticket helpers, tests.
Consumes loader callback `LocateTeleportHandler.tick(MinecraftServer)`.

- [x] Reproduce unsafe landing/resource ownership in regression fixtures.
- [x] Remove carving, use FULL loaded chunks and natural collision-safe positions.
- [x] Use unique region tickets, bounded active operations and incremental scan work.
- [x] Revalidate, preserve orientation and clean all terminal outcomes.
- [x] Run teleport regressions on each target.

### Stage 3: Command boundaries

Files: version LocateTeleportCommand/LocateCommandMixin/LocateResultHelper,
CommandErrorFixer, Fabric error mixins, mixin JSON and command tests.
Produces `LocateTeleportCommand.register(CommandDispatcher<CommandSourceStack>)`.

- [x] Test optional argument attachment/ownership and normal result callbacks.
- [x] Register explicit teleport using native argument types; no `/tp` interception.
- [x] Limit error improvements to owned, authorized locate commands.
- [x] Simplify messages and keep diagnostics/benchmarks optional.
- [x] Verify both Minecraft command adapters compile and mixins package correctly.

### Stage 4: Lifecycle, configuration and identity

Files: LocateJob/Manager/Submissions, LocateFixerMod/Runtime, loader entrypoints,
config bridges, metadata and job tests.

- [x] Write regressions for reconfiguration, shutdown/restart, cancellation races
  and queue cleanup; run RED, fix, then GREEN.
- [x] Preserve one bounded worker owner and UUID source keys; clear history on lifecycle.
- [x] Wire server tick and disconnect hooks; invalidate world index on datapack reload.
- [x] Remove schematic scans/dimension/schematic registration, retain APIs.
- [x] Default countdown to zero; preserve migrated user settings and legacy config keys.
- [x] Restore Locate Fixer public identity; build all targets.

### Stage 5: Review and release evidence

- [x] Review the integrated diff and repair important correctness findings with regressions.
- [x] Run all target unit tests and `collectArtifacts`; inspect packaged mixin/refmap data.
- [x] Attempt available dedicated-server validation and collect honest proof boundaries.
- [x] Update README, configuration docs, API migration and changelog.
- [x] Document measured timings, unverified live cases and remaining limitations.

Implementation stages completed on 2026-10-10. All four targets passed 60 tests
each and produced production JARs. NeoForge vanilla and TerraBlender/BOP smoke
checks exercised commands and lifecycle behavior. Forge live startup stopped at
the EULA gate. Release acceptance remains separate; see `docs/4.0-validation.md`
for exact evidence, measured tick delays and pending gameplay/modpack cases.
