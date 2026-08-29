package com.thunder.locatefixer.backend;

import com.thunder.locatefixer.api.LocatorTargetType;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Selects the highest-priority available compatible backend with stable tie-breaking. */
public final class LocatorBackendRegistry {
    private final Map<String, LocatorBackend> backends = new ConcurrentHashMap<>();

    public void register(LocatorBackend backend) {
        Objects.requireNonNull(backend, "backend");
        LocatorBackend existing = backends.putIfAbsent(backend.id(), backend);
        if (existing != null) {
            throw new IllegalStateException("Locator backend already registered: " + backend.id());
        }
    }

    public Optional<LocatorBackend> select(LocatorTargetType targetType) {
        return backends.values().stream()
                .filter(LocatorBackend::isAvailable)
                .filter(backend -> backend.supportedTargetTypes().contains(targetType))
                .sorted(Comparator.comparingInt(LocatorBackend::priority).reversed()
                        .thenComparingInt(LocatorBackend::estimatedSearchCost)
                        .thenComparing(LocatorBackend::id))
                .findFirst();
    }

    public List<LocatorBackend> all() {
        return backends.values().stream()
                .sorted(Comparator.comparing(LocatorBackend::id))
                .toList();
    }
}
