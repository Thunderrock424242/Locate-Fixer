package com.thunder.locatefixer.search;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ServerThreadCallTest {
    @Test
    void cancellationReleasesWorkerAndAbandonsQueuedWorldCall() throws Exception {
        CopyOnWriteArrayList<Runnable> ownerQueue = new CopyOnWriteArrayList<>();
        LocateCancellationToken token = new LocateCancellationToken();
        AtomicBoolean worldTouched = new AtomicBoolean();
        CompletableFuture<Throwable> waiting = CompletableFuture.supplyAsync(() -> {
            try {
                ServerThreadCall.call(ownerQueue::add, () -> false, () -> {
                    worldTouched.set(true);
                    return 1;
                }, token, (wait, call) -> {});
                return null;
            } catch (Throwable failure) {
                return failure;
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (ownerQueue.isEmpty() && System.nanoTime() < deadline) Thread.onSpinWait();
        assertEquals(1, ownerQueue.size());
        token.cancel();
        assertInstanceOf(CancellationException.class, waiting.get(1, TimeUnit.SECONDS));
        ownerQueue.get(0).run();
        assertFalse(worldTouched.get());
    }

    @Test
    void deadlineReleasesWorkerWhenServerNeverExecutesQueuedTask() {
        LocateCancellationToken token = new LocateCancellationToken();
        token.setTimeoutSeconds(1);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () ->
                assertThrows(LocateCancellationToken.LocateTimeoutException.class, () ->
                        ServerThreadCall.call(ignored -> {}, () -> false, () -> 1,
                                token, (wait, call) -> {})));
    }

    @Test
    void recordsActualOwnerExecutionCostAndPreservesException() {
        AtomicLong elapsed = new AtomicLong();
        IllegalStateException expected = new IllegalStateException("world failure");
        Exception actual = assertThrows(IllegalStateException.class, () ->
                ServerThreadCall.call(Runnable::run, () -> true, () -> {
                    throw expected;
                }, new LocateCancellationToken(), (wait, call) -> elapsed.set(call)));
        assertSame(expected, actual);
        assertTrue(elapsed.get() > 0L);
    }

    @Test
    void ownerCallTimingIsVisibleWhenWorkerReceivesResult() throws Exception {
        AtomicLong elapsed = new AtomicLong();
        var owner = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            int value = ServerThreadCall.call(owner, () -> false, () -> 42,
                    new LocateCancellationToken(), (wait, call) -> elapsed.set(call));
            assertEquals(42, value);
            assertTrue(elapsed.get() > 0L);
        } finally {
            owner.shutdownNow();
        }
    }
}
