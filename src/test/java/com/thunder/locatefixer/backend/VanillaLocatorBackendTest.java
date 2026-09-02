package com.thunder.locatefixer.backend;

import com.thunder.locatefixer.api.LocatorRequest;
import com.thunder.locatefixer.api.LocatorResult;
import com.thunder.locatefixer.api.LocatorTargetType;
import com.thunder.locatefixer.search.LocateCancellationToken;
import com.thunder.locatefixer.search.SearchPlan;
import com.thunder.locatefixer.search.SearchStage;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertThrows;

class VanillaLocatorBackendTest {
    @Test
    void rejectsResultWhenCancellationArrivesDuringWorldOperation() {
        LocatorRequest request = LocatorRequest.create("test", LocatorTargetType.STRUCTURE,
                "minecraft:village", "minecraft:overworld", BlockPos.ZERO, 1000);
        LocateCancellationToken token = new LocateCancellationToken();
        SearchPlan plan = new SearchPlan(List.of(new SearchStage(1000, 1.0D, 1.0D, "test")),
                1000, false);

        assertThrows(CancellationException.class, () -> new VanillaLocatorBackend().locate(
                request, plan, token, (stage, cancellationToken) -> {
                    cancellationToken.cancel();
                    return java.util.Optional.of(new LocatorResult(
                            LocatorTargetType.STRUCTURE, "minecraft:village", "minecraft:overworld",
                            new BlockPos(20, 64, 20), "locatefixer:vanilla", "test", Instant.now(),
                            true, true, Map.of()));
                }));
    }
}
