package com.thunder.locatefixer.search;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** A bounded cooperative wait; cancellation abandons queued work, never interrupts live world access. */
public final class ServerThreadCall {
    private ServerThreadCall() {}

    public static <T> T call(Executor owner, BooleanSupplier onOwnerThread, Callable<T> operation,
                             LocateCancellationToken token, BiConsumer<Long, Long> timing) throws Exception {
        token.throwIfCancelled();
        long submitted = System.nanoTime();
        if (onOwnerThread.getAsBoolean()) {
            try {
                T value = operation.call();
                token.throwIfCancelled();
                return value;
            } finally {
                timing.accept(0L, System.nanoTime() - submitted);
            }
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        owner.execute(() -> {
            if (result.isDone()) return;
            long started = System.nanoTime();
            T value = null;
            Throwable failure = null;
            try {
                token.throwIfCancelled();
                value = operation.call();
                token.throwIfCancelled();
            } catch (Throwable caught) {
                failure = caught;
            } finally {
                timing.accept(started - submitted, System.nanoTime() - started);
            }
            if (failure == null) result.complete(value); else result.completeExceptionally(failure);
        });
        try {
            while (true) {
                token.throwIfCancelled();
                try {
                    return result.get(50L, TimeUnit.MILLISECONDS);
                } catch (TimeoutException pending) {
                    // Recheck the deadline even when the owner thread cannot run the task.
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            token.cancel();
            throw new CancellationException("Locate search interrupted while waiting for the server thread");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new RuntimeException(cause);
        } finally {
            result.cancel(false);
        }
    }
}
