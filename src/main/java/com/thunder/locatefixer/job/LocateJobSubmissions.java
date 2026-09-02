package com.thunder.locatefixer.job;

import com.thunder.locatefixer.LocateRuntime;
import com.thunder.locatefixer.api.LocatorRequest;
import com.thunder.locatefixer.api.LocatorTargetType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/** Shared command-to-job boundary used by every locate command path. */
public final class LocateJobSubmissions {
    private LocateJobSubmissions() {
    }

    public static boolean submit(CommandSourceStack source,
                                 LocatorTargetType targetType,
                                 String targetId,
                                 BlockPos origin,
                                 ServerLevel level,
                                 int maxRadius,
                                 LocateJobManager.LocateJobTask task) {
        LocatorRequest request = LocatorRequest.create(source.getTextName(), targetType, targetId,
                level.dimension().location().toString(), origin, maxRadius);
        LocateJobManager.Submission submission = LocateRuntime.jobs().submit(request, job -> {
            job.selectBackend(LocateRuntime.backends().select(targetType)
                    .map(backend -> backend.id()).orElse("locatefixer:vanilla"));
            job.attribute("biomespy", Boolean.toString(LocateRuntime.integrations().active("biomespy")));
            task.run(job);
        });
        if (!submission.accepted()) {
            source.sendFailure(Component.literal("⏳ " + submission.rejectionMessage()));
        }
        return submission.accepted();
    }
}
