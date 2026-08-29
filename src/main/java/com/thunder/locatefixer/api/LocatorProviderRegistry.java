package com.thunder.locatefixer.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe public registry for third-party locator providers. */
public final class LocatorProviderRegistry {
    private static final Map<String, LocatorProvider> PROVIDERS = new ConcurrentHashMap<>();

    private LocatorProviderRegistry() {
    }

    public static void register(LocatorProvider provider) {
        Objects.requireNonNull(provider, "provider");
        validateId(provider.id());
        LocatorProvider existing = PROVIDERS.putIfAbsent(provider.id(), provider);
        if (existing != null) {
            throw new IllegalStateException("Locator provider already registered: " + provider.id());
        }
    }

    public static Optional<LocatorProvider> get(String id) {
        return Optional.ofNullable(PROVIDERS.get(id));
    }

    public static List<LocatorProvider> providers(LocatorTargetType targetType) {
        List<LocatorProvider> matches = new ArrayList<>();
        for (LocatorProvider provider : PROVIDERS.values()) {
            if (provider.targetType() == targetType) {
                matches.add(provider);
            }
        }
        matches.sort(Comparator.comparingInt(LocatorProvider::estimatedSearchCost)
                .thenComparing(LocatorProvider::id));
        return List.copyOf(matches);
    }

    public static List<LocatorProvider> allProviders() {
        return PROVIDERS.values().stream()
                .sorted(Comparator.comparing(LocatorProvider::id))
                .toList();
    }

    private static void validateId(String id) {
        if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Provider ID must be a namespaced lowercase ID: " + id);
        }
    }
}
