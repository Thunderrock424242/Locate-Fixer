package com.thunder.locatefixer.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;

/**
 * Public API for LocateFixer to support mod-added structures
 * that don't use the vanilla STRUCTURE registry.
 */
public class StructureLocatorRegistry {

    /**
     * Functional interface for locating a structure.
     */
    @FunctionalInterface
    public interface CustomStructureLocator {
        Optional<BlockPos> find(ServerLevel level, BlockPos origin, int maxRadius);
    }

    private static final Map<String, CustomStructureLocator> LOCATORS = new ConcurrentHashMap<>();

    private static String normalizeId(String id) {
        return Objects.requireNonNull(id, "id").toLowerCase(Locale.ROOT);
    }

    /**
     * Registers a custom structure locator.
     *
     * @param id      a unique string like "mymod:skycastle"
     * @param locator logic to locate the structure
     */
    public static void register(String id, CustomStructureLocator locator) {
        String normalizedId = normalizeId(id);
        LOCATORS.put(normalizedId, Objects.requireNonNull(locator, "locator"));
        if (LocatorProviderRegistry.get(normalizedId).isEmpty()) {
            try {
                LocatorProviderRegistry.register(new LocatorProvider() {
                    @Override
                    public String id() {
                        return normalizedId;
                    }

                    @Override
                    public String displayName() {
                        return normalizedId;
                    }

                    @Override
                    public LocatorTargetType targetType() {
                        return LocatorTargetType.CUSTOM;
                    }

                    @Override
                    public Optional<LocatorResult> locate(ServerLevel level, String targetId, BlockPos origin,
                                                          int maxRadius,
                                                          com.thunder.locatefixer.search.LocateCancellationToken token) {
                        token.throwIfCancelled();
                        return StructureLocatorRegistry.locate(normalizedId, level, origin, maxRadius)
                                .map(position -> new LocatorResult(LocatorTargetType.CUSTOM, normalizedId,
                                        level.dimension().location().toString(), position,
                                        "locatefixer:legacy-provider", "legacy-custom-provider",
                                        Instant.now(), true, true, Map.of()));
                    }
                });
            } catch (IllegalStateException | IllegalArgumentException ignoredRegistration) {
                // A concurrent setup callback won the registration race; the adapter delegates
                // through LOCATORS. Legacy non-namespaced IDs remain supported by this bridge.
            }
        }
    }

    /**
     * Convenience overload for provider classes that implement the API.
     */
    public static void register(LocateFixerStructureProvider provider) {
        Objects.requireNonNull(provider, "provider");
        register(provider.locateFixerStructureId(), provider::locateNearest);
    }

    public static void unregister(String id) {
        LOCATORS.remove(normalizeId(id));
    }

    /**
     * Tries to locate a registered structure. Locate Unbound invokes this method on
     * Minecraft's server thread when servicing the public command.
     */
    public static Optional<BlockPos> locate(String id, ServerLevel level, BlockPos origin, int maxRadius) {
        CustomStructureLocator locator = LOCATORS.get(normalizeId(id));
        if (locator != null) {
            return locator.find(level, origin, maxRadius);
        }
        return Optional.empty();
    }

    public static Set<String> getRegisteredStructureIds() {
        return Collections.unmodifiableSet(new TreeSet<>(LOCATORS.keySet()));
    }


    public static boolean hasRegisteredStructures() {
        return !LOCATORS.isEmpty();
    }

    public static boolean isRegistered(String id) {
        return LOCATORS.containsKey(normalizeId(id));
    }
}
