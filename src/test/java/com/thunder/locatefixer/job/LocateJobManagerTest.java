package com.thunder.locatefixer.job;

import com.thunder.locatefixer.api.LocatorRequest;
import com.thunder.locatefixer.api.LocatorTargetType;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.thunder.locatefixer.api.LocatorResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LocateJobManagerTest {
    @Test
    void tickIntervalMeasurementRetainsJobsThatFinishBeforeTheNextTick() {
        LocateJobManager manager = new LocateJobManager(1, 2, 30);
        try {
            manager.recordServerTick(1_000_000_000L);
            LocateJob job = new LocateJob(request("tick-impact"));
            manager.trackTickImpact(job);
            job.notFound("finished between ticks");
            manager.recordServerTick(1_700_000_000L);
            assertEquals(700_000_000L, job.snapshot().counters().get("tick_interval_max_nanos"));
            assertEquals(1L, job.snapshot().counters().get("tick_intervals_over_100ms"));
            manager.recordServerTick(1_750_000_000L);
            assertEquals(1L, job.snapshot().counters().get("tick_intervals_observed"));
        } finally { manager.shutdown(); }
    }

    @Test
    void pendingTickMeasurementsAreBoundedAndClearedOnShutdown() {
        LocateJobManager manager = new LocateJobManager(1, 2, 30);
        java.util.List<LocateJob> jobs = new java.util.ArrayList<>();
        manager.recordServerTick(1_000_000_000L);
        for (int i = 0; i < 1000; i++) {
            LocateJob job = new LocateJob(request("tick-" + i));
            jobs.add(job);
            manager.trackTickImpact(job);
        }
        manager.recordServerTick(1_700_000_000L);
        assertEquals(256L, jobs.stream().filter(job -> job.snapshot().counters()
                .getOrDefault("tick_intervals_observed", 0L) > 0).count());
        LocateJob oldJob = new LocateJob(request("old"));
        manager.trackTickImpact(oldJob);
        manager.shutdown();
        manager.recordServerTick(9_000_000_000L);
        assertEquals(0L, oldJob.snapshot().counters().getOrDefault("tick_intervals_observed", 0L));
    }

    @Test
    void completedResultRemainsPublishableAfterItsSearchDeadline() throws InterruptedException {
        LocateJob job = new LocateJob(request("completed"));
        job.cancellationToken().setTimeoutSeconds(1);
        job.found(new LocatorResult(LocatorTargetType.BIOME, "minecraft:plains", "minecraft:overworld",
                new BlockPos(10, 64, 10), "test", "test", Instant.now(), false, true, Map.of()));
        Thread.sleep(1100L);
        assertTrue(job.canPublish());
        assertEquals(LocateJobStatus.FOUND, job.snapshot().status());
    }

    @Test
    void timeoutPublishesTerminalCompletionExactlyOnce() throws InterruptedException {
        LocateJobManager manager = new LocateJobManager(1, 2, 30);
        CountDownLatch notified = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        try {
            manager.submit(request("timeout"), job -> {
                job.cancellationToken().setTimeoutSeconds(1);
                Thread.sleep(1100L);
                job.cancellationToken().throwIfCancelled();
            }, job -> {
                assertEquals(LocateJobStatus.TIMED_OUT, job.snapshot().status());
                calls.incrementAndGet();
                notified.countDown();
            });
            assertTrue(notified.await(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        } finally { manager.shutdown(); }
    }

    @Test
    void cancelledAndTimedOutWorkCannotPublishSuccess() {
        LocateJob cancelled = new LocateJob(request("cancel"));
        cancelled.cancel();
        assertFalse(cancelled.canPublish());
        LocateJob timedOut = new LocateJob(request("timeout"));
        timedOut.timeout();
        assertFalse(timedOut.canPublish());
    }

    @Test
    void unchangedReconfigurationPreservesTheBoundedQueue() throws InterruptedException {
        LocateJobManager manager = new LocateJobManager(1, 2, 30);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            assertTrue(manager.submit(request("running"), job -> {
                started.countDown();
                release.await();
            }).accepted());
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(manager.submit(request("queued"), job -> job.notFound("done")).accepted());
            manager.reconfigure(1, 2, 30);
            assertEquals(1, manager.diagnostics().queuedJobs());
            assertEquals(1, manager.diagnostics().remainingQueueCapacity());
        } finally {
            release.countDown();
            manager.shutdown();
        }
    }

    @Test
    void shutdownDropsHistoryBeforeAWorldRestarts() throws InterruptedException {
        LocateJobManager manager = new LocateJobManager(1, 2, 30);
        CountDownLatch done = new CountDownLatch(1);
        manager.submit(request("old-world"), job -> {
            job.notFound("done");
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        manager.shutdown();
        assertTrue(manager.latestFor("old-world").isEmpty());
        assertEquals(0, manager.diagnostics().retainedJobs());
    }

    @Test
    void cancelledJobRejectsLateResults() {
        LocatorRequest request = request("cancelled");
        LocateJob job = new LocateJob(request);
        job.start();
        assertTrue(job.cancel());
        job.found(new LocatorResult(LocatorTargetType.BIOME, "minecraft:plains", "minecraft:overworld",
                new BlockPos(10, 64, 10), "test", "test", Instant.now(), false, true, Map.of()));
        assertEquals(LocateJobStatus.CANCELLED, job.snapshot().status());
        assertTrue(job.snapshot().result().isEmpty());
    }

    @Test
    void queuedCancellationReclaimsCapacityAndSourceOwnership() throws InterruptedException {
        LocateJobManager manager = new LocateJobManager(1, 1, 30);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            manager.submit(request("running"), job -> {
                started.countDown();
                release.await();
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(manager.submit(request("queued"), job -> job.notFound("done")).accepted());
            assertFalse(manager.submit(request("overflow"), job -> {}).accepted());
            assertTrue(manager.cancelFor("queued"));
            assertTrue(manager.submit(request("queued"), job -> job.notFound("replacement")).accepted());
            assertEquals(1, manager.diagnostics().queuedJobs());
        } finally {
            release.countDown();
            manager.shutdown();
        }
    }

    private static LocatorRequest request(String source) {
        return LocatorRequest.create(source, LocatorTargetType.BIOME, "minecraft:plains",
                "minecraft:overworld", BlockPos.ZERO, 1000);
    }

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
