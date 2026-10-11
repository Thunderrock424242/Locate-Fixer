package com.thunder.locatefixer.job;

import com.thunder.locatefixer.LocateRuntime;
import com.thunder.locatefixer.api.LocatorRequest;
import com.thunder.locatefixer.api.LocatorTargetType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import com.thunder.locatefixer.mixin.CommandSourceStackAccessor;
import net.minecraft.world.level.BaseCommandBlock;

/** Shared command-to-job boundary used by every locate command path. */
public final class LocateJobSubmissions {
    private LocateJobSubmissions() {
    }

    public static String sourceKey(CommandSourceStack source) {
        if (source.getEntity() != null) {
            return (source.getEntity() instanceof ServerPlayer ? "player:" : "entity:") + source.getEntity().getUUID();
        }
        var owner = ((CommandSourceStackAccessor) source).locatefixer$getSource();
        if (owner instanceof BaseCommandBlock block) {
            return "block:" + source.getLevel().dimension().location() + ":" + BlockPos.containing(block.getPosition()).asLong();
        }
        return "source:" + owner.getClass().getName() + ":" + Integer.toHexString(System.identityHashCode(owner));
    }

    public static boolean submit(CommandSourceStack source,
                                 LocatorTargetType targetType,
                                 String targetId,
                                 BlockPos origin,
                                 ServerLevel level,
                                 int maxRadius,
                                 LocateJobManager.LocateJobTask task) {
        return submit(source, targetType, targetId, origin, level, maxRadius, task,
                job -> notifyTimeout(source, level, job, () -> true));
    }

    public static boolean submit(CommandSourceStack source, LocatorTargetType targetType, String targetId,
                                 BlockPos origin, ServerLevel level, int maxRadius,
                                 LocateJobManager.LocateJobTask task,
                                 java.util.function.Consumer<LocateJob> completion) {
        LocatorRequest request = LocatorRequest.create(sourceKey(source), targetType, targetId,
                level.dimension().location().toString(), origin, maxRadius);
        LocateJobManager.Submission submission = LocateRuntime.jobs().submit(request, job -> {
            job.selectBackend(LocateRuntime.backends().select(targetType)
                    .map(backend -> backend.id()).orElse("locatefixer:vanilla"));
            job.attribute("biomespy", Boolean.toString(LocateRuntime.integrations().active("biomespy")));
            task.run(job);
        }, completion);
        if (!submission.accepted()) {
            source.sendFailure(Component.literal("⏳ " + submission.rejectionMessage()));
        }
        return submission.accepted();
    }

    public static void notifyTimeout(CommandSourceStack source, ServerLevel level, LocateJob job,
                                     java.util.function.BooleanSupplier stillValid) {
        if (job.snapshot().status() != LocateJobStatus.TIMED_OUT) return;
        level.getServer().execute(() -> {
            if (!stillValid.getAsBoolean()) return;
            if (source.getEntity() instanceof ServerPlayer player
                    && (player.isRemoved() || level.getServer().getPlayerList().getPlayer(player.getUUID()) != player)) return;
            source.sendFailure(Component.literal("Locate search timed out. Try a shorter search distance."));
        });
    }
}
