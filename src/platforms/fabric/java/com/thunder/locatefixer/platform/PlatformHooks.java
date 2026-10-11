package com.thunder.locatefixer.platform;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

import java.util.Objects;

public final class PlatformHooks {
    private static final TagKey<Biome> CAVE_BIOME_TAG = TagKey.create(
            Registries.BIOME, Objects.requireNonNull(ResourceLocation.tryParse("c:is_cave")));

    private PlatformHooks() {
    }

    public static boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    public static TagKey<Biome> caveBiomeTag() {
        return CAVE_BIOME_TAG;
    }

    public static TeleportTarget adjustTeleport(ServerPlayer player, double x, double y, double z) {
        return new TeleportTarget(true, x, y, z);
    }

    public record TeleportTarget(boolean allowed, double x, double y, double z) {
    }
}
