package com.thunder.locatefixer.diagnostics;

/** Snapshot of Minecraft's measured recent ticks; zero slots have not been sampled. */
public record TickTimingSummary(int count, double averageMillis, double maxMillis, int stallsOver50Millis) {
    public static TickTimingSummary of(long[] durationsNanos) {
        int count = 0;
        int stalls = 0;
        double totalNanos = 0.0;
        long maximumNanos = 0;
        for (long nanos : durationsNanos) {
            if (nanos <= 0) {
                continue;
            }
            count++;
            totalNanos += nanos;
            maximumNanos = Math.max(maximumNanos, nanos);
            if (nanos > 50_000_000L) {
                stalls++;
            }
        }
        return new TickTimingSummary(count, count == 0 ? 0.0 : totalNanos / count / 1_000_000.0,
                maximumNanos / 1_000_000.0, stalls);
    }
}
