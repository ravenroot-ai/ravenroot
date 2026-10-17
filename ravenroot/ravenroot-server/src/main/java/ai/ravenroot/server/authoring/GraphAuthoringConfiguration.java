package ai.ravenroot.server.authoring;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Typed operator-owned selection of local or Git graph authoring. */
public record GraphAuthoringConfiguration(
        Mode mode, Provider provider, String repositoryOwner, String repositoryName,
        String graphDirectory, String draftBranch, String releaseBranch, URI apiBase,
        CredentialMode credentialMode, String credentialReference, String appId,
        String installationId, Map<String, String> tenantNamespaces, URI artifactBase,
        String artifactCatalogPath, int maxDocumentBytes, int pageSize, Duration requestTimeout,
        int retryLimit) {

    public enum Mode { LOCAL, GIT }
    public enum Provider { NONE, GITHUB }
    public enum CredentialMode { NONE, PAT, APP }

    private static final Pattern OWNER_REPOSITORY = Pattern.compile("[A-Za-z0-9_.-]{1,100}");
    private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,199}");
    private static final Pattern CONFINED = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,511}");

    public GraphAuthoringConfiguration {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(provider, "provider");
        repositoryOwner = text(repositoryOwner);
        repositoryName = text(repositoryName);
        graphDirectory = text(graphDirectory);
        draftBranch = text(draftBranch);
        releaseBranch = text(releaseBranch);
        Objects.requireNonNull(apiBase, "apiBase");
        Objects.requireNonNull(credentialMode, "credentialMode");
        credentialReference = text(credentialReference);
        appId = text(appId);
        installationId = text(installationId);
        tenantNamespaces = Map.copyOf(Objects.requireNonNull(tenantNamespaces, "tenantNamespaces"));
        Objects.requireNonNull(artifactBase, "artifactBase");
        artifactCatalogPath = text(artifactCatalogPath);
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (maxDocumentBytes < 1 || maxDocumentBytes > 256 * 1024 * 1024) {
            throw new IllegalArgumentException("graph authoring document limit is outside the supported range");
        }
        if (pageSize < 1 || pageSize > 200) throw new IllegalArgumentException("pageSize must be 1..200");
        if (requestTimeout.isNegative() || requestTimeout.isZero() || requestTimeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("request timeout must be positive and at most two minutes");
        }
        if (retryLimit < 0 || retryLimit > 3) throw new IllegalArgumentException("retryLimit must be 0..3");
        if (mode == Mode.LOCAL) {
            if (provider != Provider.NONE || credentialMode != CredentialMode.NONE) {
                throw new IllegalArgumentException("local authoring cannot configure a Git provider or credential");
            }
        } else {
            if (maxDocumentBytes > 100 * 1024 * 1024) {
                throw new IllegalArgumentException("GitHub graph authoring document limit cannot exceed the Git blob limit");
            }
            if (provider != Provider.GITHUB) throw new IllegalArgumentException("Git authoring currently supports github only");
            requireMatch(repositoryOwner, OWNER_REPOSITORY, "repository owner");
            requireMatch(repositoryName, OWNER_REPOSITORY, "repository name");
            requireConfined(graphDirectory, "graph directory");
            requireMatch(draftBranch, BRANCH, "draft branch");
            requireMatch(releaseBranch, BRANCH, "release branch");
            requireHttps(apiBase, "GitHub API base");
            if (draftBranch.equals(releaseBranch)) throw new IllegalArgumentException("draft and release branches must differ");
            if (credentialMode == CredentialMode.NONE || credentialReference.isBlank()) {
                throw new IllegalArgumentException("Git authoring requires a credential reference");
            }
            if (credentialMode == CredentialMode.APP && (appId.isBlank() || installationId.isBlank())) {
                throw new IllegalArgumentException("GitHub App authoring requires app and installation ids");
            }
            if (credentialMode == CredentialMode.APP
                    && (!appId.matches("[1-9][0-9]{0,19}") || !installationId.matches("[1-9][0-9]{0,19}"))) {
                throw new IllegalArgumentException("GitHub App and installation ids must be positive decimal identifiers");
            }
            if (tenantNamespaces.isEmpty()) {
                throw new IllegalArgumentException("Git authoring requires explicit tenant namespace mappings");
            }
            tenantNamespaces.forEach((tenant, namespace) -> {
                if (tenant.isBlank()) throw new IllegalArgumentException("tenant namespace key cannot be blank");
                requireConfined(namespace, "tenant namespace");
            });
            var branchNamespaces = new java.util.HashSet<String>();
            tenantNamespaces.values().forEach(namespace -> {
                if (!branchNamespaces.add(namespace.replace('/', '-'))) {
                    throw new IllegalArgumentException("tenant namespaces must map to distinct draft branches");
                }
            });
            requireHttps(artifactBase, "artifact base");
            if (!artifactBase.getPath().endsWith("/")) {
                throw new IllegalArgumentException("artifact base must end with /");
            }
            requireConfined(artifactCatalogPath, "artifact catalog path");
        }
    }

    public static GraphAuthoringConfiguration local() {
        return new GraphAuthoringConfiguration(Mode.LOCAL, Provider.NONE, "", "", "", "", "",
                URI.create("https://api.github.com"), CredentialMode.NONE, "", "", "", Map.of(),
                URI.create("https://invalid.local"), "catalog.json", 10 * 1024 * 1024, 50,
                Duration.ofSeconds(15), 1);
    }

    public static GraphAuthoringConfiguration fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        Mode mode = enumValue(environment, "RAVENROOT_GRAPH_AUTHORING_MODE", Mode.class, Mode.LOCAL);
        if (mode == Mode.LOCAL) return local();
        return new GraphAuthoringConfiguration(mode,
                enumValue(environment, "RAVENROOT_GRAPH_AUTHORING_PROVIDER", Provider.class, Provider.GITHUB),
                required(environment, "RAVENROOT_GRAPH_AUTHORING_REPOSITORY_OWNER"),
                required(environment, "RAVENROOT_GRAPH_AUTHORING_REPOSITORY_NAME"),
                environment.getOrDefault("RAVENROOT_GRAPH_AUTHORING_GRAPH_DIRECTORY", "graphs"),
                environment.getOrDefault("RAVENROOT_GRAPH_AUTHORING_DRAFT_BRANCH", "draft"),
                environment.getOrDefault("RAVENROOT_GRAPH_AUTHORING_RELEASE_BRANCH", "main"),
                URI.create(environment.getOrDefault("RAVENROOT_GRAPH_AUTHORING_GITHUB_API_BASE", "https://api.github.com")),
                enumValue(environment, "RAVENROOT_GRAPH_AUTHORING_CREDENTIAL_MODE", CredentialMode.class, null),
                required(environment, "RAVENROOT_GRAPH_AUTHORING_CREDENTIAL_REFERENCE"),
                environment.getOrDefault("RAVENROOT_GRAPH_AUTHORING_GITHUB_APP_ID", ""),
                environment.getOrDefault("RAVENROOT_GRAPH_AUTHORING_GITHUB_INSTALLATION_ID", ""),
                tenantNamespaces(required(environment, "RAVENROOT_GRAPH_AUTHORING_TENANT_NAMESPACES")),
                URI.create(required(environment, "RAVENROOT_GRAPH_ARTIFACT_BASE_URL")),
                environment.getOrDefault("RAVENROOT_GRAPH_ARTIFACT_CATALOG_PATH", "catalog.json"),
                integer(environment, "RAVENROOT_GRAPH_AUTHORING_MAX_DOCUMENT_BYTES", 10 * 1024 * 1024),
                integer(environment, "RAVENROOT_GRAPH_AUTHORING_PAGE_SIZE", 50),
                Duration.ofMillis(integer(environment, "RAVENROOT_GRAPH_AUTHORING_REQUEST_TIMEOUT_MILLIS", 15_000)),
                integer(environment, "RAVENROOT_GRAPH_AUTHORING_RETRY_LIMIT", 1));
    }

    public String tenantDirectory(String tenantId) {
        String namespace = tenantNamespaces.get(tenantId);
        if (namespace == null) throw new IllegalArgumentException("tenant has no configured graph namespace");
        return graphDirectory + "/" + namespace;
    }

    public String capabilitiesJson() {
        if (mode == Mode.LOCAL) return "{\"mode\":\"local\",\"provider\":null,\"operations\":[\"open-local\",\"save-local\"]}";
        return "{\"mode\":\"git\",\"provider\":\"github\",\"operations\":[\"list\",\"open\",\"create\",\"save\",\"history\",\"diff\",\"restore\",\"delete\",\"discard\",\"release-proposal\",\"artifact-import\",\"artifact-deploy\"]}";
    }

    public Map<String, Object> capabilities() {
        return Map.of("mode", mode.name().toLowerCase(Locale.ROOT),
                "provider", provider == Provider.NONE ? "none" : provider.name().toLowerCase(Locale.ROOT),
                "repositoryDisplay", mode == Mode.GIT ? repositoryOwner + "/" + repositoryName : "",
                "releaseBranch", mode == Mode.GIT ? releaseBranch : "",
                "maxDocumentBytes", maxDocumentBytes);
    }

    private static Map<String, String> tenantNamespaces(String encoded) {
        var result = new LinkedHashMap<String, String>();
        for (String entry : encoded.split(",")) {
            int separator = entry.indexOf('=');
            if (separator < 1 || separator == entry.length() - 1) {
                throw new IllegalArgumentException("tenant namespaces must be tenant=directory pairs");
            }
            String tenant = entry.substring(0, separator).strip();
            String directory = entry.substring(separator + 1).strip();
            if (result.putIfAbsent(tenant, directory) != null) throw new IllegalArgumentException("duplicate tenant namespace");
        }
        return result;
    }

    private static <E extends Enum<E>> E enumValue(Map<String, String> environment, String key,
                                                    Class<E> type, E fallback) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) {
            if (fallback == null) throw new IllegalArgumentException(key + " is required");
            return fallback;
        }
        try { return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException(key + " has an unsupported value"); }
    }

    private static int integer(Map<String, String> environment, String key, int fallback) {
        String raw = environment.get(key);
        try { return raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw.strip()); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException(key + " must be an integer"); }
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value.strip();
    }

    private static String text(String value) { return value == null ? "" : value.strip(); }
    private static void requireMatch(String value, Pattern pattern, String name) {
        if (!pattern.matcher(value).matches()) throw new IllegalArgumentException(name + " is invalid");
    }
    private static void requireConfined(String value, String name) {
        requireMatch(value, CONFINED, name);
        if (value.startsWith("/") || value.endsWith("/") || value.contains("//")
                || java.util.Arrays.asList(value.split("/")).contains("..")) {
            throw new IllegalArgumentException(name + " is not confined");
        }
    }
    private static void requireHttps(URI value, String name) {
        if (!"https".equalsIgnoreCase(value.getScheme()) || value.getHost() == null
                || value.getRawUserInfo() != null || value.getRawQuery() != null || value.getRawFragment() != null) {
            throw new IllegalArgumentException(name + " must be an absolute HTTPS origin/path without credentials or query");
        }
    }
}
