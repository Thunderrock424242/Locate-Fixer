package com.thunder.locatefixer.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.*;

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

    private static final Map<String, CustomStructureLocator> LOCATORS = new LinkedHashMap<>();

    /**
     * Registers a custom structure locator.
     * @param id A unique string like "mymod:skycastle"
     * @param locator Logic to locate the structure
     */
    public static void register(String id, CustomStructureLocator locator) {
        LOCATORS.put(id.toLowerCase(Locale.ROOT), locator);
    }

    /**
     * Tries to locate a registered structure.
     */
    public static Optional<BlockPos> locate(String id, ServerLevel level, BlockPos origin, int maxRadius) {
        CustomStructureLocator locator = LOCATORS.get(id.toLowerCase(Locale.ROOT));
        if (locator != null) {
            return locator.find(level, origin, maxRadius);
        }
        return Optional.empty();
    }

    public static Set<String> getRegisteredStructureIds() {
        return Collections.unmodifiableSet(LOCATORS.keySet());
    }

    public static boolean isRegistered(String id) {
        return LOCATORS.containsKey(id.toLowerCase(Locale.ROOT));
    }
}