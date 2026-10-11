package com.thunder.locatefixer.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocateSearchMessagesTest {
    @Test
    void reportsSearchStagesWithoutInventingChunkScans() {
        String first = LocateSearchMessages.stage("biome", 6400, 1, 6);
        String later = LocateSearchMessages.stage("biome", 16000, 2, 6);

        assertEquals("🔍 Searching for biome up to 6400 blocks [stage 1/6]", first);
        assertEquals("🔍 Extending biome search up to 16000 blocks [stage 2/6]", later);
        assertFalse(first.contains("chunks"));
        assertFalse(later.contains("scanned"));
    }

    @Test
    void explainsUnavailableBiomeWithoutClaimingRadiusWork() {
        String message = LocateSearchMessages.biomeUnavailable(
                "wildernessodysseyapi:glacial_meltwater_valley", "minecraft:overworld");

        assertTrue(message.contains("not available from the active biome source"));
        assertTrue(message.contains("No radius search was run"));
        assertFalse(message.contains("not found within"));
    }

    @Test
    void rejectsInvalidStageCoordinates() {
        assertThrows(IllegalArgumentException.class,
                () -> LocateSearchMessages.stage("biome", 6400, 7, 6));
    }
}
