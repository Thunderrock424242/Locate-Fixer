# Ixeris / Forge issue #89 reproduction

Checked on 2026-10-11 against the local Locate Fixer 4.0 candidate.

The current development client did **not** reproduce the reported crash. It loaded
Ixeris, opened single-player selection, created a new integrated world and completed
`/locate biome minecraft:plains`. This establishes the minimal development profile
below, rather than acceptance of the original large modpack or a production install.

## Repeatable development profile

The optional `-PixerisTest` flag selects these exact dependencies:

| Component | Version |
| --- | --- |
| Minecraft | 1.20.1 |
| Forge | 47.4.20 |
| Ixeris | [4.6.5+1.20.1-forge](https://modrinth.com/mod/ixeris/version/NpoM2rQr) |
| Locate Fixer | Current 4.0 development sources |
| Java used in the check | Eclipse Adoptium 17.0.15 |

Ixeris is pinned to `maven.modrinth:ixeris:NpoM2rQr`. The downloaded release JAR
has SHA-1 `0481b689f8b71cd92de8fb5eb3d221c66ecb5b80`, matching its
[official version metadata](https://api.modrinth.com/v2/version/NpoM2rQr).
ModDevGradle remaps that release for the Forge development runtime.

For manual testing, run from the repository root:

```powershell
.\gradlew.bat :forge-1.20.1:runClientIxeris -PixerisTest --no-daemon --console=plain
```

For the automated client probe:

```powershell
.\gradlew.bat :forge-1.20.1:runClientIxeris -PixerisTest -I tools/compat/ixeris-probe.gradle --no-daemon --console=plain
```

The init script adds a disposable test mod in a separate source set. It verifies
that Ixeris is loaded, opens `SelectWorldScreen`, creates a fresh creative world
with seed `4401357362987183368`, waits for the development player to join, submits
the locate command on the server thread, and requires a `FOUND` result. It then
stops the client through Minecraft's normal save/shutdown path. A three-minute
deadline reports failure if the fixture does not complete.

Both commands use `build/smoke/forge-ixeris-client/`. Each automated run creates a
new `saves/issue89-<timestamp>/` world there. Existing user saves are untouched.
Expect the `IXERIS_PROBE PASS` log marker as well as Gradle success; Gradle success
by itself does not establish that the probe ran. Logs are in that game directory's
`logs/` folder.

The normal build command remains unchanged. Without the flag, Forge stays at
47.4.10 and Ixeris is absent. Neither Ixeris nor the probe is bundled in production
JARs. Use the normal build command when collecting release artifacts.

## Observed result

The retained probe passed with Forge's client load warnings enabled. The loader's
handshake listed `minecraft`, `forge`, `ixeris`, `locatefixer`, and the disposable
probe. Ixeris's event-polling thread started and exited normally. The integrated
player joined, and the locate job returned `FOUND`, including verified coordinates.
The client saved all dimensions before exiting. No `InvalidInjectionException`
matching the issue occurred.

The final retained probe command completed with `BUILD SUCCESSFUL in 46s`. The
normal four-target `test collectArtifacts` command also succeeded afterward, using
the unchanged cached test results (60 passing tests per target). All four release
JAR hashes remained unchanged; the Forge JAR contains no Ixeris or probe entries.

The named development classpath logged a warning that Locate Fixer's refmap could
not be read. Its successful transformation here is therefore not proof of release
mapping correctness; the separate production-JAR inspection supplies that evidence.

Captured local evidence is kept in ignored `build/audit/`:

- `ixeris-4.0-integrated-world-pass.log`: first successful world/locate run.
- `ixeris-retained-probe-pass.log` and `ixeris-retained-probe-pass-debug.log`:
  retained fixture with warnings enabled, including loaded-mod and mixin evidence.

This probe invokes Minecraft's screen and world-opening APIs programmatically. It
does not simulate a mouse click through Ixeris's event callback. The named
development runtime can also resolve mixin method names differently from a
production Forge runtime, so this result alone cannot validate release refmaps.

## Evidence from the reported 3.1.0 artifact

[Issue #89](https://github.com/Thunderrock424242/Locate-Fixer/issues/89) reports
`locatefixer-3.1.0.jar` failing to inject `locateStructure` while opening the
single-player/create-world flow, with no loaded refmap. Ixeris appears in the
mouse-event stack, which does not establish that Ixeris caused the failure.

The [published Forge 3.1.0 artifact](https://modrinth.com/mod/locate-fixer/version/RcmCUTrm)
was downloaded and inspected. Its SHA-1 is
`c3e5e71c7baa4adc8133a06e56e2e3a273f09190`, matching the release metadata.
Its `mixins.locatefixer.json` requests `mixins.locatefixer.refmap.json`, but that
file is **absent from the JAR**. This is a concrete packaging defect consistent
with the reported injection failure. The current 4.0 production Forge JAR
contains that refmap and the locate method mappings.

The old 3.1.0 release was inspected, not launched in a production Forge client.
The reporter's full modpack, its Java 17.0.20 runtime, and installation of the 4.0
production JAR remain untested. No speculative Ixeris threading patch was added.
The GitHub issue has not been closed, and these local changes have not been published.
