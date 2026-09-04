package ai.ravenroot.extensions.slack;

import ai.ravenroot.api.ingress.IngressAuthorityDeclaration;
import ai.ravenroot.api.ingress.IngressRequestProjectionPolicy;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Strict operator-owned Slack authority. */
record SlackConfiguration(IngressAuthorityDeclaration authority,
                          IngressRequestProjectionPolicy projection,
                          StorePolicy store, Map<String, SlackProfile> profiles) {
    static final String PACKAGE_ID = "ai.ravenroot.extensions.slack";
    static final String ENVIRONMENT = "RAVENROOT_SLACK_CONFIG";

    SlackConfiguration {
        if (!PACKAGE_ID.equals(authority.packageId()) || !PACKAGE_ID.equals(projection.packageId())) throw invalid();
        store = java.util.Objects.requireNonNull(store); profiles = Map.copyOf(profiles);
        if (profiles.isEmpty() || profiles.size() * 2 > authority.maxRoutes()) throw invalid();
        if (!projection.allowedHeaders().containsAll(Set.of("content-type", "x-slack-signature",
                "x-slack-request-timestamp", "x-slack-retry-num", "x-slack-retry-reason"))) throw invalid();
        profiles.forEach((key, profile) -> {
            if (!key.equals(profile.tenantId() + "\u0000" + profile.name())
                    || profile.maxRequestBytes() > authority.maxRequestBytes()
                    || profile.maxResponseBytes() > authority.maxResponseBytes()
                    || profile.requestTimeoutMs() > authority.requestTimeout().toMillis()) throw invalid();
        });
    }

    static SlackConfiguration fromEnvironment() { return fromEnvironment(System.getenv()); }
    static SlackConfiguration fromEnvironment(Map<String, String> environment) {
        try {
            Map<String, Object> root = SlackValues.json(
                    SlackValues.canonicalBase64(environment.get(ENVIRONMENT), 4 * 1024 * 1024));
            SlackValues.exact(root, Set.of("authority", "projection", "store", "profiles"));
            IngressAuthorityDeclaration authority = authority(SlackValues.object(root.get("authority")));
            IngressRequestProjectionPolicy projection = projection(SlackValues.object(root.get("projection")));
            StorePolicy store = store(SlackValues.object(root.get("store")));
            Map<String, SlackProfile> profiles = new LinkedHashMap<>();
            SlackValues.object(root.get("profiles")).forEach((name, value) -> {
                SlackProfile profile = profile(name, SlackValues.object(value));
                if (profiles.put(profile.tenantId() + "\u0000" + profile.name(), profile) != null) throw invalid();
            });
            return new SlackConfiguration(authority, projection, store, profiles);
        } catch (SlackException failure) { throw failure.code() == SlackException.Code.CONFIGURATION ? failure : invalid(); }
        catch (RuntimeException failure) { throw invalid(); }
    }

    Optional<SlackProfile> profile(String tenant, String name) {
        return Optional.ofNullable(profiles.get(tenant + "\u0000" + name));
    }
    private static IngressAuthorityDeclaration authority(Map<String, Object> value) {
        SlackValues.exact(value, Set.of("listenerId", "pathPrefix", "requiredScopes", "maxRoutes",
                "maxConcurrentRequests", "maxRequestBytes", "maxResponseBytes", "requestTimeoutMs"));
        return new IngressAuthorityDeclaration(PACKAGE_ID, SlackValues.string(value.get("listenerId"), 160),
                SlackValues.string(value.get("pathPrefix"), 160), SlackValues.strings(value.get("requiredScopes"), 32, 128),
                (int) SlackValues.number(value.get("maxRoutes"), 2, 512),
                (int) SlackValues.number(value.get("maxConcurrentRequests"), 1, 1_024),
                SlackValues.number(value.get("maxRequestBytes"), 1, 16L * 1024 * 1024),
                SlackValues.number(value.get("maxResponseBytes"), 1, 16L * 1024 * 1024),
                Duration.ofMillis(SlackValues.number(value.get("requestTimeoutMs"), 100, 2_800)));
    }
    private static IngressRequestProjectionPolicy projection(Map<String, Object> value) {
        SlackValues.exact(value, Set.of("maxRelativePathBytes", "maxQueryParameters", "maxQueryBytes",
                "maxHeaderCount", "maxHeaderBytes", "maxHeaderValueBytes"));
        return new IngressRequestProjectionPolicy(PACKAGE_ID,
                Set.of("content-type", "x-slack-signature", "x-slack-request-timestamp",
                        "x-slack-retry-num", "x-slack-retry-reason"), null,
                (int) SlackValues.number(value.get("maxRelativePathBytes"), 1, 8_192),
                (int) SlackValues.number(value.get("maxQueryParameters"), 1, 256),
                (int) SlackValues.number(value.get("maxQueryBytes"), 1, 16_384),
                (int) SlackValues.number(value.get("maxHeaderCount"), 5, 32),
                (int) SlackValues.number(value.get("maxHeaderBytes"), 1, 8_192),
                (int) SlackValues.number(value.get("maxHeaderValueBytes"), 1, 2_048));
    }
    private static StorePolicy store(Map<String, Object> value) {
        SlackValues.exact(value, Set.of("path", "maxDeliveries", "retentionHours"));
        return new StorePolicy(Path.of(SlackValues.string(value.get("path"), 4_096)),
                (int) SlackValues.number(value.get("maxDeliveries"), 1, 1_000_000),
                (int) SlackValues.number(value.get("retentionHours"), 1, 24 * 365));
    }
    private static SlackProfile profile(String name, Map<String, Object> value) {
        SlackValues.exact(value, Set.of("tenantId", "apiOrigin", "teamId", "applicationId",
                "credentialBindingId", "credentialReference", "signingSecretReference", "eventsRoute",
                "commandsRoute", "channels", "eventTypes", "commands", "scopes", "limits"));
        Map<String, Object> limits = SlackValues.object(value.get("limits"));
        SlackValues.exact(limits, Set.of("requestTimeoutMs", "maxRequestBytes", "maxResponseBytes",
                "maxTextChars", "maxConcurrency", "maxPerSecond", "retries", "signatureMaxAgeSeconds"));
        return new SlackProfile(SlackValues.string(value.get("tenantId"), 160), name,
                URI.create(SlackValues.string(value.get("apiOrigin"), 512)),
                SlackValues.string(value.get("teamId"), 32), SlackValues.string(value.get("applicationId"), 32),
                SlackValues.string(value.get("credentialBindingId"), 256),
                SlackValues.string(value.get("credentialReference"), 256),
                SlackValues.string(value.get("signingSecretReference"), 256),
                SlackValues.string(value.get("eventsRoute"), 160), SlackValues.string(value.get("commandsRoute"), 160),
                SlackValues.strings(value.get("channels"), 256, 32),
                SlackValues.strings(value.get("eventTypes"), 128, 80),
                SlackValues.strings(value.get("commands"), 100, 33),
                SlackValues.strings(value.get("scopes"), 128, 80),
                (int) SlackValues.number(limits.get("requestTimeoutMs"), 100, 2_800),
                (int) SlackValues.number(limits.get("maxRequestBytes"), 1, 1024 * 1024),
                (int) SlackValues.number(limits.get("maxResponseBytes"), 1, 1024 * 1024),
                (int) SlackValues.number(limits.get("maxTextChars"), 1, 4_000),
                (int) SlackValues.number(limits.get("maxConcurrency"), 1, 64),
                (int) SlackValues.number(limits.get("maxPerSecond"), 1, 50),
                (int) SlackValues.number(limits.get("retries"), 0, 3),
                (int) SlackValues.number(limits.get("signatureMaxAgeSeconds"), 1, 300));
    }
    record StorePolicy(Path path, int maxDeliveries, int retentionHours) {
        StorePolicy { path = java.util.Objects.requireNonNull(path).toAbsolutePath().normalize(); }
    }
    private static SlackException invalid() { return new SlackException(SlackException.Code.CONFIGURATION); }
}
