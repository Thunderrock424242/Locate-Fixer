package com.thunder.locatefixer.search;

import com.thunder.locatefixer.api.LocatorRequest;
import com.thunder.locatefixer.api.LocatorResult;
import com.thunder.locatefixer.api.LocatorTargetType;
import com.thunder.locatefixer.backend.VanillaLocatorBackend;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class IndexedCandidateSearchTest {
    @Test
    void fartherIndexedDiscoveryCannotBypassCloserUndiscoveredResult() throws Exception {
        LocatorRequest request = request();
        SearchPlan indexedPlan = plan().withCandidateRadius(900);
        List<Integer> searched = new ArrayList<>();
        Optional<LocatorResult> result = new VanillaLocatorBackend().locate(request, indexedPlan,
                new LocateCancellationToken(), (stage, token) -> {
                    searched.add(stage.radius());
                    return Optional.of(result(new BlockPos(25, 64, 0)));
                });
        assertEquals(new BlockPos(25, 64, 0), result.orElseThrow().position());
        assertEquals(List.of(100), searched);
    }

    @Test
    void staleIndexedDiscoveryFallsBackToFullRequestedSearch() throws Exception {
        List<Integer> searched = new ArrayList<>();
        Optional<LocatorResult> result = new VanillaLocatorBackend().locate(request(),
                plan().withCandidateRadius(900), new LocateCancellationToken(), (stage, token) -> {
                    searched.add(stage.radius());
                    return stage.radius() == 1000 ? Optional.of(result(new BlockPos(950, 64, 0))) : Optional.empty();
                });
        assertEquals(List.of(100, 500, 900, 1000), searched);
        assertEquals(950, result.orElseThrow().position().getX());
    }

    private static SearchPlan plan() {
        return new SearchPlan(List.of(new SearchStage(100, 1, 1, "near"),
                new SearchStage(500, 1, 1, "medium"), new SearchStage(1000, 1, 1, "max")), 1000, false);
    }

    private static LocatorRequest request() {
        return LocatorRequest.create("test", LocatorTargetType.STRUCTURE, "minecraft:village",
                "minecraft:overworld", BlockPos.ZERO, 1000);
    }

    private static LocatorResult result(BlockPos position) {
        return new LocatorResult(LocatorTargetType.STRUCTURE, "minecraft:village", "minecraft:overworld",
                position, "locatefixer:vanilla", "world-generator", Instant.now(), true, true, Map.of());
    }
}
