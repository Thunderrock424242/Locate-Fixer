package com.thunder.locatefixer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.thunder.locatefixer.LocateFixerMod;
import com.thunder.locatefixer.LocateRuntime;
import com.thunder.locatefixer.backend.LocatorBackend;
import com.thunder.locatefixer.config.LocateFixerConfig;
import com.thunder.locatefixer.integration.LocateIntegrationRegistry;
import com.thunder.locatefixer.job.LocateJobManager;
import com.thunder.locatefixer.job.LocateJobSnapshot;
import com.thunder.locatefixer.index.WorldLocatorIndex;
import com.thunder.locatefixer.teleport.LocateTeleportHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static net.minecraft.commands.Commands.literal;

/** Player controls and operator diagnostics shared by all supported loaders. */
public final class LocateControlCommand {
    private LocateControlCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("locate")
                .then(literal("status").executes(context -> status(context.getSource())))
                .then(literal("cancel").executes(context -> cancel(context.getSource()))));
        dispatcher.register(buildAdminRoot("locateunbound"));
        dispatcher.register(buildAdminRoot("locatefixer"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildAdminRoot(String name) {
        return literal(name)
                .then(literal("status").executes(context -> status(context.getSource())))
                .then(literal("cancel").executes(context -> cancel(context.getSource())))
                .then(literal("diagnostics")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> diagnostics(context.getSource())))
                .then(literal("benchmark")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> benchmark(context.getSource())));
    }

    private static int status(CommandSourceStack source) {
        Optional<LocateJobSnapshot> latest = LocateRuntime.jobs().latestFor(source.getTextName());
        if (latest.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Locate Unbound: no recent locate request."), false);
            return 0;
        }
        LocateJobSnapshot job = latest.get();
        long elapsedMs = elapsedMillis(job);
        source.sendSuccess(() -> Component.literal("Locate Unbound job "
                + shortId(job.request().jobId().toString()) + ": " + job.status()
                + " | " + job.progressPercent() + "% | " + job.progressMessage()
                + " | backend " + job.backendId() + " | " + elapsedMs + " ms"), false);
        return 1;
    }

    private static int cancel(CommandSourceStack source) {
        boolean searchCancelled = LocateRuntime.jobs().cancelFor(source.getTextName());
        boolean teleportCancelled = false;
        try {
            teleportCancelled = LocateTeleportHandler.cancelFor(source.getPlayerOrException());
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException ignored) {
            // The console may cancel its own named search job, but it has no teleport.
        }
        boolean cancelled = searchCancelled || teleportCancelled;
        if (cancelled) {
            source.sendSuccess(() -> Component.literal("Locate Unbound request cancelled."), false);
            return 1;
        }
        source.sendFailure(Component.literal("No active locate search or teleport to cancel."));
        return 0;
    }

    private static int diagnostics(CommandSourceStack source) {
        LocateJobManager.Diagnostics jobs = LocateRuntime.jobs().diagnostics();
        source.sendSuccess(() -> Component.literal(LocateFixerMod.DISPLAY_NAME + " 4.0.0 diagnostics"), false);
        source.sendSuccess(() -> Component.literal("Workers " + jobs.workerCount()
                + " | active " + jobs.activeJobs()
                + " | queued " + jobs.queuedJobs()
                + "/" + (jobs.queuedJobs() + jobs.remainingQueueCapacity())
                + " | tracked futures " + jobs.trackedFutures()
                + " | timeout " + jobs.timeoutSeconds() + "s"), false);
        source.sendSuccess(() -> Component.literal("Configured max radius "
                + LocateFixerConfig.SERVER.locateRings.get().stream().mapToInt(Integer::intValue).max().orElse(0)
                + " | cache entries " + com.thunder.locatefixer.util.AsyncLocateHandler.cacheEntryCount()
                + " | indexed entries " + WorldLocatorIndex.get(source.getLevel()).size()
                + " | search history " + LocateRuntime.searchHistory().size()), false);
        for (LocatorBackend backend : LocateRuntime.backends().all()) {
            source.sendSuccess(() -> Component.literal("Backend " + backend.id() + " (" + backend.displayName()
                    + ") priority=" + backend.priority() + " available=" + backend.isAvailable()
                    + " async=" + backend.supportsAsyncExecution()), false);
        }
        for (LocateIntegrationRegistry.IntegrationStatus integration : LocateRuntime.integrations().statuses()) {
            source.sendSuccess(() -> Component.literal("Integration " + integration.integration().displayName()
                    + ": " + (integration.detected() ? "detected" : "not detected")
                    + ", " + (LocateRuntime.integrations().enabled(integration.integration().id()) ? "enabled" : "disabled")
                    + " | " + integration.integration().behavior()), false);
        }
        if (!LocateFixerConfig.SERVER.benchmarkEnabled.get()) {
            source.sendSuccess(() -> Component.literal("Benchmark capture is disabled in server config."), false);
        }
        return 1;
    }

    private static int benchmark(CommandSourceStack source) {
        if (!LocateFixerConfig.SERVER.benchmarkEnabled.get()) {
            source.sendFailure(Component.literal(
                    "Benchmark capture is disabled. Enable benchmark.enabled in the server config."));
            return 0;
        }
        Optional<LocateJobSnapshot> latest = LocateRuntime.jobs().latestFor(source.getTextName());
        if (latest.isEmpty()) {
            source.sendFailure(Component.literal("Run a locate search first, then use this command."));
            return 0;
        }
        LocateJobSnapshot job = latest.get();
        source.sendSuccess(() -> Component.literal("Locate Unbound benchmark "
                + shortId(job.request().jobId().toString())), false);
        source.sendSuccess(() -> Component.literal("Target " + job.request().targetType() + " "
                + job.request().targetId() + " | dimension " + job.request().dimensionId()), false);
        source.sendSuccess(() -> Component.literal("Backend " + job.backendId()
                + " | status " + job.status() + " | wall time " + elapsedMillis(job)
                + " ms | worker " + job.workerThread()), false);
        source.sendSuccess(() -> Component.literal("Cache hit/miss " + counter(job, "cache_hits") + "/"
                + counter(job, "cache_misses") + " | index hit/miss " + counter(job, "index_hits")
                + "/" + counter(job, "index_misses")), false);
        source.sendSuccess(() -> Component.literal("Stages " + counter(job, "search_stages")
                + " | backend calls " + counter(job, "backend_calls")
                + " | anchors " + counter(job, "anchors_sampled")
                + " | candidates " + counter(job, "candidates_sampled")
                + " | result distance " + job.attributes().getOrDefault("result_distance", "n/a")), false);
        source.sendSuccess(() -> Component.literal("BiomeSpy detected "
                + job.attributes().getOrDefault("biomespy", "false")
                + " | chunk load/generation counts: not sampled"), false);
        return 1;
    }

    private static long counter(LocateJobSnapshot job, String name) {
        return job.counters().getOrDefault(name, 0L);
    }

    private static long elapsedMillis(LocateJobSnapshot job) {
        Instant start = job.startedAt() == null ? job.request().createdAt() : job.startedAt();
        Instant end = job.finishedAt() == null ? Instant.now() : job.finishedAt();
        return Math.max(0L, Duration.between(start, end).toMillis());
    }

    private static String shortId(String id) {
        return id.substring(0, Math.min(8, id.length()));
    }
}
