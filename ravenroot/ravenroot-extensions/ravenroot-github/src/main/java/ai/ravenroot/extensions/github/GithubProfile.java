package ai.ravenroot.extensions.github;

import ai.ravenroot.api.node.service.OutboundCredentialBinding;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable tenant authority for one GitHub installation and repository. */
public record GithubProfile(
        String name, String tenantId, URI apiOrigin, String owner, String repository, long repositoryId,
        long installationId, String reviewerLogin, String credentialBindingId, String credentialReference,
        String webhookSecretReference, String route, Map<String, Set<String>> webhookEvents,
        ProjectPolicy project, Set<Long> workflowIds, ReleasePolicy release,
        int timeoutMs, int maxRequestBytes, int maxResponseBytes, int maxConcurrency,
        int maxPolls, int pollIntervalMs) {

    public GithubProfile {
        name = token(name, 64); tenantId = token(tenantId, 160);
        apiOrigin = java.util.Objects.requireNonNull(apiOrigin);
        if (!"https".equalsIgnoreCase(apiOrigin.getScheme()) || apiOrigin.getHost() == null
                || apiOrigin.getUserInfo() != null || apiOrigin.getQuery() != null || apiOrigin.getFragment() != null
                || apiOrigin.getPort() != -1 && apiOrigin.getPort() != 443
                || apiOrigin.getPath() != null && !apiOrigin.getPath().isEmpty()) throw invalid();
        owner = repositoryToken(owner); repository = repositoryToken(repository);
        if (repositoryId < 1 || installationId < 1) throw invalid();
        reviewerLogin = reviewer(reviewerLogin); credentialBindingId = token(credentialBindingId, 256);
        credentialReference = token(credentialReference, 256);
        webhookSecretReference = token(webhookSecretReference, 256);
        route = route(route);
        webhookEvents = Map.copyOf(webhookEvents);
        if (webhookEvents.isEmpty() || webhookEvents.size() > 32) throw invalid();
        webhookEvents.forEach((event, actions) -> {
            token(event, 64); if (actions.size() > 64) throw invalid(); actions.forEach(action -> token(action, 64));
        });
        project = java.util.Objects.requireNonNull(project);
        workflowIds = Set.copyOf(workflowIds);
        if (workflowIds.isEmpty() || workflowIds.size() > 64 || workflowIds.stream().anyMatch(id -> id < 1)) throw invalid();
        release = java.util.Objects.requireNonNull(release);
        positive(timeoutMs, 300_000); positive(maxRequestBytes, 2 * 1024 * 1024);
        positive(maxResponseBytes, 2 * 1024 * 1024); positive(maxConcurrency, 128);
        positive(maxPolls, 1_000); positive(pollIntervalMs, 60_000);
    }

    public OutboundCredentialBinding credential() {
        return new OutboundCredentialBinding(credentialBindingId, credentialReference);
    }

    URI rest(String path) {
        if (!path.startsWith("/") || path.contains("..") || path.contains("\r") || path.contains("\n")) throw invalid();
        return apiOrigin.resolve(path);
    }

    String repositoryPath() { return "/repos/" + owner + "/" + repository; }

    private static String token(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
                || !value.matches("[A-Za-z0-9._/-]+")) throw invalid();
        return value;
    }

    private static String route(String value) {
        if (value == null || !value.matches("/[A-Za-z0-9._/-]{1,159}") || value.contains("//")
                || value.contains("..") || value.endsWith("/")) throw invalid();
        return value;
    }

    private static String reviewer(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._\\-\\[\\]]{1,100}")) throw invalid();
        return value;
    }

    private static void positive(int value, int maximum) { if (value < 1 || value > maximum) throw invalid(); }
    private static GithubException invalid() { return new GithubException(GithubException.Code.CONFIGURATION); }

    public record ProjectPolicy(String projectId, String statusFieldId, String attemptsFieldId,
                                String generationFieldId, Map<String, String> statusOptions,
                                Set<String> allowedTransitions, String claimTransition) {
        public ProjectPolicy {
            projectId = token(projectId, 128); statusFieldId = token(statusFieldId, 128);
            attemptsFieldId = token(attemptsFieldId, 128); generationFieldId = token(generationFieldId, 128);
            statusOptions = Map.copyOf(statusOptions);
            if (statusOptions.isEmpty() || statusOptions.size() > 32) throw invalid();
            statusOptions.forEach((name, id) -> { token(name, 64); token(id, 128); });
            allowedTransitions = Set.copyOf(allowedTransitions);
            if (allowedTransitions.isEmpty() || allowedTransitions.size() > 128) throw invalid();
            allowedTransitions.forEach(value -> {
                if (!value.matches("[A-Za-z0-9._-]{1,64}->[A-Za-z0-9._-]{1,64}")) throw invalid();
            });
            if (claimTransition == null
                    || !claimTransition.matches("[A-Za-z0-9._-]{1,64}->[A-Za-z0-9._-]{1,64}")) throw invalid();
            if (!allowedTransitions.contains(claimTransition)) throw invalid();
        }
    }

    public record ReleasePolicy(String branch, String versionPath, String fragmentsPath,
                                Set<String> allowedKinds, int maxFiles) {
        public ReleasePolicy {
            branch = token(branch, 160); versionPath = relativePath(versionPath);
            fragmentsPath = relativePath(fragmentsPath); allowedKinds = Set.copyOf(allowedKinds);
            if (allowedKinds.isEmpty() || !Set.of("patch", "minor", "major", "none").containsAll(allowedKinds)) throw invalid();
            if (maxFiles < 1 || maxFiles > 1_000) throw invalid();
        }
    }

    private static String repositoryToken(String value) {
        if (value == null || !value.matches("[A-Za-z0-9](?:[A-Za-z0-9._-]{0,98}[A-Za-z0-9])?")) throw invalid();
        return value;
    }

    private static String relativePath(String value) {
        if (value == null || value.isBlank() || value.length() > 256 || value.indexOf('\\') >= 0
                || value.startsWith("/") || value.endsWith("/") || value.contains("//")) throw invalid();
        java.nio.file.Path path;
        try { path = java.nio.file.Path.of(value); }
        catch (RuntimeException invalid) { throw invalid(); }
        if (path.isAbsolute() || !path.normalize().toString().replace('\\', '/').equals(value)
                || path.startsWith("..")) throw invalid();
        return value;
    }
}
