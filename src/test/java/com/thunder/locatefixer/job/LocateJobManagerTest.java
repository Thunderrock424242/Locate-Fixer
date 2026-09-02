package com.thunder.locatefixer.job;

import com.thunder.locatefixer.api.LocatorRequest;
import com.thunder.locatefixer.api.LocatorTargetType;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocateJobManagerTest {
    @Test
    void fastTasksDoNotLeakTrackedFutures() throws InterruptedException {
        LocateJobManager manager = new LocateJobManager(2, 256, 30);
        try {
            for (int i = 0; i < 200; i++) {
                LocatorRequest request = LocatorRequest.create("source-" + i, LocatorTargetType.BIOME,
                        "minecraft:plains", "minecraft:overworld", BlockPos.ZERO, 1000);
                assertTrue(manager.submit(request, job -> job.notFound("test complete")).accepted());
            }

            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline && manager.diagnostics().trackedFutures() != 0) {
                Thread.sleep(5L);
            }

            assertEquals(0, manager.diagnostics().activeJobs());
            assertEquals(0, manager.diagnostics().trackedFutures());
        } finally {
            manager.shutdown();
        }
    }
}
