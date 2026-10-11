# Locate Fixer API

Locate Fixer 4.0 retains the Locate Unbound unified provider API for structures, biomes, POIs, placed features, and generic locations. The technical package and mod ID remain `com.thunder.locatefixer` and `locatefixer` for source, binary and world compatibility.

## Unified provider

Implement `com.thunder.locatefixer.api.LocatorProvider` and register it during common setup:

```java
LocatorProviderRegistry.register(new LocatorProvider() {
    @Override
    public String id() {
        return "mymod:sky_fortress";
    }

    @Override
    public String displayName() {
        return "Sky Fortress";
    }

    @Override
    public LocatorTargetType targetType() {
        return LocatorTargetType.CUSTOM;
    }

    @Override
    public Set<String> supportedDimensions() {
        return Set.of("minecraft:overworld");
    }

    @Override
    public int maximumRadius() {
        return 100_000;
    }

    @Override
    public LocatorCachePolicy cachePolicy() {
        return LocatorCachePolicy.PERSISTENT;
    }

    @Override
    public LocatorThreadSafety threadSafety() {
        return LocatorThreadSafety.SERVER_THREAD_ONLY;
    }

    @Override
    public int estimatedSearchCost() {
        return 20;
    }

    @Override
    public Optional<LocatorResult> locate(ServerLevel level,
                                          String targetId,
                                          BlockPos origin,
                                          int maxRadius,
                                          LocateCancellationToken token) {
        token.throwIfCancelled();
        return MySavedIndex.nearest(level.dimension(), origin, maxRadius)
                .map(position -> new LocatorResult(
                        LocatorTargetType.CUSTOM,
                        id(),
                        level.dimension().location().toString(),
                        position,
                        "mymod:saved-index",
                        "mymod-index",
                        Instant.now(),
                        true,
                        true,
                        Map.of("variant", "sky")));
    }
});
```

Provider IDs must be stable, lowercase, namespaced IDs. Duplicate IDs are rejected.

### Contract

- `supportedDimensions()` returns dimension IDs. An empty set means all dimensions.
- `maximumRadius()` is enforced against the request radius.
- `cachePolicy()` declares whether results are suitable for memory or persistent reuse.
- `threadSafety()` defaults to `SERVER_THREAD_ONLY`. The declaration remains supported, but the current integration conservatively invokes all providers on the owning server thread because they receive a live `ServerLevel`. It does not move world access off-thread based on metadata alone.
- `estimatedSearchCost()` is a relative 0–100 planning hint.
- `safelyTeleportable()` defaults to true. When false, results use plain coordinates. Provider output never grants automatic travel or intercepts `/tp`; ordinary coordinate links suggest text only.
- Check `LocateCancellationToken` during bounded loops. Cancellation is cooperative; do not interrupt a world call or retain the supplied level.
- Return positions in the declared result dimension and within the supplied maximum radius. Locate Unbound rejects out-of-scope results.
- Use `verified=false` or `generated=false` when the provider only predicts a location. Do not claim generation from placement metadata alone.

### Cache policy and verification

- `NONE` keeps results only on the completed job.
- `MEMORY` allows a bounded, short-lived process cache using exact XYZ origin, radius, world/registry/provider lifetime, configured expiry and entry cap. The legacy granularity argument remains accepted but cannot merge different origins.
- `PERSISTENT` includes memory reuse and permits world-index writes.
- Only `verified=true` results may enter the persistent world index, even when the provider requests `PERSISTENT`. Predictive or unverified results may still use memory caching when the policy allows it.

`/xlocate customstructure <id>` suggests unified `CUSTOM` and `STRUCTURE` providers as well as legacy providers.

## Backend API

`LocatorBackend` sits below orchestration and above version-specific world operations. It publishes:

- Stable backend ID and display name
- Supported `LocatorTargetType` values
- Priority and availability
- Async support declaration
- Estimated cost
- A `locate` method receiving the request, `SearchPlan`, cancellation token, and a version-specific `LocatorSearchOperation`

Registering external backends is intentionally not exposed as a global static call in 4.0.0. The registry exists as the internal selection boundary while its lifecycle and compatibility rules settle. Providers are the supported third-party extension point in this release.

## Legacy custom structures

The old API remains source compatible:

```java
StructureLocatorRegistry.register("mymod:sky_fortress", (level, origin, maxRadius) ->
        MySavedIndex.nearest(level.dimension(), origin, maxRadius));
```

`LocateFixerStructureProvider` is deprecated but not scheduled for removal. A namespaced legacy registration automatically receives a unified adapter that delegates to the newest legacy callback. Non-namespaced legacy IDs continue to work only through `StructureLocatorRegistry`.

Legacy callbacks always run on the server thread. Keep them fast and bounded.

## Persistent index behavior

Successful normal and provider searches are normalized to `LocatorResult`. Verified results are written to Minecraft SavedData when indexing and the provider's persistent policy are enabled. Providers do not write `locatefixer_index.dat` directly. All index entries require recent verification, restarted entries require live verification, and datapack reload invalidates old records. An indexed candidate can add a search stage; a live provider call must still establish the nearest answer. Normal POI commands always use the live manager.

The built-in `/locate feature` command reports a feature-capable biome rather than an exact placed-feature occurrence. Its result is marked ungenerated and unverified, is not made clickable, and is never written to the persistent index.

The index is an implementation detail, not a storage API. Its schema may migrate between releases while preserving the `locatefixer_index.dat` file.

## Removed runtime integrations and safe travel

WorldEdit schematic scanning/commands, random dimension travel and generic command correction are no longer registered or started. Their existing public classes remain for compatibility, but do not imply an active runtime integration. Prefer WorldEdit's own tools or vanilla dimension commands for those functions.

The old `LocateTeleportHandler.authorize`, `consumeAuthorization` and `createCommand` signatures remain deprecated. A grant no longer changes the behavior of `/tp`. Use `requestTeleport(source, level, target)` for explicit permission-checked player travel, or the retained `startTeleportWithPreload(player, level, target, action)` compatibility entry point. Both prepare and revalidate a naturally safe landing on the server thread. The custom action remains the integration's responsibility. Landing queries are loaded-only and throw if no safe position exists; they never carve terrain or force a synchronous chunk load.

Normal native command interception returns asynchronous admission rather than a synchronous distance. Callback-bearing vanilla commands and RCON use conservative native handling to preserve command result/response semantics. The explicit teleport suffix is a new asynchronous action and does not promise a distance return value.
