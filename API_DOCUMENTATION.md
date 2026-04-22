# Locate Fixer API for Modders

This document explains how another mod can expose non-vanilla structures to Locate Fixer.

## Goal
If your mod places structures through custom logic (instead of vanilla structure registration), you can register a custom locator so admins can run:

```mcfunction
/locate structure <your_mod:structure_id>
```

---

## 1) Implement the provider interface

Create or adapt your structure manager class to implement:

- `com.thunder.locatefixer.api.LocateFixerStructureProvider`

Required methods:

- `String locateFixerStructureId()`
- `Optional<BlockPos> locateNearest(ServerLevel level, BlockPos origin, int maxRadius)`

### Example

```java
package com.example.mymod.integration;

import com.thunder.locatefixer.api.LocateFixerStructureProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Optional;

public final class SkyFortressLocator implements LocateFixerStructureProvider {

    @Override
    public String locateFixerStructureId() {
        return "mymod:sky_fortress";
    }

    @Override
    public Optional<BlockPos> locateNearest(ServerLevel level, BlockPos origin, int maxRadius) {
        // Replace with your own search/index logic.
        // Return Optional.empty() when no match is found.
        return MyStructureIndex.findNearestSkyFortress(level.dimension(), origin, maxRadius);
    }
}
```

---

## 2) Register the provider during common setup

During your mod setup, register the provider with Locate Fixer:

```java
import com.thunder.locatefixer.api.StructureLocatorRegistry;

public final class MyMod {
    public static void onCommonSetup() {
        StructureLocatorRegistry.register(new SkyFortressLocator());
    }
}
```

You can also register with a lambda:

```java
StructureLocatorRegistry.register("mymod:sky_fortress", (level, origin, maxRadius) ->
        MyStructureIndex.findNearestSkyFortress(level.dimension(), origin, maxRadius));
```

---

## 3) Using the command

Once registered, server operators can run:

```mcfunction
/locate structure mymod:sky_fortress
```

- Suggestions for known ids are provided in command autocomplete.
- If the id is not registered, Locate Fixer returns an error message.

---

## Notes and best practices

- Keep your locator method thread-safe and fast.
  - Locate Fixer runs custom lookups asynchronously.
- Respect `maxRadius` to avoid unexpectedly expensive searches.
- Use stable ids (`namespace:path`) and keep them lowercase.
- If your structure locations are pre-indexed, query that index first for best performance.
