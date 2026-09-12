package ai.ravenroot.extensions.matrix;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Strict operator-owned Matrix authority. */
record MatrixConfiguration(StorePolicy store, Map<String, MatrixProfile> profiles) {
    static final String PACKAGE_ID = "ai.ravenroot.extensions.matrix";
    static final String ENVIRONMENT = "RAVENROOT_MATRIX_CONFIG";

    MatrixConfiguration {
        store = java.util.Objects.requireNonNull(store); profiles = Map.copyOf(profiles);
        if (profiles.isEmpty() || profiles.size() > 512) throw invalid();
        profiles.forEach((key, profile) -> {
            if (!key.equals(profile.tenantId() + "\u0000" + profile.name())) throw invalid();
        });
    }

    static MatrixConfiguration fromEnvironment() { return fromEnvironment(System.getenv()); }

    static MatrixConfiguration fromEnvironment(Map<String, String> environment) {
        try {
            Map<String, Object> root = MatrixValues.json(
                    MatrixValues.canonicalBase64(environment.get(ENVIRONMENT), 4 * 1024 * 1024));
            MatrixValues.exact(root, Set.of("store", "profiles"));
            StorePolicy store = store(MatrixValues.object(root.get("store")));
            Map<String, MatrixProfile> profiles = new LinkedHashMap<>();
            MatrixValues.object(root.get("profiles")).forEach((name, value) -> {
                MatrixProfile profile = profile(name, MatrixValues.object(value));
                if (profiles.put(profile.tenantId() + "\u0000" + profile.name(), profile) != null) throw invalid();
            });
            return new MatrixConfiguration(store, profiles);
        } catch (MatrixException failure) {
            throw failure.code() == MatrixException.Code.CONFIGURATION ? failure : invalid();
        } catch (RuntimeException failure) { throw invalid(); }
    }

    Optional<MatrixProfile> profile(String tenant, String name) {
        return Optional.ofNullable(profiles.get(tenant + "\u0000" + name));
    }

    private static StorePolicy store(Map<String, Object> value) {
        MatrixValues.exact(value, Set.of("path", "maxDeliveries", "retentionHours", "maxSources"));
        return new StorePolicy(Path.of(MatrixValues.string(value.get("path"), 4_096)),
                (int) MatrixValues.number(value.get("maxDeliveries"), 1, 1_000_000),
                (int) MatrixValues.number(value.get("retentionHours"), 1, 24 * 365),
                (int) MatrixValues.number(value.get("maxSources"), 1, 10_000));
    }

    private static MatrixProfile profile(String name, Map<String, Object> value) {
        MatrixValues.exact(value, Set.of("tenantId", "homeserverOrigin", "userId", "rooms", "eventTypes",
                "credentialBindingId", "credentialReference", "initialSyncMode", "initialSince", "limits"));
        Map<String, Object> limits = MatrixValues.object(value.get("limits"));
        MatrixValues.exact(limits, Set.of("requestTimeoutMs", "maxRequestBytes", "maxResponseBytes",
                "maxTextChars", "maxConcurrency", "maxPerSecond", "pollTimeoutMs", "retryBackoffMs",
                "maxEventsPerSync"));
        String initialMode = MatrixValues.string(value.get("initialSyncMode"), 32);
        MatrixProfile.InitialSyncMode mode = switch (initialMode) {
            case "skip" -> MatrixProfile.InitialSyncMode.SKIP;
            case "deliver-bounded" -> MatrixProfile.InitialSyncMode.DELIVER_BOUNDED;
            default -> throw invalid();
        };
        return new MatrixProfile(MatrixValues.string(value.get("tenantId"), 160), name,
                URI.create(MatrixValues.string(value.get("homeserverOrigin"), 512)),
                MatrixValues.string(value.get("userId"), 255),
                MatrixValues.strings(value.get("rooms"), 256, 255),
                MatrixValues.strings(value.get("eventTypes"), 64, 128),
                MatrixValues.string(value.get("credentialBindingId"), 256),
                MatrixValues.string(value.get("credentialReference"), 256),
                (int) MatrixValues.number(limits.get("requestTimeoutMs"), 1_000, 60_000),
                (int) MatrixValues.number(limits.get("maxRequestBytes"), 1, 1024 * 1024),
                (int) MatrixValues.number(limits.get("maxResponseBytes"), 1, 8 * 1024 * 1024),
                (int) MatrixValues.number(limits.get("maxTextChars"), 1, 65_535),
                (int) MatrixValues.number(limits.get("maxConcurrency"), 1, 64),
                (int) MatrixValues.number(limits.get("maxPerSecond"), 1, 50),
                (int) MatrixValues.number(limits.get("pollTimeoutMs"), 0, 30_000),
                (int) MatrixValues.number(limits.get("retryBackoffMs"), 100, 60_000),
                (int) MatrixValues.number(limits.get("maxEventsPerSync"), 1, 1_000), mode,
                MatrixValues.optionalString(value.get("initialSince"), 2_048));
    }

    record StorePolicy(Path path, int maxDeliveries, int retentionHours, int maxSources) {
        StorePolicy { path = java.util.Objects.requireNonNull(path).toAbsolutePath().normalize(); }
    }

    private static MatrixException invalid() { return new MatrixException(MatrixException.Code.CONFIGURATION); }
}
