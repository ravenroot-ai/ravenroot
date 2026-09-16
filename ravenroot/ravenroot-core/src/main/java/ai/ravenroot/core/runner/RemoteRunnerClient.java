package ai.ravenroot.core.runner;

import ai.ravenroot.api.runner.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;

/** Designated remote-runner integration for protocol v1; credentials never enter an assignment. */
public final class RemoteRunnerClient {
    private final URI endpoint;
    private final Supplier<String> token;
    private final HttpClient http;
    public RemoteRunnerClient(URI endpoint, Supplier<String> token) {
        if ((!endpoint.getScheme().equals("https") && !(endpoint.getScheme().equals("http")
                && Set.of("127.0.0.1", "[::1]", "localhost").contains(endpoint.getHost())))
                || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                || !(endpoint.getPath().isEmpty() || endpoint.getPath().equals("/"))) {
            throw new IllegalArgumentException("runner endpoint must be an HTTPS origin or explicit loopback origin");
        }
        this.endpoint = endpoint.resolve("/v1/runner-plane/"); this.token = Objects.requireNonNull(token);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public void register(RunnerRegistration registration) throws IOException, InterruptedException {
        send("register", "POST", RunnerJson.write(RunnerJson.registration(registration)), Map.of());
    }
    public Map<String, Object> assignments(String cursor) throws IOException, InterruptedException {
        String query = cursor == null ? "" : "?cursor=" + java.net.URLEncoder.encode(cursor, java.nio.charset.StandardCharsets.UTF_8);
        return RunnerJson.read(send("assignments" + query, "GET", new byte[0], Map.of()));
    }
    public RunnerAssignment assignment(UUID processId, UUID jobId) throws IOException, InterruptedException {
        return RunnerCodec.assignment(send(path(processId, jobId), "GET", new byte[0], Map.of()));
    }
    public RunnerWorkspaceRelease release(UUID process) throws IOException, InterruptedException {
        var value = RunnerJson.read(send("workspaces/" + process + "/release", "POST", new byte[0], Map.of()));
        if (!(value.get("jobIds") instanceof List<?> ids)) throw new IllegalArgumentException("invalid runner release");
        return new RunnerWorkspaceRelease(Math.toIntExact(RunnerJson.number(value, "protocolVersion")),
                new ai.ravenroot.api.persistence.ExecutionKey(RunnerJson.text(value, "tenantId"), process),
                UUID.fromString(RunnerJson.text(value, "workspaceId")), RunnerJson.text(value, "runnerId"),
                ids.stream().map(id -> UUID.fromString((String) id)).collect(java.util.stream.Collectors.toUnmodifiableSet()),
                java.time.Instant.parse(RunnerJson.text(value, "notBefore")));
    }
    public RunnerAssignment claim(UUID processId, UUID jobId, boolean reconcile) throws IOException, InterruptedException {
        return RunnerCodec.assignment(send(path(processId, jobId) + (reconcile ? "/reconcile-report" : "/claim"),
                "POST", RunnerJson.write(Map.of("ttlSeconds", 30)), Map.of()));
    }
    public RunnerAssignment heartbeat(RunnerAssignment assignment) throws IOException, InterruptedException {
        return RunnerCodec.assignment(send(path(assignment) + "/heartbeat", "POST",
                RunnerJson.write(Map.of("fence", assignment.job().fence(), "ttlSeconds", 30)), Map.of()));
    }
    public RunnerAssignment complete(RunnerAssignment assignment, RunnerResult result) throws IOException, InterruptedException {
        return RunnerCodec.assignment(send(path(assignment) + "/complete", "POST", RunnerCodec.result(result),
                Map.of("Content-Type", "application/vnd.ravenroot.runner-result.v1", "X-Runner-Fence", Long.toString(assignment.job().fence()))));
    }
    public RunnerArtifact upload(RunnerAssignment assignment, RunnerArtifact.Kind kind, byte[] bytes) throws IOException, InterruptedException {
        if (bytes.length > Math.min(1_048_576, assignment.job().authority().limits().artifactBytes())) {
            throw new IllegalArgumentException("runner artifact upload quota exceeded");
        }
        var value = RunnerJson.read(send(path(assignment) + "/artifacts", "POST", bytes,
                Map.of("Content-Type", "application/octet-stream", "X-Runner-Fence", Long.toString(assignment.job().fence()),
                        "X-Runner-Artifact-Kind", kind.name())));
        return new RunnerArtifact(assignment.job().identity(), UUID.fromString(RunnerJson.text(value, "artifactId")),
                RunnerArtifact.Kind.valueOf(RunnerJson.text(value, "kind")), RunnerJson.text(value, "sha256"),
                RunnerJson.number(value, "sizeBytes"));
    }
    private byte[] send(String path, String method, byte[] bytes, Map<String, String> headers) throws IOException, InterruptedException {
        String credential = token.get();
        if (credential == null || credential.isBlank() || credential.indexOf('\n') >= 0 || credential.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("runner workload credential is unavailable");
        }
        var request = HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + credential).header("Accept", "application/octet-stream, application/json");
        request.header("Content-Type", headers.getOrDefault("Content-Type", "application/json"));
        headers.forEach((name, value) -> { if (!name.equals("Content-Type")) request.header(name, value); });
        var response = http.send(request.method(method, HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (var input = response.body()) {
            byte[] body = input.readNBytes(1_048_577);
            if (body.length > 1_048_576) throw new IOException("runner response quota exceeded");
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("runner protocol refused with HTTP " + response.statusCode());
            }
            return body;
        }
    }
    private static String path(RunnerAssignment assignment) {
        return path(assignment.job().identity().execution().processInstanceId(), assignment.job().identity().runnerJobId());
    }
    private static String path(UUID process, UUID job) { return "workspaces/" + process + "/jobs/" + job; }
}
