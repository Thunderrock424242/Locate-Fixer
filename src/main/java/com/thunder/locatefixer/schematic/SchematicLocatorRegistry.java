package com.thunder.locatefixer.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

public class SchematicLocatorRegistry {

    @FunctionalInterface
    public interface CustomStructureLocator {
        Optional<BlockPos> find(ServerLevel level, BlockPos origin, int maxRadius);
    }

    private static final Map<String, CustomStructureLocator> LOCATORS = new LinkedHashMap<>();
    private static final Map<String, BlockPos> SCHEMATIC_POSITIONS = new HashMap<>();

    // Register for non-vanilla structures (via other mods)
    public static void register(String id, CustomStructureLocator locator) {
        LOCATORS.put(id.toLowerCase(Locale.ROOT), locator);
    }

    public static Optional<BlockPos> locate(String id, ServerLevel level, BlockPos origin, int maxRadius) {
        String key = id.toLowerCase(Locale.ROOT);
        if (LOCATORS.containsKey(key)) {
            return LOCATORS.get(key).find(level, origin, maxRadius);
        }
        return Optional.ofNullable(SCHEMATIC_POSITIONS.get(key));
    }

    public static boolean isRegistered(String id) {
        return LOCATORS.containsKey(id.toLowerCase(Locale.ROOT)) || SCHEMATIC_POSITIONS.containsKey(id.toLowerCase(Locale.ROOT));
    }

    public static Set<String> getAllRegisteredIds() {
        Set<String> all = new LinkedHashSet<>(LOCATORS.keySet());
        all.addAll(SCHEMATIC_POSITIONS.keySet());
        return all;
    }

    // Auto-detect .schem files from WorldEdit folder
    public static void scanWorldEditSchematicsFolder() {
        Path schemFolder = Paths.get("config", "worldedit", "schematics");
        if (!Files.exists(schemFolder)) return;

        try (Stream<Path> stream = Files.walk(schemFolder)) {
            stream.filter(p -> p.toString().endsWith(".schem"))
                    .forEach(file -> {
                        String name = file.getFileName().toString().replace(".schem", "").toLowerCase(Locale.ROOT);

                        if (!SCHEMATIC_POSITIONS.containsKey(name)) {
                            BlockPos dummy = new BlockPos(0, 80, 0); // Default if no metadata
                            SCHEMATIC_POSITIONS.put(name, dummy);
                            System.out.println("[LocateFixer] Found schematic: " + name);
                        }
                    });
        } catch (IOException e) {
            System.err.println("[LocateFixer] Failed to scan schematics: " + e.getMessage());
        }
    }
}