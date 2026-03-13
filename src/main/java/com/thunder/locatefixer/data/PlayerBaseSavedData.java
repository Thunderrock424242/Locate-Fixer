package com.thunder.locatefixer.data;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public class PlayerBaseSavedData extends SavedData {
    private static final String DATA_NAME = "locatefixer_player_bases";

    private final Map<UUID, Map<String, BlockPos>> basesByPlayer = new LinkedHashMap<>();

    public static PlayerBaseSavedData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(new Factory<>(
                PlayerBaseSavedData::new,
                PlayerBaseSavedData::load
        ), DATA_NAME);
    }

    private static PlayerBaseSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        PlayerBaseSavedData data = new PlayerBaseSavedData();
        ListTag players = tag.getList("players", Tag.TAG_COMPOUND);

        for (Tag playerEntry : players) {
            if (!(playerEntry instanceof CompoundTag playerTag) || !playerTag.hasUUID("uuid")) {
                continue;
            }

            UUID playerId = playerTag.getUUID("uuid");
            Map<String, BlockPos> playerBases = new LinkedHashMap<>();
            ListTag bases = playerTag.getList("bases", Tag.TAG_COMPOUND);

            for (Tag baseEntry : bases) {
                if (!(baseEntry instanceof CompoundTag baseTag) || !baseTag.contains("name", Tag.TAG_STRING)) {
                    continue;
                }

                String name = normalizeName(baseTag.getString("name"));
                BlockPos pos = new BlockPos(baseTag.getInt("x"), baseTag.getInt("y"), baseTag.getInt("z"));
                playerBases.put(name, pos);
            }

            if (!playerBases.isEmpty()) {
                data.basesByPlayer.put(playerId, playerBases);
            }
        }

        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag players = new ListTag();

        for (Map.Entry<UUID, Map<String, BlockPos>> playerEntry : basesByPlayer.entrySet()) {
            CompoundTag playerTag = new CompoundTag();
            playerTag.putUUID("uuid", playerEntry.getKey());

            ListTag bases = new ListTag();
            for (Map.Entry<String, BlockPos> baseEntry : playerEntry.getValue().entrySet()) {
                CompoundTag baseTag = new CompoundTag();
                BlockPos pos = baseEntry.getValue();

                baseTag.putString("name", baseEntry.getKey());
                baseTag.putInt("x", pos.getX());
                baseTag.putInt("y", pos.getY());
                baseTag.putInt("z", pos.getZ());
                bases.add(baseTag);
            }

            playerTag.put("bases", bases);
            players.add(playerTag);
        }

        tag.put("players", players);
        return tag;
    }

    public void setBase(UUID playerId, String baseName, BlockPos pos) {
        String normalized = normalizeName(baseName);
        basesByPlayer.computeIfAbsent(playerId, id -> new LinkedHashMap<>())
                .put(normalized, pos.immutable());
        setDirty();
    }

    public BlockPos getBase(UUID playerId, String baseName) {
        Map<String, BlockPos> playerBases = basesByPlayer.get(playerId);
        if (playerBases == null) {
            return null;
        }

        return playerBases.get(normalizeName(baseName));
    }

    public Set<String> getBaseNames(UUID playerId) {
        Map<String, BlockPos> playerBases = basesByPlayer.get(playerId);
        if (playerBases == null) {
            return Set.of();
        }

        return new TreeSet<>(playerBases.keySet());
    }

    private static String normalizeName(String name) {
        return name.trim().toLowerCase();
    }
}
