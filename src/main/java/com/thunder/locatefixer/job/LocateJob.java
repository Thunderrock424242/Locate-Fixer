package com.thunder.locatefixer.job;

import com.thunder.locatefixer.api.LocatorRequest;
import com.thunder.locatefixer.api.LocatorResult;
import com.thunder.locatefixer.search.LocateCancellationToken;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;

/** Mutable state for one request. All externally visible state is exposed as an immutable snapshot. */
public final class LocateJob {
    private final LocatorRequest request;
    private final LocateCancellationToken cancellationToken = new LocateCancellationToken();
    private final AtomicReference<LocateJobStatus> status = new AtomicReference<>(LocateJobStatus.QUEUED);
    private volatile int progressPercent;
    private volatile String progressMessage = "Waiting for a search worker";
    private volatile String backendId = "unselected";
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile LocatorResult result;
    private volatile String failureMessage = "";
    private volatile String workerThread = "";
    private long startedNanos;
    private long lastProgressNanos;
    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<String, String> attributes = new ConcurrentHashMap<>();
    private final AtomicLong longestServerCallNanos = new AtomicLong();
    private final AtomicLong longestTickIntervalNanos = new AtomicLong();

    LocateJob(LocatorRequest request) {
        this.request = request;
    }

    public LocatorRequest request() {
        return request;
    }

    public LocateCancellationToken cancellationToken() {
        return cancellationToken;
    }

    public boolean isCancelled() {
        return cancellationToken.isCancelled();
    }

    public boolean canPublish() {
        LocateJobStatus current = status.get();
        if (current == LocateJobStatus.CANCELLED || current == LocateJobStatus.TIMED_OUT) return false;
        return current.isTerminal() || !isCancelled();
    }

    public synchronized void start() {
        if (status.get().isTerminal()) return;
        startedAt = Instant.now();
        startedNanos = System.nanoTime();
        workerThread = Thread.currentThread().getName();
        update(LocateJobStatus.INDEX_LOOKUP, 1, "Checking cached and indexed discoveries");
    }

    public void selectBackend(String backendId) {
        this.backendId = backendId;
        update(LocateJobStatus.BACKEND_SEARCH, Math.max(2, progressPercent), "Searching with " + backendId);
    }

    public void searching(int progressPercent, String message) {
        update(LocateJobStatus.SEARCHING, progressPercent, message);
    }

    /** Normal chat progress is useful only for longer searches, at a bounded frequency. */
    public synchronized boolean shouldReportProgress() {
        long now = System.nanoTime();
        long interval = java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        if (status.get().isTerminal() || startedNanos == 0L || now - startedNanos < interval
                || (lastProgressNanos != 0L && now - lastProgressNanos < interval)) return false;
        lastProgressNanos = now;
        return true;
    }

    public void incrementCounter(String name) {
        counters.computeIfAbsent(name, ignored -> new LongAdder()).increment();
    }

    public void addCounter(String name, long amount) {
        counters.computeIfAbsent(name, ignored -> new LongAdder()).add(amount);
    }

    /** Measured executor queue delay and live server-thread execution, not estimated MSPT. */
    public void recordServerCall(long waitNanos, long callNanos) {
        incrementCounter("server_calls");
        addCounter("server_wait_nanos", Math.max(0L, waitNanos));
        addCounter("server_call_nanos", Math.max(0L, callNanos));
        longestServerCallNanos.accumulateAndGet(Math.max(0L, callNanos), Math::max);
    }

    public void recordTickInterval(long intervalNanos) {
        incrementCounter("tick_intervals_observed");
        if (intervalNanos > 100_000_000L) incrementCounter("tick_intervals_over_100ms");
        longestTickIntervalNanos.accumulateAndGet(Math.max(0L, intervalNanos), Math::max);
    }

    public void attribute(String name, String value) {
        if (name != null && value != null) {
            attributes.put(name, value);
        }
    }

    public synchronized void found(LocatorResult result) {
        if (status.get().isTerminal()) return;
        if (cancellationToken.isCancelled()) {
            if (cancellationToken.isTimedOut()) timeout(); else cancel();
            return;
        }
        this.result = result;
        transitionTerminal(LocateJobStatus.FOUND, 100, "Location found", "");
    }

    public void notFound(String message) {
        transitionTerminal(LocateJobStatus.NOT_FOUND, 100, message, message);
    }

    public void unavailable(String message) {
        transitionTerminal(LocateJobStatus.UNAVAILABLE, 100, message, message);
    }

    public void fail(Throwable failure) {
        String message = failure == null || failure.getMessage() == null
                ? "Locate search failed" : failure.getMessage();
        transitionTerminal(LocateJobStatus.FAILED, progressPercent, message, message);
    }

    public synchronized boolean cancel() {
        if (status.get().isTerminal()) {
            return false;
        }
        cancellationToken.cancel();
        transitionTerminal(LocateJobStatus.CANCELLED, progressPercent, "Cancelled", "");
        return true;
    }

    public void timeout() {
        cancellationToken.cancel();
        transitionTerminal(LocateJobStatus.TIMED_OUT, progressPercent, "Search timed out", "Search timed out");
    }

    public synchronized LocateJobSnapshot snapshot() {
        Map<String, Long> counterSnapshot = new java.util.TreeMap<>();
        counters.forEach((name, value) -> counterSnapshot.put(name, value.sum()));
        counterSnapshot.put("server_call_max_nanos", longestServerCallNanos.get());
        counterSnapshot.put("tick_interval_max_nanos", longestTickIntervalNanos.get());
        return new LocateJobSnapshot(request, status.get(), progressPercent, progressMessage, backendId,
                startedAt, finishedAt, Optional.ofNullable(result), failureMessage, workerThread,
                Map.copyOf(counterSnapshot), Map.copyOf(new java.util.TreeMap<>(attributes)));
    }

    private synchronized void update(LocateJobStatus nextStatus, int percent, String message) {
        if (status.get().isTerminal()) {
            return;
        }
        status.set(nextStatus);
        progressPercent = Math.max(0, Math.min(100, percent));
        progressMessage = message == null ? "" : message;
    }

    private synchronized void transitionTerminal(LocateJobStatus terminalStatus, int percent, String message, String failure) {
        LocateJobStatus previous;
        do {
            previous = status.get();
            if (previous.isTerminal()) {
                return;
            }
        } while (!status.compareAndSet(previous, terminalStatus));
        progressPercent = Math.max(0, Math.min(100, percent));
        progressMessage = message == null ? "" : message;
        failureMessage = failure == null ? "" : failure;
        finishedAt = Instant.now();
    }
}
