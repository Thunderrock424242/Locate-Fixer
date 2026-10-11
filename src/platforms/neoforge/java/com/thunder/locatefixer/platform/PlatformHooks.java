package com.thunder.locatefixer.platform;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.EventHooks;

public final class PlatformHooks {
    private PlatformHooks() {
    }

    public static boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    public static TagKey<Biome> caveBiomeTag() {
        return Tags.Biomes.IS_CAVE;
    }

    public static TeleportTarget adjustTeleport(ServerPlayer player, double x, double y, double z) {
        var event = EventHooks.onEntityTeleportCommand(player, x, y, z);
        return new TeleportTarget(!event.isCanceled(), event.getTargetX(), event.getTargetY(), event.getTargetZ());
    }

    public record TeleportTarget(boolean allowed, double x, double y, double z) {
    }
}
