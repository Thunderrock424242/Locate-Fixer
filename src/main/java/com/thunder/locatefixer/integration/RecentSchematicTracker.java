package com.thunder.locatefixer.integration;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;

public class RecentSchematicTracker {
    private static final Map<String, String> PLAYER_SCHEMATIC_MAP = new HashMap<>();
    private static final Map<String, BlockPos> PLAYER_POS_MAP = new HashMap<>();

    public static void trackPlayer(String playerName, String schematicName) {
        PLAYER_SCHEMATIC_MAP.put(playerName.toLowerCase(), schematicName.toLowerCase());
    }

    public static void trackPosition(String playerName, BlockPos pos) {
        PLAYER_POS_MAP.put(playerName.toLowerCase(), pos);
    }

    public static String getRecentSchematic(String playerName) {
        return PLAYER_SCHEMATIC_MAP.getOrDefault(playerName.toLowerCase(), null);
    }

    public static BlockPos getRecentPosition(String playerName) {
        return PLAYER_POS_MAP.getOrDefault(playerName.toLowerCase(), null);
    }

    public static void clear(String playerName) {
        PLAYER_SCHEMATIC_MAP.remove(playerName.toLowerCase());
        PLAYER_POS_MAP.remove(playerName.toLowerCase());
    }
}
