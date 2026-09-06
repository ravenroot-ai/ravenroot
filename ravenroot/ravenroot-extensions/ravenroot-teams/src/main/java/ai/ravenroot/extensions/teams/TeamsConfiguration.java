package ai.ravenroot.extensions.teams;

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

/** Strict operator-owned Microsoft Teams authority. */
record TeamsConfiguration(IngressAuthorityDeclaration authority,
                          IngressRequestProjectionPolicy projection,
                          StorePolicy store, Map<String, TeamsProfile> profiles) {
    static final String PACKAGE_ID = "ai.ravenroot.extensions.teams";
    static final String ENVIRONMENT = "RAVENROOT_TEAMS_CONFIG";

    TeamsConfiguration {
        if (!PACKAGE_ID.equals(authority.packageId()) || !PACKAGE_ID.equals(projection.packageId())) throw invalid();
        store = java.util.Objects.requireNonNull(store); profiles = Map.copyOf(profiles);
        if (profiles.isEmpty() || profiles.size() > authority.maxRoutes()) throw invalid();
        if (!projection.allowedHeaders().containsAll(Set.of("content-type", "x-ravenroot-teams-signature")))
            throw invalid();
        Set<String> routes = new HashSet<>();
        profiles.forEach((key, profile) -> {
            if (!key.equals(profile.tenantId() + "\u0000" + profile.name())
                    || profile.maxRequestBytes() > authority.maxRequestBytes()
                    || profile.maxResponseBytes() > authority.maxResponseBytes()
                    || profile.ackTimeoutMs() > authority.requestTimeout().toMillis()
                    || !routes.add(profile.webhookRoute())) throw invalid();
        });
    }

    static TeamsConfiguration fromEnvironment() { return fromEnvironment(System.getenv()); }

    static TeamsConfiguration fromEnvironment(Map<String, String> environment) {
        try {
            Map<String, Object> root = TeamsValues.json(
                    TeamsValues.canonicalBase64(environment.get(ENVIRONMENT), 4 * 1024 * 1024));
            TeamsValues.exact(root, Set.of("authority", "projection", "store", "profiles"));
            IngressAuthorityDeclaration authority = authority(TeamsValues.object(root.get("authority")));
            IngressRequestProjectionPolicy projection = projection(TeamsValues.object(root.get("projection")));
            StorePolicy store = store(TeamsValues.object(root.get("store")));
            Map<String, TeamsProfile> profiles = new LinkedHashMap<>();
            TeamsValues.object(root.get("profiles")).forEach((name, value) -> {
                TeamsProfile profile = profile(name, TeamsValues.object(value));
                if (profiles.put(profile.tenantId() + "\u0000" + profile.name(), profile) != null) throw invalid();
            });
            return new TeamsConfiguration(authority, projection, store, profiles);
        } catch (TeamsException failure) {
            throw failure.code() == TeamsException.Code.CONFIGURATION ? failure : invalid();
        } catch (RuntimeException failure) { throw invalid(); }
    }

    Optional<TeamsProfile> profile(String tenant, String name) {
        return Optional.ofNullable(profiles.get(tenant + "\u0000" + name));
    }

    private static IngressAuthorityDeclaration authority(Map<String, Object> value) {
        TeamsValues.exact(value, Set.of("listenerId", "pathPrefix", "requiredScopes", "maxRoutes",
                "maxConcurrentRequests", "maxRequestBytes", "maxResponseBytes", "requestTimeoutMs"));
        return new IngressAuthorityDeclaration(PACKAGE_ID, TeamsValues.string(value.get("listenerId"), 160),
                TeamsValues.string(value.get("pathPrefix"), 160),
                TeamsValues.strings(value.get("requiredScopes"), 32, 128),
                (int) TeamsValues.number(value.get("maxRoutes"), 1, 512),
                (int) TeamsValues.number(value.get("maxConcurrentRequests"), 1, 1_024),
                TeamsValues.number(value.get("maxRequestBytes"), 1, 16L * 1024 * 1024),
                TeamsValues.number(value.get("maxResponseBytes"), 1, 16L * 1024 * 1024),
                Duration.ofMillis(TeamsValues.number(value.get("requestTimeoutMs"), 100, 4_500)));
    }

    private static IngressRequestProjectionPolicy projection(Map<String, Object> value) {
        TeamsValues.exact(value, Set.of("maxRelativePathBytes", "maxQueryParameters", "maxQueryBytes",
                "maxHeaderCount", "maxHeaderBytes", "maxHeaderValueBytes"));
        return new IngressRequestProjectionPolicy(PACKAGE_ID,
                Set.of("content-type", "x-ravenroot-teams-signature"), null,
                (int) TeamsValues.number(value.get("maxRelativePathBytes"), 1, 8_192),
                (int) TeamsValues.number(value.get("maxQueryParameters"), 1, 256),
                (int) TeamsValues.number(value.get("maxQueryBytes"), 1, 16_384),
                (int) TeamsValues.number(value.get("maxHeaderCount"), 2, 32),
                (int) TeamsValues.number(value.get("maxHeaderBytes"), 1, 8_192),
                (int) TeamsValues.number(value.get("maxHeaderValueBytes"), 1, 2_048));
    }

    private static StorePolicy store(Map<String, Object> value) {
        TeamsValues.exact(value, Set.of("path", "maxDeliveries", "retentionHours"));
        return new StorePolicy(Path.of(TeamsValues.string(value.get("path"), 4_096)),
                (int) TeamsValues.number(value.get("maxDeliveries"), 1, 1_000_000),
                (int) TeamsValues.number(value.get("retentionHours"), 1, 24 * 365));
    }

    private static TeamsProfile profile(String name, Map<String, Object> value) {
        TeamsValues.exact(value, Set.of("tenantId", "workflowEndpoint", "microsoftTenantId", "teamId",
                "channels", "credentialBindingId", "credentialReference", "signingSecretReference",
                "webhookRoute", "limits"));
        Map<String, Object> limits = TeamsValues.object(value.get("limits"));
        TeamsValues.exact(limits, Set.of("requestTimeoutMs", "maxRequestBytes", "maxResponseBytes",
                "maxTextChars", "maxConcurrency", "maxPerSecond", "ackTimeoutMs", "signatureMaxAgeSeconds"));
        return new TeamsProfile(TeamsValues.string(value.get("tenantId"), 160), name,
                URI.create(TeamsValues.string(value.get("workflowEndpoint"), 2_048)),
                TeamsValues.string(value.get("microsoftTenantId"), 64),
                TeamsValues.string(value.get("teamId"), 160),
                TeamsValues.strings(value.get("channels"), 256, 160),
                TeamsValues.string(value.get("credentialBindingId"), 256),
                TeamsValues.string(value.get("credentialReference"), 256),
                TeamsValues.string(value.get("signingSecretReference"), 256),
                TeamsValues.string(value.get("webhookRoute"), 160),
                (int) TeamsValues.number(limits.get("requestTimeoutMs"), 100, 30_000),
                (int) TeamsValues.number(limits.get("maxRequestBytes"), 1, 1024 * 1024),
                (int) TeamsValues.number(limits.get("maxResponseBytes"), 1, 1024 * 1024),
                (int) TeamsValues.number(limits.get("maxTextChars"), 1, 28_000),
                (int) TeamsValues.number(limits.get("maxConcurrency"), 1, 64),
                (int) TeamsValues.number(limits.get("maxPerSecond"), 1, 50),
                (int) TeamsValues.number(limits.get("ackTimeoutMs"), 100, 4_500),
                (int) TeamsValues.number(limits.get("signatureMaxAgeSeconds"), 1, 300));
    }

    record StorePolicy(Path path, int maxDeliveries, int retentionHours) {
        StorePolicy { path = java.util.Objects.requireNonNull(path).toAbsolutePath().normalize(); }
    }

    private static TeamsException invalid() { return new TeamsException(TeamsException.Code.CONFIGURATION); }
}
