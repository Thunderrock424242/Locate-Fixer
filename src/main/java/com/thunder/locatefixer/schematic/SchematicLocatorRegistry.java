package com.thunder.locatefixer.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import com.thunder.locatefixer.LocateFixerMod;

public class SchematicLocatorRegistry {

    @FunctionalInterface
    public interface CustomStructureLocator {
        Optional<BlockPos> find(ServerLevel level, BlockPos origin, int maxRadius);
    }

    private static final Map<String, CustomStructureLocator> LOCATORS = new ConcurrentHashMap<>();
    private static final Set<String> SCHEMATIC_NAMES = ConcurrentHashMap.newKeySet();
    private static final Map<String, Map<String, BlockPos>> SCHEMATIC_POSITIONS = new ConcurrentHashMap<>();

    private static String normalizeId(String id) {
        return Objects.requireNonNull(id, "id").toLowerCase(Locale.ROOT);
    }

    // Register for non-vanilla structures (via other mods)
    public static void register(String id, CustomStructureLocator locator) {
        LOCATORS.put(normalizeId(id), locator);
    }

    public static void registerSchematicPosition(String id, ServerLevel level, BlockPos pos) {
        String key = normalizeId(id);
        String dimensionId = Objects.requireNonNull(level, "level").dimension().location().toString();
        SCHEMATIC_NAMES.add(key);
        SCHEMATIC_POSITIONS.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>())
                .put(dimensionId, Objects.requireNonNull(pos, "pos").immutable());
    }

    public static Optional<BlockPos> locate(String id, ServerLevel level, BlockPos origin, int maxRadius) {
        String key = normalizeId(id);
        if (LOCATORS.containsKey(key)) {
            return LOCATORS.get(key).find(level, origin, maxRadius);
        }
        BlockPos position = Optional.ofNullable(SCHEMATIC_POSITIONS.get(key))
                .map(byDimension -> byDimension.get(level.dimension().location().toString()))
                .orElse(null);
        if (position == null || horizontalDistanceSq(origin, position) > (long) maxRadius * maxRadius) {
            return Optional.empty();
        }
        return Optional.of(position);
    }

    public static boolean isRegistered(String id) {
        String key = normalizeId(id);
        return LOCATORS.containsKey(key) || SCHEMATIC_NAMES.contains(key) || SCHEMATIC_POSITIONS.containsKey(key);
    }

    public static Set<String> getAllRegisteredIds() {
        Set<String> all = new TreeSet<>(LOCATORS.keySet());
        all.addAll(SCHEMATIC_NAMES);
        all.addAll(SCHEMATIC_POSITIONS.keySet());
        return Collections.unmodifiableSet(all);
    }

    // Auto-detect .schem files from WorldEdit folder and index their names.
    // Positions are NOT registered here because we have no reliable coordinates
    // until an operator records the pasted anchor. This scan only
    // populates the name list so /locate schematic can suggest them.
    public static void scanWorldEditSchematicsFolder() {
        Path schemFolder = Paths.get("config", "worldedit", "schematics");
        if (!Files.exists(schemFolder)) {
            return;
        }

        try (Stream<Path> stream = Files.walk(schemFolder)) {
            stream.filter(p -> p.toString().endsWith(".schem"))
                    .forEach(file -> {
                        String name = file.getFileName().toString().replace(".schem", "");
                        String key = normalizeId(name);
                        if (SCHEMATIC_NAMES.add(key)) {
                            LocateFixerMod.LOGGER.info("[LocateUnbound] Indexed schematic '{}' (record its pasted anchor with /locate schematic record {})",
                                    name, key);
                        }
                    });
        } catch (IOException e) {
            LocateFixerMod.LOGGER.error("[LocateUnbound] Failed to scan schematics: {}", e.getMessage());
        }
    }

    private static long horizontalDistanceSq(BlockPos first, BlockPos second) {
        long dx = (long) second.getX() - first.getX();
        long dz = (long) second.getZ() - first.getZ();
        return dx * dx + dz * dz;
    }
}
