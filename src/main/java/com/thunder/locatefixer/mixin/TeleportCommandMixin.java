package com.thunder.locatefixer.mixin;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.thunder.locatefixer.teleport.LocateTeleportHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.TeleportCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import net.neoforged.neoforge.event.EventHooks;

@Mixin(TeleportCommand.class)
public abstract class TeleportCommandMixin {

    private static final SimpleCommandExceptionType LOCATEFIX_INVALID_POSITION =
            new SimpleCommandExceptionType(Component.translatable("commands.teleport.invalidPosition"));

    @Inject(method = "teleportToPos", at = @At("HEAD"), cancellable = true)
    private static void locatefix$preloadTeleport(CommandSourceStack source,
                                                  Collection<? extends Entity> targets,
                                                  ServerLevel level,
                                                  Coordinates position,
                                                  @Nullable Coordinates rotation,
                                                  @Nullable Object facing,
                                                  CallbackInfoReturnable<Integer> cir) throws CommandSyntaxException {
        if (targets.size() != 1 || rotation != null || facing != null) {
            return;
        }

        Entity entity = targets.iterator().next();
        if (!(entity instanceof ServerPlayer player)) {
            return;
        }

        if (source.getEntity() != player) {
            return;
        }

        if (position.isXRelative() || position.isYRelative() || position.isZRelative()) {
            return;
        }

        Vec3 targetVec = position.getPosition(source);
        double x = targetVec.x;
        double y = targetVec.y;
        double z = targetVec.z;
        BlockPos targetPos = BlockPos.containing(targetVec);

        LocateTeleportHandler.startTeleportWithPreload(player, level, targetPos, () -> {
            try {
                locatefix$performTeleport(player, level, x, y, z);
                source.sendSuccess(() -> Component.translatable(
                        "commands.teleport.success.location.single",
                        player.getDisplayName(),
                        formatDouble(x),
                        formatDouble(y),
                        formatDouble(z)
                ), true);
                player.sendSystemMessage(Component.literal("✅ Teleport complete."));
            } catch (CommandSyntaxException e) {
                player.sendSystemMessage(Component.literal("Teleport failed: " + e.getMessage()));
            }
        });

        cir.setReturnValue(targets.size());
    }

    private static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%f", value);
    }

    private static void locatefix$performTeleport(ServerPlayer player,
                                                  ServerLevel level,
                                                  double x,
                                                  double y,
                                                  double z) throws CommandSyntaxException {
        var event = EventHooks.onEntityTeleportCommand(player, x, y, z);
        if (event.isCanceled()) {
            return;
        }

        double targetX = event.getTargetX();
        double targetY = event.getTargetY();
        double targetZ = event.getTargetZ();

        BlockPos blockPos = BlockPos.containing(targetX, targetY, targetZ);
        if (!Level.isInSpawnableBounds(blockPos)) {
            throw LOCATEFIX_INVALID_POSITION.create();
        }

        EnumSet<RelativeMovement> relative = EnumSet.of(RelativeMovement.X_ROT, RelativeMovement.Y_ROT);
        float yaw = Mth.wrapDegrees(player.getYRot());
        float pitch = Mth.wrapDegrees(player.getXRot());
        if (player.teleportTo(level, targetX, targetY, targetZ, relative, yaw, pitch)) {
            if (!player.isFallFlying()) {
                player.setDeltaMovement(player.getDeltaMovement().multiply(1.0, 0.0, 1.0));
                player.setOnGround(true);
            }
        }
    }
}
