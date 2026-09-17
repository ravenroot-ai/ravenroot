package ai.ravenroot.core.runner;

import ai.ravenroot.api.runner.RunnerAssignment;
import ai.ravenroot.api.security.SecretProvider;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Model-only egress on the trusted worker. No endpoint, credential or headers come from a graph. */
public final class RunnerModelGateway implements RunnerAgentRuntime.ModelGateway {
    public record Profile(URI endpoint, String model, String credentialReference,
                          int maxConcurrency, int maxRequestBytes, int maxResponseBytes) {
        public Profile {
            Objects.requireNonNull(endpoint); Objects.requireNonNull(model);
            if ((!"https".equals(endpoint.getScheme()) && !("http".equals(endpoint.getScheme())
                    && Set.of("127.0.0.1", "localhost", "[::1]").contains(endpoint.getHost())))
                    || endpoint.getUserInfo() != null || endpoint.getFragment() != null || endpoint.getQuery() != null
                    || model.isBlank() || maxConcurrency < 1 || maxRequestBytes < 1 || maxResponseBytes < 1
                    || (credentialReference != null && !"https".equals(endpoint.getScheme()))) {
                throw new IllegalArgumentException("invalid operator model profile");
            }
        }
    }
    private final Map<String, Profile> profiles;
    private final Map<String, Semaphore> permits;
    private final SecretProvider secrets;
    private final HttpClient client;
    private final Object dispatchGate = new Object();
    private final Set<UUID> cancelledJobs = new HashSet<>();
    private record Scope(UUID workspaceId, long generation) { }
    private final Set<Scope> cancelledWorkspaces = new HashSet<>();
    private record Pending(Scope workspace, CompletableFuture<HttpResponse<byte[]>> response) { }
    private final Map<UUID, Pending> pending = new HashMap<>();
    public RunnerModelGateway(Map<String, Profile> profiles, SecretProvider secrets, Duration connectTimeout) {
        this.profiles = Map.copyOf(profiles); this.secrets = Objects.requireNonNull(secrets);
        var values = new LinkedHashMap<String, Semaphore>();
        profiles.forEach((name, profile) -> values.put(name, new Semaphore(profile.maxConcurrency())));
        permits = Map.copyOf(values);
        client = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Override public Map<String, Object> complete(RunnerAssignment assignment, Map<String, Object> request,
                                                int outputTokens, Duration timeout) throws Exception {
        String name = assignment.job().definition().modelProfile();
        Profile profile = profiles.get(name);
        if (profile == null) throw new IllegalStateException("Agent model profile is not configured on this worker");
        Semaphore permit = permits.get(name);
        if (!permit.tryAcquire()) throw new IllegalStateException("model profile capacity reached");
        try {
            byte[] body = RunnerJson.write(Map.of("model", profile.model(), "messages", request.get("messages"),
                    "tools", request.get("tools"), "max_tokens", outputTokens));
            if (body.length > profile.maxRequestBytes()) throw new IllegalArgumentException("model request capacity exceeded");
            var builder = HttpRequest.newBuilder(profile.endpoint()).timeout(timeout).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (profile.credentialReference() != null) {
                try (var secret = secrets.get(profile.credentialReference()).orElseThrow(() -> new IllegalStateException("model credential unavailable"))) {
                    char[] copy = secret.copy();
                    try { builder.header("Authorization", "Bearer " + new String(copy)); }
                    finally { Arrays.fill(copy, '\0'); }
                }
            }
            UUID jobId = assignment.job().identity().runnerJobId();
            Scope workspace = scope(assignment);
            CompletableFuture<HttpResponse<byte[]>> dispatched;
            synchronized (dispatchGate) {
                if (cancelledJobs.contains(jobId) || cancelledWorkspaces.contains(workspace))
                    throw new CancellationException("model dispatch is cancelled");
                if (pending.containsKey(jobId)) throw new IllegalStateException("one model turn per Agent invocation");
                dispatched = client.sendAsync(builder.build(), ai.ravenroot.core.security.egress.BoundedBodyHandlers.ofByteArray(profile.maxResponseBytes()));
                pending.put(jobId, new Pending(workspace, dispatched));
            }
            HttpResponse<byte[]> response;
            try { response = RunnerHttp.await(dispatched, timeout); }
            finally { synchronized (dispatchGate) { pending.remove(jobId); } }
            {
                byte[] bytes = response.body();
                if (response.statusCode() != 200) throw new IllegalStateException("model endpoint refused the bounded request");
                var value = RunnerJson.read(bytes);
                if (!(value.get("choices") instanceof List<?> choices) || choices.size() != 1) throw new IllegalArgumentException("one model choice required");
                var message = RunnerJson.map(RunnerJson.map(choices.getFirst()).get("message"));
                if (!"assistant".equals(message.get("role"))) throw new IllegalArgumentException("model assistant message required");
                long tokens = RunnerJson.number(RunnerJson.map(value.get("usage")), "total_tokens");
                if (tokens < 1) throw new IllegalArgumentException("model token accounting is required");
                return Map.of("type", "model-result", "message", message, "tokens", tokens);
            }
        } finally { permit.release(); }
    }
    @Override public void cancel(RunnerAssignment assignment) {
        synchronized (dispatchGate) {
            UUID job = assignment.job().identity().runnerJobId(); cancelledJobs.add(job);
            var active = pending.get(job); if (active != null) active.response().cancel(true);
        }
    }
    private static Scope scope(RunnerAssignment assignment) {
        return new Scope(assignment.workspace() == null ? assignment.workspaceId() : assignment.workspace().workspaceId(),
                assignment.workspace() == null ? 1 : assignment.workspace().generation());
    }
    @Override public void cancelWorkspace(RunnerAssignment assignment) {
        synchronized (dispatchGate) {
            Scope workspace = scope(assignment); cancelledWorkspaces.add(workspace);
            pending.values().stream().filter(value -> value.workspace().equals(workspace)).forEach(value -> value.response().cancel(true));
        }
    }
    @Override public void released(ai.ravenroot.api.runner.RunnerWorkspaceRelease release) {
        synchronized (dispatchGate) {
            if (release.jobIds().stream().anyMatch(pending::containsKey)) throw new IllegalStateException("model transport has not quiesced");
            cancelledJobs.removeAll(release.jobIds());
            cancelledWorkspaces.remove(new Scope(release.workspaceId(), release.generation()));
        }
    }
}
