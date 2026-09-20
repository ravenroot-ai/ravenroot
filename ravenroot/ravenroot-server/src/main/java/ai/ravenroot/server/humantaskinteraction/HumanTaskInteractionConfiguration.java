package ai.ravenroot.server.humantaskinteraction;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.persistence.HandlerRegistration;
import ai.ravenroot.api.persistence.HumanTaskPresentationKind;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Operator-owned immutable registry for trusted Human Task presentation providers. */
public record HumanTaskInteractionConfiguration(Duration capabilityTtl, int maxCompletionBytes,
                                                 byte[] capabilitySecret,
                                                 Map<ProfileKey, Profile> profiles) {
    public static final String CONFIG_VARIABLE = "RAVENROOT_HUMAN_TASK_INTERACTION_CONFIG";
    private static final int MAX_CONFIG_BYTES = 65_536;

    public enum Kind { CUSTOM, EXTERNAL }

    public record ProfileKey(String id, int version) {
        public ProfileKey {
            id = HandlerRegistration.requireBoundedKey(id, "profile id");
            if (version < 1) throw new IllegalArgumentException("profile version must be positive");
        }
    }

    public record Profile(ProfileKey key, Kind kind, URI launchUri, URI origin,
                          byte[] completionSecret) {
        public Profile {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(kind, "kind");
            launchUri = requireLaunchUri(launchUri);
            origin = requireOrigin(origin);
            if (!sameOrigin(launchUri, origin)) {
                throw new IllegalArgumentException("profile launch URI must use its registered origin");
            }
            completionSecret = completionSecret == null ? new byte[0] : completionSecret.clone();
            if (kind == Kind.CUSTOM && completionSecret.length != 0) {
                throw new IllegalArgumentException("custom profiles do not accept provider credentials");
            }
            if (kind == Kind.EXTERNAL && completionSecret.length < 32) {
                throw new IllegalArgumentException("external profile signing secret must be at least 32 bytes");
            }
        }

        @Override public byte[] completionSecret() { return completionSecret.clone(); }
    }

    public HumanTaskInteractionConfiguration {
        Objects.requireNonNull(capabilityTtl, "capabilityTtl");
        if (capabilityTtl.isNegative() || capabilityTtl.isZero()
                || capabilityTtl.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException("capability ttl must be between 1 second and 30 minutes");
        }
        if (maxCompletionBytes < 1_024 || maxCompletionBytes > 1_048_576) {
            throw new IllegalArgumentException("completion body limit must be between 1024 and 1048576 bytes");
        }
        capabilitySecret = Objects.requireNonNull(capabilitySecret, "capabilitySecret").clone();
        if (capabilitySecret.length < 32) {
            throw new IllegalArgumentException("capability signing secret must be at least 32 bytes");
        }
        profiles = Map.copyOf(Objects.requireNonNull(profiles, "profiles"));
        if (profiles.isEmpty() || profiles.size() > 64) {
            throw new IllegalArgumentException("interaction registry must contain 1..64 profiles");
        }
        profiles.forEach((key, profile) -> {
            if (!key.equals(profile.key())) throw new IllegalArgumentException("profile key mismatch");
        });
    }

    @Override public byte[] capabilitySecret() { return capabilitySecret.clone(); }

    /**
     * Origins admitted by the Workbench frame policy, derived only from this operator registry.
     * @return immutable registered origin set
     */
    public Set<URI> presentationOrigins() {
        return profiles.values().stream().map(Profile::origin)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public static HumanTaskInteractionConfiguration fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String configured = environment.getOrDefault(CONFIG_VARIABLE, "").strip();
        if (configured.isEmpty()) return null;
        Path path = Path.of(configured).toAbsolutePath().normalize();
        byte[] bytes;
        try {
            long size = Files.size(path);
            if (size < 1 || size > MAX_CONFIG_BYTES) {
                throw new IllegalArgumentException(CONFIG_VARIABLE + " file must be 1..65536 bytes");
            }
            bytes = Files.readAllBytes(path);
        } catch (IOException failure) {
            throw new IllegalArgumentException("cannot read " + CONFIG_VARIABLE, failure);
        }
        PayloadValue value = PayloadJson.read(bytes,
                new PayloadLimits(MAX_CONFIG_BYTES, 5, 256, 64, 16_384, 256));
        if (!(value instanceof PayloadValue.MapValue root)
                || !root.entries().keySet().equals(java.util.Set.of(
                "schemaVersion", "capabilityTtlSeconds", "maxCompletionBytes",
                "capabilitySecretBase64", "profiles"))
                || integer(root, "schemaVersion") != 1) {
            throw new IllegalArgumentException("invalid Human Task interaction configuration");
        }
        byte[] capabilitySecret = secret(text(root, "capabilitySecretBase64"), "capability secret");
        if (!(root.entries().get("profiles") instanceof PayloadValue.ListValue profileValues)
                || profileValues.values().isEmpty() || profileValues.values().size() > 64) {
            throw new IllegalArgumentException("interaction profiles must be a non-empty bounded list");
        }
        Map<ProfileKey, Profile> profiles = new LinkedHashMap<>();
        for (PayloadValue entry : profileValues.values()) {
            if (!(entry instanceof PayloadValue.MapValue profile)
                    || !profile.entries().keySet().stream().allMatch(java.util.Set.of(
                    "id", "version", "kind", "launchUri", "origin", "completionSecretBase64")::contains)
                    || !profile.entries().keySet().containsAll(List.of(
                    "id", "version", "kind", "launchUri", "origin"))) {
                throw new IllegalArgumentException("invalid interaction profile");
            }
            var key = new ProfileKey(text(profile, "id"), Math.toIntExact(integer(profile, "version")));
            Kind kind = Kind.valueOf(text(profile, "kind").toUpperCase(java.util.Locale.ROOT));
            byte[] completion = profile.entries().containsKey("completionSecretBase64")
                    ? secret(text(profile, "completionSecretBase64"), "completion secret") : new byte[0];
            Profile registered = new Profile(key, kind, URI.create(text(profile, "launchUri")),
                    URI.create(text(profile, "origin")), completion);
            if (profiles.putIfAbsent(key, registered) != null) {
                throw new IllegalArgumentException("duplicate interaction profile");
            }
        }
        return new HumanTaskInteractionConfiguration(
                Duration.ofSeconds(integer(root, "capabilityTtlSeconds")),
                Math.toIntExact(integer(root, "maxCompletionBytes")), capabilitySecret, profiles);
    }

    public Profile requireProfile(String id, int version, HumanTaskPresentationKind presentationKind) {
        Profile profile = profiles.get(new ProfileKey(id, version));
        if (profile == null) throw new IllegalArgumentException("unregistered interaction profile");
        Kind expected = presentationKind == HumanTaskPresentationKind.CUSTOM ? Kind.CUSTOM
                : presentationKind == HumanTaskPresentationKind.EXTERNAL ? Kind.EXTERNAL : null;
        if (expected == null || profile.kind() != expected) {
            throw new IllegalArgumentException("interaction profile kind mismatch");
        }
        return profile;
    }

    private static URI requireLaunchUri(URI value) {
        Objects.requireNonNull(value, "launchUri");
        if (!allowedScheme(value) || value.getHost() == null || value.getUserInfo() != null
                || value.getFragment() != null) {
            throw new IllegalArgumentException("profile launch URI must be an absolute safe HTTP URI");
        }
        return value.normalize();
    }

    private static URI requireOrigin(URI value) {
        Objects.requireNonNull(value, "origin");
        if (!allowedScheme(value) || value.getHost() == null || value.getUserInfo() != null
                || value.getRawPath() != null && !value.getRawPath().isEmpty()
                || value.getRawQuery() != null || value.getRawFragment() != null) {
            throw new IllegalArgumentException("profile origin must contain only scheme and authority");
        }
        return value.normalize();
    }

    private static boolean allowedScheme(URI value) {
        if ("https".equalsIgnoreCase(value.getScheme())) return true;
        return "http".equalsIgnoreCase(value.getScheme())
                && ("localhost".equalsIgnoreCase(value.getHost())
                || "127.0.0.1".equals(value.getHost()) || "::1".equals(value.getHost()));
    }

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String text(PayloadValue.MapValue map, String name) {
        if (!(map.entries().get(name) instanceof PayloadValue.TextValue text) || text.value().isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank text");
        }
        return text.value();
    }

    private static long integer(PayloadValue.MapValue map, String name) {
        if (!(map.entries().get(name) instanceof PayloadValue.IntegerValue integer)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return integer.value();
    }

    private static byte[] secret(String value, String name) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length < 32) throw new IllegalArgumentException(name + " must be at least 32 bytes");
            return decoded;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(name + " must be base64 for at least 32 bytes", invalid);
        }
    }
}
