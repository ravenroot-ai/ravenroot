package ai.ravenroot.extensions.storage;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.security.EnvironmentKeyCodec;

import java.net.URI;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Strict canonical-Base64 JSON operator profile. */
public final class EnvironmentStorageProfileResolver implements StorageProfileResolver {
    private static final int MAX_PROFILE_BYTES = 16 * 1024;
    private static final PayloadLimits LIMITS = new PayloadLimits(MAX_PROFILE_BYTES, 8, 64, 256, 4096, 64);
    private static final Set<String> FIELDS = Set.of("origin", "region", "bucket", "keyPrefix",
            "addressingStyle", "signingBindingId", "operations", "contentTypes", "allowIfMatch",
            "allowIfNoneMatch", "maxObjectBytes", "timeoutMs", "maxConcurrency", "maxRequestsPerSecond");
    private final Map<String, String> environment;

    public EnvironmentStorageProfileResolver() { this(System.getenv()); }
    EnvironmentStorageProfileResolver(Map<String, String> environment) { this.environment = Map.copyOf(environment); }

    @Override public Optional<StorageProfile> resolve(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) return Optional.empty();
        try {
            String encoded = environment.get(environmentVariableName(name));
            if (encoded == null || encoded.length() > MAX_PROFILE_BYTES * 2) return Optional.empty();
            byte[] json = Base64.getDecoder().decode(encoded);
            if (!Base64.getEncoder().encodeToString(json).equals(encoded)) return Optional.empty();
            Map<String, Object> root = StorageValues.object(PayloadJson.read(json, LIMITS).toJava(), "profile");
            StorageValues.exactKeys(root, FIELDS, "profile");
            return Optional.of(new StorageProfile(name,
                    URI.create(StorageValues.string(root.get("origin"), "origin", 512)),
                    StorageValues.string(root.get("region"), "region", 63),
                    StorageValues.string(root.get("bucket"), "bucket", 63),
                    root.get("keyPrefix") instanceof String prefix ? prefix : throwInvalid("keyPrefix"),
                    StorageProfile.AddressingStyle.valueOf(StorageValues.string(root.get("addressingStyle"),
                            "addressingStyle", 32).toUpperCase(Locale.ROOT).replace('-', '_')),
                    StorageValues.string(root.get("signingBindingId"), "signingBindingId", 256),
                    enumSet(root.get("operations")), stringSet(root.get("contentTypes"), "contentTypes", 32),
                    StorageValues.bool(root.get("allowIfMatch"), "allowIfMatch"),
                    StorageValues.bool(root.get("allowIfNoneMatch"), "allowIfNoneMatch"),
                    StorageValues.integer(root.get("maxObjectBytes"), "maxObjectBytes", 1,
                            StorageProfile.HARD_MAX_OBJECT_BYTES),
                    StorageValues.integer(root.get("timeoutMs"), "timeoutMs", 1, 300_000),
                    StorageValues.integer(root.get("maxConcurrency"), "maxConcurrency", 1, 256),
                    StorageValues.integer(root.get("maxRequestsPerSecond"), "maxRequestsPerSecond", 1, 10_000)));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    static String environmentVariableName(String name) {
        return "RAVENROOT_OBJECT_STORAGE_PROFILE_" + EnvironmentKeyCodec.hex(name);
    }

    private static Set<StorageProfile.Operation> enumSet(Object value) {
        Set<StorageProfile.Operation> result = StorageValues.list(value, "operations").stream()
                .map(entry -> StorageValues.string(entry, "operations", 16).toUpperCase(Locale.ROOT))
                .map(StorageProfile.Operation::valueOf).collect(Collectors.toUnmodifiableSet());
        if (result.isEmpty()) throw StorageValues.invalid("operations");
        return result;
    }

    private static Set<String> stringSet(Object value, String field, int max) {
        var list = StorageValues.list(value, field);
        if (list.size() > max) throw StorageValues.invalid(field);
        return list.stream().map(entry -> StorageValues.string(entry, field, 128))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String throwInvalid(String field) { throw StorageValues.invalid(field); }
}
