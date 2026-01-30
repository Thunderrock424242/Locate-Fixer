package com.thunder.locatefixer.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import com.thunder.locatefixer.locatefixer;

public class SchematicLocatorRegistry {

    @FunctionalInterface
    public interface CustomStructureLocator {
        Optional<BlockPos> find(ServerLevel level, BlockPos origin, int maxRadius);
    }

    private static final Map<String, CustomStructureLocator> LOCATORS = new ConcurrentHashMap<>();
    private static final Map<String, BlockPos> SCHEMATIC_POSITIONS = new ConcurrentHashMap<>();

    private static String normalizeId(String id) {
        return Objects.requireNonNull(id, "id").toLowerCase(Locale.ROOT);
    }

    // Register for non-vanilla structures (via other mods)
    public static void register(String id, CustomStructureLocator locator) {
        LOCATORS.put(normalizeId(id), locator);
    }

    public static void registerSchematicPosition(String id, BlockPos pos) {
        SCHEMATIC_POSITIONS.put(normalizeId(id), Objects.requireNonNull(pos, "pos"));
    }

    public static Optional<BlockPos> locate(String id, ServerLevel level, BlockPos origin, int maxRadius) {
        String key = normalizeId(id);
        if (LOCATORS.containsKey(key)) {
            return LOCATORS.get(key).find(level, origin, maxRadius);
        }
        return Optional.ofNullable(SCHEMATIC_POSITIONS.get(key));
    }

    public static boolean isRegistered(String id) {
        String key = normalizeId(id);
        return LOCATORS.containsKey(key) || SCHEMATIC_POSITIONS.containsKey(key);
    }

    public static Set<String> getAllRegisteredIds() {
        Set<String> all = new TreeSet<>(LOCATORS.keySet());
        all.addAll(SCHEMATIC_POSITIONS.keySet());
        return Collections.unmodifiableSet(all);
    }

    // Auto-detect .schem files from WorldEdit folder
    public static void scanWorldEditSchematicsFolder() {
        Path schemFolder = Paths.get("config", "worldedit", "schematics");
        if (!Files.exists(schemFolder)) {
            return;
        }

        try (Stream<Path> stream = Files.walk(schemFolder)) {
            stream.filter(p -> p.toString().endsWith(".schem"))
                    .forEach(file -> {
                        String name = file.getFileName().toString().replace(".schem", "");
                        SCHEMATIC_POSITIONS.computeIfAbsent(normalizeId(name), key -> {
                            locatefixer.LOGGER.info("[LocateFixer] Found schematic: {}", name);
                            return new BlockPos(0, 80, 0); // Default if no metadata
                        });
                    });
        } catch (IOException e) {
            locatefixer.LOGGER.error("[LocateFixer] Failed to scan schematics: {}", e.getMessage());
        }
    }
}
