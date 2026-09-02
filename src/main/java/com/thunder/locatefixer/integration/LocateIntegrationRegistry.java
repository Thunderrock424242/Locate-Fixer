package com.thunder.locatefixer.integration;

import com.thunder.locatefixer.LocateFixerMod;
import com.thunder.locatefixer.platform.PlatformHooks;
import com.thunder.locatefixer.config.LocateFixerConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Safe mod-presence detection with no reflection or hard dependency on optional mods. */
public final class LocateIntegrationRegistry {
    private final Map<String, IntegrationStatus> statuses = new ConcurrentHashMap<>();

    public void detectBuiltIns() {
        detect(new Descriptor("biomespy", "BiomeSpy", List.of("biomespy"),
                "transparent lower-level biome/structure optimization; vanilla calls are preserved"));
        detect(new Descriptor("natures_compass", "Nature's Compass", List.of("naturescompass"),
                "coexistence mode; its existing worker manager remains the owner of compass searches"));
        detect(new Descriptor("explorers_compass", "Explorer's Compass", List.of("explorerscompass"),
                "coexistence mode; its existing worker manager remains the owner of compass searches"));
        detect(new Descriptor("async_locator_refined", "Async Locator Refined",
                List.of("asynclocator", "async_locator", "async_locator_refined"),
                "conflict-safe mode; Locate Unbound yields vanilla /locate interception to the other mod"));
        detect(new Descriptor("worldedit", "WorldEdit", List.of("worldedit"),
                "schematic name discovery with explicit operator anchor recording"));
    }

    public List<IntegrationStatus> statuses() {
        return statuses.values().stream()
                .sorted(java.util.Comparator.comparing(status -> status.integration().id()))
                .toList();
    }

    public boolean detected(String integrationId) {
        IntegrationStatus status = statuses.get(integrationId);
        return status != null && status.detected();
    }

    public boolean enabled(String integrationId) {
        return switch (integrationId) {
            case "biomespy" -> LocateFixerConfig.SERVER.enableBiomeSpyCompatibility.get();
            case "natures_compass", "explorers_compass" ->
                    LocateFixerConfig.SERVER.enableCompassCompatibility.get();
            case "async_locator_refined" -> LocateFixerConfig.SERVER.enableAsyncLocatorConflictMode.get();
            default -> true;
        };
    }

    public boolean active(String integrationId) {
        return detected(integrationId) && enabled(integrationId);
    }

    private void detect(LocateIntegration integration) {
        List<String> matches = new ArrayList<>();
        for (String modId : integration.candidateModIds()) {
            if (PlatformHooks.isModLoaded(modId)) {
                matches.add(modId);
            }
        }
        IntegrationStatus status = new IntegrationStatus(integration, !matches.isEmpty(), List.copyOf(matches));
        statuses.put(integration.id(), status);
        if (status.detected()) {
            LocateFixerMod.LOGGER.info("[LocateUnbound] Detected {}: {}", integration.displayName(), integration.behavior());
        }
    }

    public record IntegrationStatus(LocateIntegration integration, boolean detected, List<String> matchedModIds) {
    }

    private record Descriptor(String id,
                              String displayName,
                              List<String> candidateModIds,
                              String behavior) implements LocateIntegration {
    }
}
