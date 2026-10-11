package com.thunder.locatefixer.teleport;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TeleportLeaseLedgerTest {
    @Test
    void overlappingPlayersOwnIndependentTicketsAndReleaseExactlyTheirOwnLease() {
        TeleportLeaseLedger ledger = new TeleportLeaseLedger(8, 128);
        Set<String> tickets = new HashSet<>();
        TeleportLeaseLedger.Tickets adapter = adapter(tickets);
        TeleportLeaseLedger.Lease first = ledger.acquire(UUID.randomUUID(), List.of(42L), adapter);
        TeleportLeaseLedger.Lease second = ledger.acquire(UUID.randomUUID(), List.of(42L), adapter);
        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first.id(), second.id());
        first.close();
        first.close();
        assertEquals(Set.of(second.id() + ":42"), tickets);
        assertEquals(1, ledger.activeCount());
        second.close();
        assertTrue(tickets.isEmpty());
        assertEquals(0, ledger.ticketCount());
    }

    @Test
    void rejectsDuplicatePlayersAndGlobalCapacityWithoutAcquiringMoreTickets() {
        TeleportLeaseLedger ledger = new TeleportLeaseLedger(2, 3);
        Set<String> tickets = new HashSet<>();
        TeleportLeaseLedger.Tickets adapter = adapter(tickets);
        UUID player = UUID.randomUUID();
        assertNotNull(ledger.acquire(player, List.of(1L, 2L), adapter));
        assertNull(ledger.acquire(player, List.of(1L), adapter));
        assertNull(ledger.acquire(UUID.randomUUID(), List.of(3L, 4L), adapter));
        assertNotNull(ledger.acquire(UUID.randomUUID(), List.of(3L), adapter));
        assertNull(ledger.acquire(UUID.randomUUID(), List.of(4L), adapter));
        assertEquals(3, tickets.size());
    }

    @Test
    void acquisitionFailureRollsBackPartialTicketsAndCapacity() {
        TeleportLeaseLedger ledger = new TeleportLeaseLedger(1, 3);
        Set<String> tickets = new HashSet<>();
        TeleportLeaseLedger.Tickets adapter = new TeleportLeaseLedger.Tickets() {
            public void add(long chunk, UUID id) {
                tickets.add(id + ":" + chunk);
                if (chunk == 2) throw new IllegalStateException("ticket add failed");
            }
            public void remove(long chunk, UUID id) { tickets.remove(id + ":" + chunk); }
        };
        assertThrows(IllegalStateException.class, () -> ledger.acquire(UUID.randomUUID(), List.of(1L, 2L), adapter));
        assertTrue(tickets.isEmpty());
        assertEquals(0, ledger.activeCount());
        assertEquals(0, ledger.ticketCount());
        assertNotNull(ledger.acquire(UUID.randomUUID(), List.of(3L), adapter));
    }

    @Test
    void failedReleaseKeepsOwnershipUntilRetriedAndStillReleasesOtherChunks() {
        TeleportLeaseLedger ledger = new TeleportLeaseLedger(2, 3);
        Set<String> tickets = new HashSet<>();
        boolean[] firstAttempt = {true};
        TeleportLeaseLedger.Tickets adapter = new TeleportLeaseLedger.Tickets() {
            public void add(long chunk, UUID id) { tickets.add(id + ":" + chunk); }
            public void remove(long chunk, UUID id) {
                if (chunk == 1 && firstAttempt[0]) {
                    firstAttempt[0] = false;
                    throw new IllegalStateException("temporary removal failure");
                }
                tickets.remove(id + ":" + chunk);
            }
        };
        TeleportLeaseLedger.Lease lease = ledger.acquire(UUID.randomUUID(), List.of(1L, 2L), adapter);
        assertNotNull(lease);
        assertThrows(IllegalStateException.class, lease::close);
        assertEquals(Set.of(lease.id() + ":1"), tickets);
        assertEquals(1, ledger.ticketCount());
        ledger.retryClosingLeases();
        assertTrue(tickets.isEmpty());
        assertEquals(0, ledger.activeCount());
    }

    private static TeleportLeaseLedger.Tickets adapter(Set<String> tickets) {
        return new TeleportLeaseLedger.Tickets() {
            public void add(long chunk, UUID id) { tickets.add(id + ":" + chunk); }
            public void remove(long chunk, UUID id) { tickets.remove(id + ":" + chunk); }
        };
    }
}
