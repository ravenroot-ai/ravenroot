package ai.ravenroot.extensions.mattermost;

import ai.ravenroot.api.ingress.IngressAuthorityDeclaration;
import ai.ravenroot.api.ingress.IngressRequestProjectionPolicy;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Strict operator-owned Mattermost authority. */
record MattermostConfiguration(IngressAuthorityDeclaration authority,
                               IngressRequestProjectionPolicy projection,
                               StorePolicy store, Map<String, MattermostProfile> profiles) {
    static final String PACKAGE_ID = "ai.ravenroot.extensions.mattermost";
    static final String ENVIRONMENT = "RAVENROOT_MATTERMOST_CONFIG";

    MattermostConfiguration {
        if (!PACKAGE_ID.equals(authority.packageId()) || !PACKAGE_ID.equals(projection.packageId())) throw invalid();
        store = java.util.Objects.requireNonNull(store); profiles = Map.copyOf(profiles);
        if (profiles.isEmpty() || profiles.size() > authority.maxRoutes()) throw invalid();
        if (!projection.allowedHeaders().contains("content-type")) throw invalid();
        Set<String> routes = new HashSet<>();
        profiles.forEach((key, profile) -> {
            if (!key.equals(profile.tenantId() + "\u0000" + profile.name())
                    || profile.maxRequestBytes() > authority.maxRequestBytes()
                    || profile.maxResponseBytes() > authority.maxResponseBytes()
                    || profile.requestTimeoutMs() > authority.requestTimeout().toMillis()
                    || !routes.add(profile.outgoingWebhookRoute())) throw invalid();
        });
    }

    static MattermostConfiguration fromEnvironment() { return fromEnvironment(System.getenv()); }
    static MattermostConfiguration fromEnvironment(Map<String, String> environment) {
        try {
            Map<String, Object> root = MattermostValues.json(
                    MattermostValues.canonicalBase64(environment.get(ENVIRONMENT), 4 * 1024 * 1024));
            MattermostValues.exact(root, Set.of("authority", "projection", "store", "profiles"));
            IngressAuthorityDeclaration authority = authority(MattermostValues.object(root.get("authority")));
            IngressRequestProjectionPolicy projection = projection(MattermostValues.object(root.get("projection")));
            StorePolicy store = store(MattermostValues.object(root.get("store")));
            Map<String, MattermostProfile> profiles = new LinkedHashMap<>();
            MattermostValues.object(root.get("profiles")).forEach((name, value) -> {
                MattermostProfile profile = profile(name, MattermostValues.object(value));
                if (profiles.put(profile.tenantId() + "\u0000" + profile.name(), profile) != null) throw invalid();
            });
            return new MattermostConfiguration(authority, projection, store, profiles);
        } catch (MattermostException failure) {
            throw failure.code() == MattermostException.Code.CONFIGURATION ? failure : invalid();
        } catch (RuntimeException failure) { throw invalid(); }
    }

    Optional<MattermostProfile> profile(String tenant, String name) {
        return Optional.ofNullable(profiles.get(tenant + "\u0000" + name));
    }
    private static IngressAuthorityDeclaration authority(Map<String, Object> value) {
        MattermostValues.exact(value, Set.of("listenerId", "pathPrefix", "requiredScopes", "maxRoutes",
                "maxConcurrentRequests", "maxRequestBytes", "maxResponseBytes", "requestTimeoutMs"));
        return new IngressAuthorityDeclaration(PACKAGE_ID, MattermostValues.string(value.get("listenerId"), 160),
                MattermostValues.string(value.get("pathPrefix"), 160),
                MattermostValues.strings(value.get("requiredScopes"), 32, 128),
                (int) MattermostValues.number(value.get("maxRoutes"), 1, 512),
                (int) MattermostValues.number(value.get("maxConcurrentRequests"), 1, 1_024),
                MattermostValues.number(value.get("maxRequestBytes"), 1, 16L * 1024 * 1024),
                MattermostValues.number(value.get("maxResponseBytes"), 1, 16L * 1024 * 1024),
                Duration.ofMillis(MattermostValues.number(value.get("requestTimeoutMs"), 100, 2_800)));
    }
    private static IngressRequestProjectionPolicy projection(Map<String, Object> value) {
        MattermostValues.exact(value, Set.of("maxRelativePathBytes", "maxQueryParameters", "maxQueryBytes",
                "maxHeaderCount", "maxHeaderBytes", "maxHeaderValueBytes"));
        return new IngressRequestProjectionPolicy(PACKAGE_ID, Set.of("content-type"), null,
                (int) MattermostValues.number(value.get("maxRelativePathBytes"), 1, 8_192),
                (int) MattermostValues.number(value.get("maxQueryParameters"), 1, 256),
                (int) MattermostValues.number(value.get("maxQueryBytes"), 1, 16_384),
                (int) MattermostValues.number(value.get("maxHeaderCount"), 1, 32),
                (int) MattermostValues.number(value.get("maxHeaderBytes"), 1, 8_192),
                (int) MattermostValues.number(value.get("maxHeaderValueBytes"), 1, 2_048));
    }
    private static StorePolicy store(Map<String, Object> value) {
        MattermostValues.exact(value, Set.of("path", "maxDeliveries", "retentionHours"));
        return new StorePolicy(Path.of(MattermostValues.string(value.get("path"), 4_096)),
                (int) MattermostValues.number(value.get("maxDeliveries"), 1, 1_000_000),
                (int) MattermostValues.number(value.get("retentionHours"), 1, 24 * 365));
    }
    private static MattermostProfile profile(String name, Map<String, Object> value) {
        MattermostValues.exact(value, Set.of("tenantId", "origin", "teamId", "publicChannels",
                "credentialBindingId", "credentialReference", "webhookTokenReference",
                "outgoingWebhookRoute", "limits"));
        Map<String, Object> limits = MattermostValues.object(value.get("limits"));
        MattermostValues.exact(limits, Set.of("maxTextChars", "maxRequestBytes", "maxResponseBytes",
                "maxConcurrency", "maxPerSecond", "requestTimeoutMs", "retries"));
        return new MattermostProfile(MattermostValues.string(value.get("tenantId"), 160), name,
                URI.create(MattermostValues.string(value.get("origin"), 512)),
                MattermostValues.string(value.get("teamId"), 32),
                MattermostValues.strings(value.get("publicChannels"), 256, 32),
                MattermostValues.string(value.get("credentialBindingId"), 256),
                MattermostValues.string(value.get("credentialReference"), 256),
                MattermostValues.string(value.get("webhookTokenReference"), 256),
                MattermostValues.string(value.get("outgoingWebhookRoute"), 160),
                (int) MattermostValues.number(limits.get("maxTextChars"), 1, 16_383),
                (int) MattermostValues.number(limits.get("maxRequestBytes"), 1, 1024 * 1024),
                (int) MattermostValues.number(limits.get("maxResponseBytes"), 1, 1024 * 1024),
                (int) MattermostValues.number(limits.get("maxConcurrency"), 1, 64),
                (int) MattermostValues.number(limits.get("maxPerSecond"), 1, 100),
                (int) MattermostValues.number(limits.get("requestTimeoutMs"), 100, 2_800),
                (int) MattermostValues.number(limits.get("retries"), 0, 3));
    }
    record StorePolicy(Path path, int maxDeliveries, int retentionHours) {
        StorePolicy { path = java.util.Objects.requireNonNull(path).toAbsolutePath().normalize(); }
    }
    private static MattermostException invalid() {
        return new MattermostException(MattermostException.Code.CONFIGURATION);
    }
}
