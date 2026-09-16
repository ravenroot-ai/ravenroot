package ai.ravenroot.server;

import ai.ravenroot.api.persistence.ProcessInventoryQuery;
import ai.ravenroot.api.runner.*;
import ai.ravenroot.api.security.*;
import ai.ravenroot.core.runner.*;
import ai.ravenroot.server.security.AuthenticationConfiguration;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Trusted-local composition: a private Java capability, never an HTTP credential or listener. */
final class LocalRunnerSupervisor implements AutoCloseable {
    private final RunnerJobService jobs;
    private final AuthorizedRunnerControl control;
    private final LocalRunnerClient client;
    private final RequestContext operator;
    private final RequestContext workload;
    private final RunnerRegistration registration;
    private final RunnerWorkerConfiguration configuration;
    private final Map<String, Object> document;
    private RunnerDriver driver;
    private RunnerWorker worker;
    private boolean closed;

    static void validateExposure(Map<String, String> environment, AuthenticationConfiguration authentication, boolean containerized) {
        if (!"disabled".equals(authentication.mode())) throw new IllegalArgumentException("local runner requires disabled trusted-local authentication");
        String address = authentication.bindAddress().getAddress().getHostAddress();
        if ("127.0.0.1".equals(address)) return;
        if (containerized && "0.0.0.0".equals(address)
                && "true".equals(environment.get("RAVENROOT_CONTAINER_LOOPBACK_ONLY"))
                && "127.0.0.1".equals(environment.get("RAVENROOT_LOCAL_HOST_BIND_ADDRESS"))) return;
        throw new IllegalArgumentException("local runner requires exact 127.0.0.1 host exposure");
    }

    static LocalRunnerSupervisor fromEnvironment(Map<String, String> environment, AuthenticationConfiguration authentication,
            RunnerPlaneConfiguration plane, RunnerJobService jobs, AuthorizedRunnerControl control) {
        String path = environment.get("RAVENROOT_LOCAL_RUNNER_CONFIG");
        if (path == null || path.isBlank()) return null;
        validateExposure(environment, authentication, Files.exists(Path.of("/.dockerenv")));
        if (plane == null || jobs == null || control == null) throw new IllegalArgumentException("local runner requires a durable governed control plane");
        try (var input = Files.newInputStream(Path.of(path).toRealPath())) {
            return new LocalRunnerSupervisor(jobs, control, plane,
                    RunnerJson.read(input.readNBytes(Math.addExact(RunnerJson.LIMITS.maxEncodedBytes(), 1))));
        } catch (java.io.IOException failure) { throw new IllegalArgumentException("local runner configuration unavailable", failure); }
    }

    LocalRunnerSupervisor(RunnerJobService jobs, AuthorizedRunnerControl control, RunnerPlaneConfiguration plane, Map<String, Object> document) {
        this.jobs = Objects.requireNonNull(jobs); this.control = Objects.requireNonNull(control);
        var keys = new HashSet<>(document.keySet()); keys.remove("worker");
        if (!keys.equals(Set.of("tenantId", "registration", "docker", "stateDirectory", "runtimeImages", "agentRuntime")))
            throw new IllegalArgumentException("local runner config has no endpoint, token or identity override");
        if (!"local".equals(RunnerJson.text(document, "tenantId")) || !plane.policies().keySet().equals(Set.of("local")))
            throw new IllegalArgumentException("trusted-local runner and catalog must use only the local UI tenant");
        this.registration = RunnerJson.registration("local", RunnerJson.map(document.get("registration")));
        if (!plane.runners().contains(registration)) throw new IllegalArgumentException("local worker requires exact operator-approved registration");
        this.configuration = RunnerWorkerMain.configuration(document.get("worker"));
        this.document = Map.copyOf(document);
        this.workload = new RequestContext("local-supervisor", registration.runnerId(), PrincipalType.WORKLOAD,
                plane.issuer(), "local", Set.of(Role.OPERATOR), Set.of(AuthorizationAction.RUNNER_DISPATCH.requiredScope()));
        this.operator = new RequestContext("local-supervisor-stop", "anonymous-loopback", PrincipalType.USER,
                "urn:ravenroot:disabled-loopback", "local", Set.of(Role.OPERATOR), Set.of(AuthorizationAction.RUNNER_CONTROL.requiredScope()));
        this.client = new LocalRunnerClient(control, workload, configuration);
    }

    void start() {
        try { startContainer(); }
        catch (Exception failure) { throw new IllegalStateException("local runner startup refused", failure); }
    }
    private void startContainer() throws Exception {
        var images = new LinkedHashMap<String, String>();
        RunnerJson.map(document.get("runtimeImages")).forEach((key, value) -> {
            if (!(value instanceof String image)) throw new IllegalArgumentException("local runtime image digest required");
            images.put(key, image);
        });
        var container = new LocalContainerRunner(registration, Path.of(RunnerJson.text(document, "docker")),
                Path.of(RunnerJson.text(document, "stateDirectory")), images, Clock.systemUTC(), client::upload, configuration);
        driver = container;
        try {
            if (registration.capabilities().capabilities().contains(RunnerPolicy.Capability.WORKSPACE_WRITE))
                for (String image : images.values()) container.verifyWorkspaceQuota(image);
            container.withAgentRuntime(RunnerWorkerMain.agentRuntime(RunnerJson.map(document.get("agentRuntime"))));
            startWorker();
        } catch (Exception failure) {
            container.close(); driver = null;
            throw new IllegalStateException("local runner startup refused", failure);
        }
    }

    // Same supervisor lifecycle with a bounded protocol driver in tests; no alternate identity path.
    void start(RunnerDriver driver) throws Exception { this.driver = driver; startWorker(); }
    private void startWorker() throws Exception {
        worker = new RunnerWorker(client, driver, configuration);
        worker.telemetry().install(jobs.telemetry());
        worker.start();
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (worker == null) { if (driver != null) driver.close(); return; }
        worker.stopAdmissions();
        RuntimeException unconfirmed = null;
        try {
            String cursor = null;
            do {
                var page = jobs.store().listProcessInstances("local", ProcessInventoryQuery.everything(
                        Math.min(jobs.store().maxInventoryPageSize(), jobs.controlConfiguration().recoveryPageSize())).after(cursor)).toCompletableFuture().join();
                cursor = page.nextCursor().orElse(null);
                for (var process : page.items()) {
                    var state = jobs.store().loadRunnerWorkspace(process.key()).toCompletableFuture().join().orElse(null);
                    if (state == null) continue;
                    for (var resource : state.workspaces().values()) {
                        if (!resource.runnerId().equals(registration.runnerId()) || resource.terminal()) continue;
                        try {
                            retryRevision(() -> control.stopWorkspace(operator, process.key().processInstanceId(), resource.nodeId(),
                                    control.workerRevision(workload, process.key().processInstanceId())));
                            var job = state.jobs().values().stream().filter(value -> resource.nodeId().equals(value.workspaceNodeId())).findFirst().orElseThrow();
                            var assignment = client.assignment(process.key().processInstanceId(), job.job().identity().runnerJobId());
                            driver.stopWorkspace(assignment).toCompletableFuture().get(configuration.cleanupTimeout().toMillis(), TimeUnit.MILLISECONDS);
                            retryRevision(() -> { client.workspaceStopped(assignment); return 0L; });
                        } catch (Exception failure) {
                            if (unconfirmed == null) unconfirmed = new IllegalStateException("local Workspace shutdown not confirmed; durable stop/recovery required");
                            unconfirmed.addSuppressed(failure);
                        }
                    }
                }
            } while (cursor != null);
        } finally { worker.close(); }
        if (unconfirmed != null) throw unconfirmed;
    }

    private void retryRevision(java.util.concurrent.Callable<Long> operation) throws Exception {
        long deadline = System.nanoTime() + configuration.cleanupTimeout().toNanos();
        for (;;) {
            try { operation.call(); return; }
            catch (RuntimeException failure) {
                var storeFailure = ai.ravenroot.api.persistence.ExecutionStoreException.unwrap(failure);
                if (storeFailure == null || !(storeFailure.failure() instanceof ai.ravenroot.api.persistence.ExecutionStoreFailure.ConcurrencyConflict)
                        || System.nanoTime() >= deadline) throw failure;
                TimeUnit.NANOSECONDS.sleep(Math.min(configuration.pollInterval().toNanos(), Math.max(1, deadline - System.nanoTime())));
            }
        }
    }

}
