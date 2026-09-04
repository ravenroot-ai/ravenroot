package ai.ravenroot.extensions.discord;

import ai.ravenroot.api.ingress.IngressAuthorityDeclaration;
import ai.ravenroot.api.ingress.IngressRequestProjectionPolicy;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Strict operator-owned Discord authority. */
record DiscordConfiguration(IngressAuthorityDeclaration authority,
                            IngressRequestProjectionPolicy projection,
                            StorePolicy store, Map<String, DiscordProfile> profiles) {
    static final String PACKAGE_ID = "ai.ravenroot.extensions.discord";
    static final String ENVIRONMENT = "RAVENROOT_DISCORD_CONFIG";

    DiscordConfiguration {
        if (!PACKAGE_ID.equals(authority.packageId()) || !PACKAGE_ID.equals(projection.packageId())) throw invalid();
        store = java.util.Objects.requireNonNull(store); profiles = Map.copyOf(profiles);
        if (profiles.isEmpty() || profiles.size() > authority.maxRoutes()) throw invalid();
        if (!projection.allowedHeaders().containsAll(Set.of("x-signature-ed25519", "x-signature-timestamp"))) throw invalid();
        profiles.forEach((key, profile) -> {
            if (!key.equals(profile.tenantId() + "\u0000" + profile.name())
                    || profile.maxRequestBytes() > authority.maxRequestBytes()
                    || profile.maxResponseBytes() > authority.maxResponseBytes()
                    || profile.requestTimeoutMs() > authority.requestTimeout().toMillis()) throw invalid();
        });
    }

    static DiscordConfiguration fromEnvironment() { return fromEnvironment(System.getenv()); }

    static DiscordConfiguration fromEnvironment(Map<String, String> environment) {
        try {
            Map<String, Object> root = DiscordValues.json(
                    DiscordValues.canonicalBase64(environment.get(ENVIRONMENT), 4 * 1024 * 1024));
            DiscordValues.exact(root, Set.of("authority", "projection", "store", "profiles"));
            IngressAuthorityDeclaration authority = authority(DiscordValues.object(root.get("authority")));
            IngressRequestProjectionPolicy projection = projection(DiscordValues.object(root.get("projection")));
            StorePolicy store = store(DiscordValues.object(root.get("store")));
            Map<String, DiscordProfile> profiles = new LinkedHashMap<>();
            DiscordValues.object(root.get("profiles")).forEach((name, value) -> {
                DiscordProfile profile = profile(name, DiscordValues.object(value));
                profiles.put(profile.tenantId() + "\u0000" + profile.name(), profile);
            });
            return new DiscordConfiguration(authority, projection, store, profiles);
        } catch (DiscordException failure) { throw failure.code() == DiscordException.Code.CONFIGURATION ? failure : invalid(); }
        catch (RuntimeException failure) { throw invalid(); }
    }

    Optional<DiscordProfile> profile(String tenant, String name) {
        return Optional.ofNullable(profiles.get(tenant + "\u0000" + name));
    }

    private static IngressAuthorityDeclaration authority(Map<String, Object> value) {
        DiscordValues.exact(value, Set.of("listenerId", "pathPrefix", "requiredScopes", "maxRoutes",
                "maxConcurrentRequests", "maxRequestBytes", "maxResponseBytes", "requestTimeoutMs"));
        return new IngressAuthorityDeclaration(PACKAGE_ID, DiscordValues.string(value.get("listenerId"), 160),
                DiscordValues.string(value.get("pathPrefix"), 160), DiscordValues.strings(value.get("requiredScopes"), 32, 128),
                (int) DiscordValues.number(value.get("maxRoutes"), 1, 256),
                (int) DiscordValues.number(value.get("maxConcurrentRequests"), 1, 1_024),
                DiscordValues.number(value.get("maxRequestBytes"), 1, 16L * 1024 * 1024),
                DiscordValues.number(value.get("maxResponseBytes"), 1, 16L * 1024 * 1024),
                Duration.ofMillis(DiscordValues.number(value.get("requestTimeoutMs"), 100, 300_000)));
    }

    private static IngressRequestProjectionPolicy projection(Map<String, Object> value) {
        DiscordValues.exact(value, Set.of("maxRelativePathBytes", "maxQueryParameters", "maxQueryBytes",
                "maxHeaderCount", "maxHeaderBytes", "maxHeaderValueBytes"));
        return new IngressRequestProjectionPolicy(PACKAGE_ID,
                Set.of("x-signature-ed25519", "x-signature-timestamp"), null,
                (int) DiscordValues.number(value.get("maxRelativePathBytes"), 1, 8_192),
                (int) DiscordValues.number(value.get("maxQueryParameters"), 1, 256),
                (int) DiscordValues.number(value.get("maxQueryBytes"), 1, 16_384),
                (int) DiscordValues.number(value.get("maxHeaderCount"), 2, 32),
                (int) DiscordValues.number(value.get("maxHeaderBytes"), 1, 8_192),
                (int) DiscordValues.number(value.get("maxHeaderValueBytes"), 1, 2_048));
    }

    private static StorePolicy store(Map<String, Object> value) {
        DiscordValues.exact(value, Set.of("path", "maxDeliveries", "retentionHours"));
        return new StorePolicy(Path.of(DiscordValues.string(value.get("path"), 4_096)),
                (int) DiscordValues.number(value.get("maxDeliveries"), 1, 1_000_000),
                (int) DiscordValues.number(value.get("retentionHours"), 1, 24 * 365));
    }

    private static DiscordProfile profile(String name, Map<String, Object> value) {
        DiscordValues.exact(value, Set.of("tenantId", "apiOrigin", "applicationId", "publicKeyHex",
                "guilds", "commands", "credentialBindingId", "credentialReference", "route", "limits"));
        Map<String, Set<String>> channels = new LinkedHashMap<>();
        DiscordValues.object(value.get("guilds")).forEach((guild, raw) -> {
            DiscordProfile.snowflake(guild);
            channels.put(guild, DiscordValues.strings(raw, 256, 20));
        });
        Map<String, Object> limits = DiscordValues.object(value.get("limits"));
        DiscordValues.exact(limits, Set.of("requestTimeoutMs", "maxRequestBytes", "maxResponseBytes",
                "maxContentChars", "maxAttachmentBytes", "maxAttachments", "maxConcurrency",
                "maxPerSecond", "retries", "signatureMaxAgeSeconds", "futureSkewSeconds"));
        byte[] publicKey;
        try { publicKey = java.util.HexFormat.of().parseHex(DiscordValues.string(value.get("publicKeyHex"), 64)); }
        catch (IllegalArgumentException failure) { throw invalid(); }
        return new DiscordProfile(DiscordValues.string(value.get("tenantId"), 160), name,
                URI.create(DiscordValues.string(value.get("apiOrigin"), 512)),
                DiscordValues.string(value.get("applicationId"), 20), publicKey, channels.keySet(), channels,
                DiscordValues.strings(value.get("commands"), 100, 32),
                DiscordValues.string(value.get("credentialBindingId"), 256),
                DiscordValues.string(value.get("credentialReference"), 256),
                DiscordValues.string(value.get("route"), 160),
                (int) DiscordValues.number(limits.get("requestTimeoutMs"), 100, 2_800),
                (int) DiscordValues.number(limits.get("maxRequestBytes"), 1, 1024 * 1024),
                (int) DiscordValues.number(limits.get("maxResponseBytes"), 1, 1024 * 1024),
                (int) DiscordValues.number(limits.get("maxContentChars"), 1, 2_000),
                (int) DiscordValues.number(limits.get("maxAttachmentBytes"), 1, 8 * 1024 * 1024),
                (int) DiscordValues.number(limits.get("maxAttachments"), 0, 10),
                (int) DiscordValues.number(limits.get("maxConcurrency"), 1, 64),
                (int) DiscordValues.number(limits.get("maxPerSecond"), 1, 50),
                (int) DiscordValues.number(limits.get("retries"), 0, 3),
                (int) DiscordValues.number(limits.get("signatureMaxAgeSeconds"), 1, 300),
                (int) DiscordValues.number(limits.get("futureSkewSeconds"), 0, 60));
    }

    record StorePolicy(Path path, int maxDeliveries, int retentionHours) {
        StorePolicy { path = java.util.Objects.requireNonNull(path).toAbsolutePath().normalize(); }
    }

    private static DiscordException invalid() { return new DiscordException(DiscordException.Code.CONFIGURATION); }
}
