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
    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<String, String> attributes = new ConcurrentHashMap<>();

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

    public void start() {
        startedAt = Instant.now();
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

    public void incrementCounter(String name) {
        counters.computeIfAbsent(name, ignored -> new LongAdder()).increment();
    }

    public void addCounter(String name, long amount) {
        counters.computeIfAbsent(name, ignored -> new LongAdder()).add(amount);
    }

    public void attribute(String name, String value) {
        if (name != null && value != null) {
            attributes.put(name, value);
        }
    }

    public void found(LocatorResult result) {
        this.result = result;
        transitionTerminal(LocateJobStatus.FOUND, 100, "Location found", "");
    }

    public void notFound(String message) {
        transitionTerminal(LocateJobStatus.FAILED, 100, message, message);
    }

    public void fail(Throwable failure) {
        String message = failure == null || failure.getMessage() == null
                ? "Locate search failed" : failure.getMessage();
        transitionTerminal(LocateJobStatus.FAILED, progressPercent, message, message);
    }

    public boolean cancel() {
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

    public LocateJobSnapshot snapshot() {
        Map<String, Long> counterSnapshot = new java.util.TreeMap<>();
        counters.forEach((name, value) -> counterSnapshot.put(name, value.sum()));
        return new LocateJobSnapshot(request, status.get(), progressPercent, progressMessage, backendId,
                startedAt, finishedAt, Optional.ofNullable(result), failureMessage, workerThread,
                Map.copyOf(counterSnapshot), Map.copyOf(new java.util.TreeMap<>(attributes)));
    }

    private void update(LocateJobStatus nextStatus, int percent, String message) {
        if (status.get().isTerminal()) {
            return;
        }
        status.set(nextStatus);
        progressPercent = Math.max(0, Math.min(100, percent));
        progressMessage = message == null ? "" : message;
    }

    private void transitionTerminal(LocateJobStatus terminalStatus, int percent, String message, String failure) {
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
