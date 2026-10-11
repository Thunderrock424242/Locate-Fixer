package com.thunder.locatefixer.teleport;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Owns bounded, independent temporary chunk leases on the server thread. */
final class TeleportLeaseLedger {
    interface Tickets {
        void add(long chunk, UUID operation);
        void remove(long chunk, UUID operation);
    }
    private final int maxActive;
    private final int maxTickets;
    private final Map<UUID, Lease> active = new HashMap<>();
    private int ticketCount;

    TeleportLeaseLedger(int maxActive, int maxTickets) {
        this.maxActive = maxActive;
        this.maxTickets = maxTickets;
    }

    Lease acquire(UUID player, List<Long> chunks, Tickets tickets) {
        List<Long> uniqueChunks = List.copyOf(new LinkedHashSet<>(chunks));
        if (active.containsKey(player) || active.size() >= maxActive
                || ticketCount + uniqueChunks.size() > maxTickets) return null;
        Lease lease = new Lease(player, tickets);
        active.put(player, lease);
        try {
            for (long chunk : uniqueChunks) {
                // Include the failing addition in rollback: an adapter may throw after adding it.
                lease.chunks.add(chunk);
                ticketCount++;
                tickets.add(chunk, lease.id);
            }
            return lease;
        } catch (RuntimeException failure) {
            try { lease.close(); } catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    int activeCount() { return active.size(); }
    int ticketCount() { return ticketCount; }

    void retryClosingLeases() {
        RuntimeException failure = null;
        for (Lease lease : List.copyOf(active.values())) {
            if (!lease.closing) continue;
            try { lease.close(); }
            catch (RuntimeException cleanupFailure) {
                if (failure == null) failure = cleanupFailure;
                else failure.addSuppressed(cleanupFailure);
            }
        }
        if (failure != null) throw failure;
    }

    final class Lease implements AutoCloseable {
        private final UUID id = UUID.randomUUID();
        private final UUID player;
        private final Tickets tickets;
        private final List<Long> chunks = new ArrayList<>();
        private boolean closed;
        private boolean closing;

        private Lease(UUID player, Tickets tickets) { this.player = player; this.tickets = tickets; }
        UUID id() { return id; }

        public void close() {
            if (closed) return;
            closing = true;
            RuntimeException failure = null;
            for (Iterator<Long> iterator = chunks.iterator(); iterator.hasNext();) {
                long chunk = iterator.next();
                try {
                    tickets.remove(chunk, id);
                    iterator.remove();
                    ticketCount--;
                }
                catch (RuntimeException cleanupFailure) {
                    if (failure == null) failure = cleanupFailure;
                    else failure.addSuppressed(cleanupFailure);
                }
            }
            if (chunks.isEmpty()) {
                closed = true;
                active.remove(player, this);
            }
            if (failure != null) throw failure;
        }
    }
}
