package com.thunder.locatefixer.job;

import com.thunder.locatefixer.api.LocatorRequest;
import com.thunder.locatefixer.api.LocatorResult;

import java.time.Instant;
import java.util.Optional;
import java.util.Map;

public record LocateJobSnapshot(
        LocatorRequest request,
        LocateJobStatus status,
        int progressPercent,
        String progressMessage,
        String backendId,
        Instant startedAt,
        Instant finishedAt,
        Optional<LocatorResult> result,
        String failureMessage,
        String workerThread,
        Map<String, Long> counters,
        Map<String, String> attributes
) {
}
