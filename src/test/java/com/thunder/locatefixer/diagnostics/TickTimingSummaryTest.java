package com.thunder.locatefixer.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TickTimingSummaryTest {
    @Test
    void summarizesObservedNativeTickSamples() {
        TickTimingSummary summary = TickTimingSummary.of(new long[]{0, 20_000_000, 60_000_000});
        assertEquals(2, summary.count());
        assertEquals(40.0, summary.averageMillis());
        assertEquals(60.0, summary.maxMillis());
        assertEquals(1, summary.stallsOver50Millis());
    }

    @Test
    void unfilledOrInvalidSamplesAreNotMeasuredTicks() {
        TickTimingSummary summary = TickTimingSummary.of(new long[]{0, -1, 0});
        assertEquals(0, summary.count());
        assertEquals(0.0, summary.averageMillis());
        assertEquals(0.0, summary.maxMillis());
        assertEquals(0, summary.stallsOver50Millis());
    }

    @Test
    void stallThresholdIsStrictlyOverFiftyMilliseconds() {
        TickTimingSummary summary = TickTimingSummary.of(new long[]{50_000_000, 50_000_001});
        assertEquals(2, summary.count());
        assertEquals(1, summary.stallsOver50Millis());
    }

    @Test
    void summingLongTicksDoesNotOverflow() {
        TickTimingSummary summary = TickTimingSummary.of(new long[]{Long.MAX_VALUE, Long.MAX_VALUE});
        assertEquals(2, summary.count());
        assertEquals(Long.MAX_VALUE / 1_000_000.0, summary.averageMillis());
        assertEquals(Long.MAX_VALUE / 1_000_000.0, summary.maxMillis());
        assertEquals(2, summary.stallsOver50Millis());
    }
}
