package com.thunder.locatefixer.job;

import com.thunder.locatefixer.api.LocatorRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns the bounded worker queue, per-source flood protection, cancellation, and status history. */
public final class LocateJobManager {
    private static final int MAX_HISTORY = 256;

    private final Map<UUID, LocateJob> jobs = new ConcurrentHashMap<>();
    private final Map<String, UUID> activeBySource = new ConcurrentHashMap<>();
    private final Map<UUID, Future<?>> futures = new ConcurrentHashMap<>();
    private volatile ThreadPoolExecutor executor;
    private volatile int timeoutSeconds;

    public LocateJobManager(int workers, int queueCapacity, int timeoutSeconds) {
        this.executor = buildExecutor(workers, queueCapacity);
        this.timeoutSeconds = Math.max(5, timeoutSeconds);
    }

    public Submission submit(LocatorRequest request, LocateJobTask task) {
        UUID existing = activeBySource.putIfAbsent(request.sourceKey(), request.jobId());
        if (existing != null) {
            return Submission.rejected("A locate request is already active for this source.");
        }

        LocateJob job = new LocateJob(request);
        job.cancellationToken().setTimeoutSeconds(timeoutSeconds);
        jobs.put(request.jobId(), job);
        trimHistory();
        try {
            Future<?> future = executor.submit(() -> execute(job, task));
            futures.put(request.jobId(), future);
            return Submission.accepted(job);
        } catch (RejectedExecutionException rejected) {
            activeBySource.remove(request.sourceKey(), request.jobId());
            jobs.remove(request.jobId());
            return Submission.rejected("The locate queue is full. Try again after another search finishes.");
        }
    }

    public Optional<LocateJobSnapshot> activeFor(String sourceKey) {
        UUID id = activeBySource.get(sourceKey);
        LocateJob job = id == null ? null : jobs.get(id);
        return job == null ? Optional.empty() : Optional.of(job.snapshot());
    }

    public Optional<LocateJobSnapshot> latestFor(String sourceKey) {
        return jobs.values().stream()
                .filter(job -> job.request().sourceKey().equals(sourceKey))
                .map(LocateJob::snapshot)
                .max(Comparator.comparing(snapshot -> snapshot.request().createdAt()));
    }

    public boolean cancelFor(String sourceKey) {
        UUID id = activeBySource.get(sourceKey);
        LocateJob job = id == null ? null : jobs.get(id);
        if (job == null || !job.cancel()) {
            return false;
        }
        Future<?> future = futures.get(id);
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }
        if (job.snapshot().startedAt() == null) {
            if (future instanceof Runnable queuedTask) {
                executor.remove(queuedTask);
            }
            activeBySource.remove(sourceKey, id);
            futures.remove(id);
        }
        return true;
    }

    public Diagnostics diagnostics() {
        ThreadPoolExecutor current = executor;
        long active = activeBySource.size();
        return new Diagnostics(current.getCorePoolSize(), current.getQueue().size(),
                current.getQueue().remainingCapacity(), active, jobs.size(), timeoutSeconds);
    }

    public synchronized void reconfigure(int workers, int queueCapacity, int timeoutSeconds) {
        ThreadPoolExecutor replacement = buildExecutor(workers, queueCapacity);
        ThreadPoolExecutor previous = executor;
        executor = replacement;
        this.timeoutSeconds = Math.max(5, timeoutSeconds);
        previous.shutdown();
    }

    public synchronized void shutdown() {
        for (LocateJob job : jobs.values()) {
            if (!job.snapshot().status().isTerminal()) {
                job.cancel();
            }
        }
        activeBySource.clear();
        futures.clear();
        executor.shutdownNow();
    }

    private void execute(LocateJob job, LocateJobTask task) {
        try {
            if (job.isCancelled()) {
                if (job.cancellationToken().isTimedOut()) {
                    job.timeout();
                } else {
                    job.cancel();
                }
                return;
            }
            job.start();
            task.run(job);
            if (!job.snapshot().status().isTerminal()) {
                if (job.cancellationToken().isTimedOut()) {
                    job.timeout();
                } else {
                    job.notFound("No matching location was found.");
                }
            }
        } catch (com.thunder.locatefixer.search.LocateCancellationToken.LocateTimeoutException timedOut) {
            job.timeout();
        } catch (java.util.concurrent.CancellationException cancelled) {
            job.cancel();
        } catch (Throwable failure) {
            job.fail(failure);
        } finally {
            activeBySource.remove(job.request().sourceKey(), job.request().jobId());
            futures.remove(job.request().jobId());
        }
    }

    private void trimHistory() {
        if (jobs.size() <= MAX_HISTORY) {
            return;
        }
        List<LocateJob> terminal = new ArrayList<>();
        for (LocateJob job : jobs.values()) {
            if (job.snapshot().status().isTerminal()) {
                terminal.add(job);
            }
        }
        terminal.sort(Comparator.comparing(job -> job.request().createdAt()));
        int removeCount = Math.max(0, jobs.size() - MAX_HISTORY);
        for (int i = 0; i < Math.min(removeCount, terminal.size()); i++) {
            jobs.remove(terminal.get(i).request().jobId(), terminal.get(i));
        }
    }

    private static ThreadPoolExecutor buildExecutor(int workers, int queueCapacity) {
        int safeWorkers = Math.max(1, Math.min(8, workers));
        int safeCapacity = Math.max(1, Math.min(1024, queueCapacity));
        ThreadPoolExecutor pool = new ThreadPoolExecutor(safeWorkers, safeWorkers,
                30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(safeCapacity),
                new WorkerThreadFactory(), new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    @FunctionalInterface
    public interface LocateJobTask {
        void run(LocateJob job) throws Exception;
    }

    public record Submission(boolean accepted, LocateJob job, String rejectionMessage) {
        static Submission accepted(LocateJob job) {
            return new Submission(true, job, "");
        }

        static Submission rejected(String message) {
            return new Submission(false, null, message);
        }
    }

    public record Diagnostics(int workerCount,
                              int queuedJobs,
                              int remainingQueueCapacity,
                              long activeJobs,
                              int retainedJobs,
                              int timeoutSeconds) {
    }

    private static final class WorkerThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "LocateUnbound-Search-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
