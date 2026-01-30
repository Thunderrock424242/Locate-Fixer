package com.thunder.locatefixer.integration;

import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;

public class RecentSchematicTracker {
    private static final Map<String, String> PLAYER_SCHEMATIC_MAP = new ConcurrentHashMap<>();
    private static final Map<String, BlockPos> PLAYER_POS_MAP = new ConcurrentHashMap<>();

    private static String normalizePlayerName(String playerName) {
        return Objects.requireNonNull(playerName, "playerName").toLowerCase(Locale.ROOT);
    }

    public static void trackPlayer(String playerName, String schematicName) {
        PLAYER_SCHEMATIC_MAP.put(normalizePlayerName(playerName), schematicName.toLowerCase(Locale.ROOT));
    }

    public static void trackPosition(String playerName, BlockPos pos) {
        PLAYER_POS_MAP.put(normalizePlayerName(playerName), pos);
    }

    public static String getRecentSchematic(String playerName) {
        return PLAYER_SCHEMATIC_MAP.getOrDefault(normalizePlayerName(playerName), null);
    }

    public static BlockPos getRecentPosition(String playerName) {
        return PLAYER_POS_MAP.getOrDefault(normalizePlayerName(playerName), null);
    }

    public static void clear(String playerName) {
        String key = normalizePlayerName(playerName);
        PLAYER_SCHEMATIC_MAP.remove(key);
        PLAYER_POS_MAP.remove(key);
    }
}
