package com.thunder.locatefixer.search;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative cancellation token; Minecraft world calls are never interrupted mid-operation. */
public final class LocateCancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile long deadlineNanos = Long.MAX_VALUE;

    public void setTimeoutSeconds(int timeoutSeconds) {
        deadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds));
    }

    public boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }

    public boolean isCancelled() {
        return cancelled.get() || isTimedOut();
    }

    public boolean isTimedOut() {
        return System.nanoTime() >= deadlineNanos;
    }

    public void throwIfCancelled() {
        if (isTimedOut()) {
            throw new LocateTimeoutException();
        }
        if (cancelled.get()) {
            throw new CancellationException("Locate request cancelled");
        }
    }

    public static final class LocateTimeoutException extends CancellationException {
        public LocateTimeoutException() {
            super("Locate request timed out");
        }
    }
}
