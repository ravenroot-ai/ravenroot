package ai.ravenroot.extensions.github;

import ai.ravenroot.api.ingress.IngressAuthorityDeclaration;
import ai.ravenroot.api.ingress.IngressRequestProjectionPolicy;
import ai.ravenroot.api.payload.PayloadJson;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Strict operator configuration; graph content can select but never construct these authorities. */
public record GithubConfiguration(IngressAuthorityDeclaration authority,
                                  IngressRequestProjectionPolicy projection,
                                  StorePolicy store, Map<String, GithubProfile> profiles) {
    public static final String PACKAGE_ID = "ai.ravenroot.extensions.github";
    public static final String ENVIRONMENT = "RAVENROOT_GITHUB_CONFIG";

    public GithubConfiguration {
        if (!PACKAGE_ID.equals(authority.packageId()) || !PACKAGE_ID.equals(projection.packageId())) throw invalid();
        store = java.util.Objects.requireNonNull(store);
        profiles = Map.copyOf(profiles);
        if (profiles.isEmpty() || profiles.size() > authority.maxRoutes()) throw invalid();
        if (!projection.allowedHeaders().containsAll(Set.of("x-hub-signature-256", "x-github-delivery",
                "x-github-event"))) throw invalid();
        profiles.forEach((key, value) -> {
            if (!key.equals(value.tenantId() + "\u0000" + value.name())) throw invalid();
            if (value.maxRequestBytes() > authority.maxRequestBytes()
                    || value.timeoutMs() > authority.requestTimeout().toMillis()) throw invalid();
        });
    }

    public static GithubConfiguration fromEnvironment() { return fromEnvironment(System.getenv()); }

    static GithubConfiguration fromEnvironment(Map<String, String> environment) {
        try {
            byte[] bytes = GithubValues.canonicalBase64(environment.get(ENVIRONMENT), 4 * 1024 * 1024);
            Map<String, Object> root = GithubValues.json(bytes);
            GithubValues.exact(root, Set.of("authority", "projection", "store", "profiles"));
            IngressAuthorityDeclaration authority = authority(GithubValues.object(root.get("authority")));
            IngressRequestProjectionPolicy projection = projection(GithubValues.object(root.get("projection")));
            StorePolicy store = store(GithubValues.object(root.get("store")));
            Map<String, Object> rawProfiles = GithubValues.object(root.get("profiles"));
            Map<String, GithubProfile> profiles = new LinkedHashMap<>();
            rawProfiles.forEach((name, raw) -> {
                GithubProfile profile = profile(name, GithubValues.object(raw));
                profiles.put(profile.tenantId() + "\u0000" + name, profile);
            });
            return new GithubConfiguration(authority, projection, store, profiles);
        } catch (GithubException failure) { throw failure; }
        catch (RuntimeException failure) { throw invalid(); }
    }

    Optional<GithubProfile> profile(String tenantId, String name) {
        return Optional.ofNullable(profiles.get(tenantId + "\u0000" + name));
    }

    private static IngressAuthorityDeclaration authority(Map<String, Object> value) {
        GithubValues.exact(value, Set.of("listenerId", "pathPrefix", "requiredScopes", "maxRoutes",
                "maxConcurrentRequests", "maxRequestBytes", "maxResponseBytes", "requestTimeoutMs"));
        return new IngressAuthorityDeclaration(PACKAGE_ID, GithubValues.string(value.get("listenerId"), 160),
                GithubValues.string(value.get("pathPrefix"), 160), GithubValues.strings(value.get("requiredScopes"), 32, 128),
                (int) GithubValues.number(value.get("maxRoutes"), 1, 256),
                (int) GithubValues.number(value.get("maxConcurrentRequests"), 1, 1_024),
                GithubValues.number(value.get("maxRequestBytes"), 1, 16L * 1024 * 1024),
                GithubValues.number(value.get("maxResponseBytes"), 1, 16L * 1024 * 1024),
                Duration.ofMillis(GithubValues.number(value.get("requestTimeoutMs"), 1, 300_000)));
    }

    private static IngressRequestProjectionPolicy projection(Map<String, Object> value) {
        GithubValues.exact(value, Set.of("maxRelativePathBytes", "maxQueryParameters", "maxQueryBytes",
                "maxHeaderCount", "maxHeaderBytes", "maxHeaderValueBytes"));
        return new IngressRequestProjectionPolicy(PACKAGE_ID,
                Set.of("x-hub-signature-256", "x-github-delivery", "x-github-event"), "x-github-delivery",
                (int) GithubValues.number(value.get("maxRelativePathBytes"), 1, 8_192),
                (int) GithubValues.number(value.get("maxQueryParameters"), 1, 256),
                (int) GithubValues.number(value.get("maxQueryBytes"), 1, 16_384),
                (int) GithubValues.number(value.get("maxHeaderCount"), 3, 32),
                (int) GithubValues.number(value.get("maxHeaderBytes"), 1, 8_192),
                (int) GithubValues.number(value.get("maxHeaderValueBytes"), 1, 2_048));
    }

    private static StorePolicy store(Map<String, Object> value) {
        GithubValues.exact(value, Set.of("path", "maxOperations", "retentionHours", "leaseMs"));
        return new StorePolicy(Path.of(GithubValues.string(value.get("path"), 4_096)),
                (int) GithubValues.number(value.get("maxOperations"), 1, 1_000_000),
                (int) GithubValues.number(value.get("retentionHours"), 1, 24 * 365),
                (int) GithubValues.number(value.get("leaseMs"), 1_000, 300_000));
    }

    private static GithubProfile profile(String name, Map<String, Object> value) {
        GithubValues.exact(value, Set.of("tenantId", "apiOrigin", "owner", "repository", "repositoryId",
                "installationId", "reviewerLogin", "credentialBindingId", "credentialReference", "webhookSecretReference",
                "route", "events", "project", "workflowIds", "release", "limits"));
        Map<String, Set<String>> events = new LinkedHashMap<>();
        GithubValues.object(value.get("events")).forEach((event, actions) ->
                events.put(event, GithubValues.strings(actions, 64, 64)));
        Map<String, Object> project = GithubValues.object(value.get("project"));
        GithubValues.exact(project, Set.of("projectId", "statusFieldId", "attemptsFieldId", "generationFieldId",
                "statusOptions", "allowedTransitions", "claimTransition"));
        Map<String, String> options = new LinkedHashMap<>();
        GithubValues.object(project.get("statusOptions")).forEach((key, option) ->
                options.put(key, GithubValues.string(option, 128)));
        Map<String, Object> release = GithubValues.object(value.get("release"));
        GithubValues.exact(release, Set.of("branch", "versionPath", "fragmentsPath", "allowedKinds", "maxFiles"));
        Map<String, Object> limits = GithubValues.object(value.get("limits"));
        GithubValues.exact(limits, Set.of("timeoutMs", "maxRequestBytes", "maxResponseBytes", "maxConcurrency",
                "maxPolls", "pollIntervalMs"));
        return new GithubProfile(name, GithubValues.string(value.get("tenantId"), 160),
                URI.create(GithubValues.string(value.get("apiOrigin"), 512)),
                GithubValues.string(value.get("owner"), 100), GithubValues.string(value.get("repository"), 100),
                GithubValues.number(value.get("repositoryId"), 1, Long.MAX_VALUE),
                GithubValues.number(value.get("installationId"), 1, Long.MAX_VALUE),
                GithubValues.string(value.get("reviewerLogin"), 100),
                GithubValues.string(value.get("credentialBindingId"), 256),
                GithubValues.string(value.get("credentialReference"), 256),
                GithubValues.string(value.get("webhookSecretReference"), 256),
                GithubValues.string(value.get("route"), 160), Map.copyOf(events),
                new GithubProfile.ProjectPolicy(GithubValues.string(project.get("projectId"), 128),
                        GithubValues.string(project.get("statusFieldId"), 128),
                        GithubValues.string(project.get("attemptsFieldId"), 128),
                        GithubValues.string(project.get("generationFieldId"), 128), options,
                        GithubValues.strings(project.get("allowedTransitions"), 128, 130),
                        GithubValues.string(project.get("claimTransition"), 130)),
                workflowIds(value.get("workflowIds")),
                new GithubProfile.ReleasePolicy(GithubValues.string(release.get("branch"), 160),
                        GithubValues.string(release.get("versionPath"), 256),
                        GithubValues.string(release.get("fragmentsPath"), 256),
                        GithubValues.strings(release.get("allowedKinds"), 4, 16),
                        (int) GithubValues.number(release.get("maxFiles"), 1, 1_000)),
                (int) GithubValues.number(limits.get("timeoutMs"), 1, 300_000),
                (int) GithubValues.number(limits.get("maxRequestBytes"), 1, 2 * 1024 * 1024),
                (int) GithubValues.number(limits.get("maxResponseBytes"), 1, 2 * 1024 * 1024),
                (int) GithubValues.number(limits.get("maxConcurrency"), 1, 128),
                (int) GithubValues.number(limits.get("maxPolls"), 1, 1_000),
                (int) GithubValues.number(limits.get("pollIntervalMs"), 1, 60_000));
    }

    private static GithubException invalid() { return new GithubException(GithubException.Code.CONFIGURATION); }

    private static Set<Long> workflowIds(Object raw) {
        List<Object> values = GithubValues.list(raw);
        if (values.isEmpty() || values.size() > 64) throw invalid();
        java.util.LinkedHashSet<Long> ids = new java.util.LinkedHashSet<>();
        for (Object value : values) ids.add(GithubValues.number(value, 1, Long.MAX_VALUE));
        if (ids.size() != values.size()) throw invalid();
        return Set.copyOf(ids);
    }

    public record StorePolicy(Path path, int maxOperations, int retentionHours, int leaseMs) {
        public StorePolicy { path = path.toAbsolutePath().normalize(); }
    }
}
