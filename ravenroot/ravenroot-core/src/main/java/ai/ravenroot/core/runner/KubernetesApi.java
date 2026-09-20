package ai.ravenroot.core.runner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Bounded Kubernetes transport. The operator pins both kubectl and its dedicated ServiceAccount
 * configuration; no ambient context, graph option, shell, credential argument or Docker API is used.
 */
public final class KubernetesApi implements KubernetesTransport {
    /** Bounded diagnostic classes; none is permission to replay a failed operation. */
    enum FailureKind { QUOTA, ADMISSION, AUTHORIZATION, CONFLICT, UNAVAILABLE }
    static final class OperationFailure extends IOException {
        final FailureKind kind;
        OperationFailure(FailureKind kind) { this(kind, -1); }
        OperationFailure(FailureKind kind, int exitCode) {
            super("Kubernetes operation " + kind + " (client exit " + exitCode + "); effect remains unconfirmed"); this.kind = kind;
        }
    }
    static FailureKind classify(byte[] stderr) {
        String text = new String(stderr, StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);
        if (text.contains("exceeded quota")) return FailureKind.QUOTA;
        if (text.contains("admission webhook") || text.contains("validatingadmissionpolicy") || text.contains("violates podsecurity")) return FailureKind.ADMISSION;
        if (text.contains("(forbidden)") || text.contains("(unauthorized)")) return FailureKind.AUTHORIZATION;
        if (text.contains("(conflict)") || text.contains("(alreadyexists)") || text.contains("test failed")) return FailureKind.CONFLICT;
        return FailureKind.UNAVAILABLE;
    }
    private final Path executable;
    private final Path kubeconfig;
    private final String namespace;
    private final Duration timeout;
    private final int bytes;

    /** Operator-owned transport configuration, with an explicit namespace and response ceiling. */
    public KubernetesApi(Path executable, Path kubeconfig, String namespace, Duration timeout, int bytes) throws IOException {
        this.executable = executable.toRealPath();
        this.kubeconfig = kubeconfig.toRealPath();
        this.namespace = dnsLabel(namespace);
        this.timeout = Objects.requireNonNull(timeout);
        this.bytes = bytes;
        if (!java.nio.file.Files.isExecutable(this.executable) || timeout.isNegative() || timeout.isZero() || bytes < 1)
            throw new IllegalArgumentException("invalid Kubernetes transport configuration");
        try { verifyConfiguration(); }
        catch (Exception invalid) { throw new IOException("a single TLS-verified dedicated ServiceAccount kubeconfig is required"); }
    }

    private void verifyConfiguration() throws Exception {
        var config = RunnerJson.read(command(null, "config", "view", "--minify", "--raw", "--flatten", "-o", "json"));
        for (String field : List.of("clusters", "users", "contexts"))
            if (!(config.get(field) instanceof List<?> values) || values.size() != 1)
                throw new IllegalArgumentException("exactly one Kubernetes identity is required");
        var cluster = RunnerJson.map(RunnerJson.map(((List<?>) config.get("clusters")).getFirst()).get("cluster"));
        if (!java.util.Set.of("server", "certificate-authority-data", "tls-server-name", "extensions").containsAll(cluster.keySet())
                || !"https".equals(java.net.URI.create(RunnerJson.text(cluster, "server")).getScheme())
                || !cluster.containsKey("certificate-authority-data")) throw new IllegalArgumentException("verified cluster TLS required");
        var user = RunnerJson.map(RunnerJson.map(((List<?>) config.get("users")).getFirst()).get("user"));
        if (!user.keySet().equals(java.util.Set.of("token")) && !user.keySet().equals(java.util.Set.of("tokenFile")))
            throw new IllegalArgumentException("credential plugins and non-ServiceAccount credentials are forbidden");
        String token;
        if (user.containsKey("tokenFile")) {
            try (var stream = java.nio.file.Files.newInputStream(Path.of(RunnerJson.text(user, "tokenFile")))) {
                token = new String(bounded(stream, 16_384), StandardCharsets.UTF_8).strip();
            }
        } else {
            if (java.nio.file.Files.getPosixFilePermissions(kubeconfig).stream().anyMatch(permission ->
                    permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_")))
                throw new IllegalArgumentException("inline ServiceAccount credential must be owner-only");
            token = RunnerJson.text(user, "token");
        }
        var parts = token.split("\\.");
        if (parts.length != 3 || token.length() > 16_384) throw new IllegalArgumentException("ServiceAccount JWT required");
        var claims = RunnerJson.read(java.util.Base64.getUrlDecoder().decode(parts[1]));
        if (!RunnerJson.text(claims, "sub").matches("system:serviceaccount:[a-z0-9-]{1,63}:[a-z0-9-]{1,63}"))
            throw new IllegalArgumentException("dedicated ServiceAccount subject required");
        // The API server verifies the token signature, audience and expiry on every operation.
    }

    static String dnsLabel(String value) {
        if (value == null || !value.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))
            throw new IllegalArgumentException("bounded Kubernetes DNS label required");
        return value;
    }

    private List<String> arguments(String... operation) {
        var result = new ArrayList<>(List.of(executable.toString(), "--kubeconfig=" + kubeconfig,
                "--namespace=" + namespace, "--request-timeout=" + timeout.toSeconds() + "s"));
        result.addAll(List.of(operation));
        return result;
    }

    private Process start(String... operation) throws IOException {
        var builder = new ProcessBuilder(arguments(operation));
        // kubectl must not inherit a plugin, proxy, context or credential provider chosen by an
        // interactive shell. The pinned kubeconfig is the only authentication configuration.
        builder.environment().keySet().removeIf(key -> key.startsWith("KUBECONFIG")
                || key.equals("HTTP_PROXY") || key.equals("HTTPS_PROXY") || key.equals("ALL_PROXY")
                || key.equals("http_proxy") || key.equals("https_proxy") || key.equals("all_proxy"));
        return builder.start();
    }

    private byte[] command(byte[] input, String... operation) throws Exception {
        Process process = start(operation);
        try (var streams = Executors.newVirtualThreadPerTaskExecutor()) {
            var output = streams.submit(() -> bounded(process.getInputStream(), bytes));
            // Never publish kubectl stderr: server admission responses can contain submitted data.
            var errors = streams.submit(() -> bounded(process.getErrorStream(), Math.min(bytes, 65_536)));
            try {
                try (var stdin = process.getOutputStream()) { if (input != null) stdin.write(input); }
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS))
                    throw new OperationFailure(FailureKind.UNAVAILABLE);
                byte[] result = output.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                byte[] diagnostic = errors.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (process.exitValue() != 0) throw new OperationFailure(classify(diagnostic), process.exitValue());
                return result;
            } finally {
                if (process.isAlive()) process.destroyForcibly();
                output.cancel(true); errors.cancel(true);
            }
        }
    }

    static byte[] bounded(java.io.InputStream input, int limit) throws IOException {
        byte[] value = input.readNBytes(Math.addExact(limit, 1));
        if (value.length > limit) throw new IOException("Kubernetes stream exceeds its configured bound");
        return value;
    }

    /** Creates one exact object; any failure is uncertain and never authorizes another create. */
    public Map<String, Object> create(Map<String, Object> object) throws Exception {
        return RunnerJson.read(command(RunnerJson.write(object), "create", "-f", "-", "-o", "json"));
    }

    /** Reads one exact object. Empty means an acknowledged 404; transport failure remains unknown. */
    public Map<String, Object> get(String kind, String name) throws Exception {
        if (!List.of("pod", "pvc").contains(kind)) throw new IllegalArgumentException("unsupported Kubernetes resource");
        byte[] value = command(null, "get", kind, dnsLabel(name), "--ignore-not-found=true", "-o", "json");
        return value.length == 0 ? null : RunnerJson.read(value);
    }

    /**
     * Atomically patches metadata using JSON Patch tests. A stale UID/resourceVersion cannot update
     * a successor's ownership. Callers supply only bounded operator/identity metadata.
     */
    public Map<String, Object> patch(String kind, String name, String uid, String revision,
                                      Map<String, String> annotations) throws Exception {
        if (!List.of("pod", "pvc").contains(kind)) throw new IllegalArgumentException("unsupported Kubernetes resource");
        var patch = List.of(Map.of("op", "test", "path", "/metadata/uid", "value", uid),
                Map.of("op", "test", "path", "/metadata/resourceVersion", "value", revision),
                Map.of("op", "add", "path", "/metadata/annotations", "value", annotations));
        return RunnerJson.read(command(RunnerJson.write(patch), "patch", kind, dnsLabel(name),
                "--type=json", "--patch-file=/dev/stdin", "-o", "json"));
    }

    /**
     * Deletes through UID and resourceVersion preconditions, never a name-only deletion. Completion
     * of this request is not proof of quiescence; callers must subsequently observe the UID absent.
     */
    public void delete(String kind, String name, String uid, String revision, int graceSeconds) throws Exception {
        String plural = switch (kind) { case "pod" -> "pods"; case "pvc" -> "persistentvolumeclaims";
            default -> throw new IllegalArgumentException("unsupported Kubernetes resource"); };
        if (graceSeconds < 0) throw new IllegalArgumentException("invalid termination grace");
        var options = Map.of("apiVersion", "v1", "kind", "DeleteOptions", "gracePeriodSeconds", graceSeconds,
                "preconditions", Map.of("uid", uid, "resourceVersion", revision), "propagationPolicy", "Orphan");
        command(RunnerJson.write(options), "delete", "--raw=/api/v1/namespaces/" + namespace + "/" + plural + "/" + dnsLabel(name), "-f", "-");
    }

    /**
     * Starts only the immutable runtime's bounded entry point. There is intentionally no generic
     * exec method or graph-supplied argv. Namespace admission must enforce this same fixed command.
     */
    public Process agent(String pod, int protocolBytes) throws IOException {
        if (protocolBytes < 1 || protocolBytes > 1_048_576) throw new IllegalArgumentException("invalid Agent protocol bound");
        return start("exec", "--stdin", dnsLabel(pod), "--container=agent", "--", "python3",
                "/opt/agent_runtime.py", Integer.toString(protocolBytes));
    }

    /** Runs the image's fixed enforcement probe without passing graph data or a shell program. */
    public Map<String, Object> attest(String pod) throws Exception {
        return RunnerJson.read(command(null, "exec", dnsLabel(pod), "--container=agent", "--",
                "python3", "/opt/kubernetes_attestation.py"));
    }

    /** Positive network-control probe before any Agent authority is delivered. */
    public Map<String, Object> networkControl(String pod) throws Exception {
        return RunnerJson.read(command(null, "exec", dnsLabel(pod), "--container=agent", "--",
                "python3", "/opt/kubernetes_attestation.py", "--network-control"));
    }

    /** Irreversibly removes the bootstrap-only egress label under exact UID/revision tests. */
    public Map<String, Object> isolate(String pod, String uid, String revision) throws Exception {
        var patch = List.of(Map.of("op", "test", "path", "/metadata/uid", "value", uid),
                Map.of("op", "test", "path", "/metadata/resourceVersion", "value", revision),
                Map.of("op", "remove", "path", "/metadata/labels/ravenroot.ai~1attesting"));
        return RunnerJson.read(command(RunnerJson.write(patch), "patch", "pod", dnsLabel(pod),
                "--type=json", "--patch-file=/dev/stdin", "-o", "json"));
    }

    /** Returns the pinned namespace for bounded technical observations. */
    public String namespace() { return namespace; }
}
