package ai.ravenroot.server;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.execution.NodeCommand;
import ai.ravenroot.api.persistence.*;
import ai.ravenroot.api.runner.*;
import ai.ravenroot.api.security.*;
import ai.ravenroot.core.persistence.*;
import ai.ravenroot.core.runner.*;
import ai.ravenroot.core.runtime.*;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.security.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LocalRunnerSupervisorTest {
    @TempDir Path directory;
    @org.junit.jupiter.api.BeforeEach void canonicalTemporaryDirectory() throws Exception { directory = directory.toRealPath(); }
    private final Clock clock = Clock.systemUTC();
    private final RunnerPolicy policy = new RunnerPolicy(Set.of(RunnerPolicy.Capability.WORKSPACE_READ), Set.of(), Set.of(), Set.of(), Set.of(),
            new RunnerPolicy.Limits(Duration.ofMinutes(5), 64_000_000, 1, 1_000_000, 4096, 1024, 4096));
    private final RunnerRegistration registration = new RunnerRegistration(1, "local", "local-worker", "local-container-v1", Set.of("development"), policy);
    private final AgentDefinition definition = new AgentDefinition(new AgentDefinition.Reference("local", "reader", 1), "Read only", "agent", "governed-model",
            Map.of("read", new AgentCommand("read", true, policy, Set.of("answered"))), Set.of(), Set.of(), policy, Duration.ofHours(1), "object");
    private final WorkspaceProfile profile = new WorkspaceProfile(new AgentDefinition.Reference("local", "development", 1),
            WorkspaceProfile.Scope.PROCESS_INSTANCE, WorkspaceProfile.RuntimeLifecycle.PER_WORKSPACE, "development", "agent", policy,
            new WorkspaceProfile.Capacity(1, 7, 8, 8_000_000, 64, 1024, WorkspaceProfile.Admission.QUEUE),
            Duration.ofHours(1), WorkspaceProfile.CompletionPolicy.ABORT, Set.of("reader"));

    private Map<String, Object> document() {
        return RunnerJson.read(RunnerJson.write(Map.of("tenantId", "local", "registration", RunnerJson.registration(registration), "docker", "/usr/bin/docker",
                "stateDirectory", directory.resolve("worker").toString(), "runtimeImages", Map.of("agent", "sha256:" + "0".repeat(64)),
                "agentRuntime", Map.of(), "worker", Map.of("pollInterval", "PT1H"))));
    }
    private RunnerPlaneConfiguration plane() {
        return new RunnerPlaneConfiguration("urn:ravenroot:local-worker", directory.resolve("artifacts"), Map.of("local", policy),
                List.of(definition), List.of(registration), List.of(profile));
    }
    private RequestContext workload() {
        return new RequestContext("test", registration.runnerId(), PrincipalType.WORKLOAD, plane().issuer(), "local", Set.of(Role.OPERATOR),
                Set.of(AuthorizationAction.RUNNER_DISPATCH.requiredScope()));
    }

    @Test void exactLoopbackOnlyAndNeverProductionOidcOrTokenAuthentication() throws Exception {
        for (String address : List.of("127.0.0.1", "127.0.0.2", "::1", "0.0.0.0", "192.0.2.2")) {
            var authentication = new AuthenticationConfiguration(new InetSocketAddress(InetAddress.getByName(address), 8080), new DisabledLoopbackAuthenticator(), "disabled");
            if (address.equals("127.0.0.1")) LocalRunnerSupervisor.validateExposure(Map.of(), authentication, false);
            else assertThrows(IllegalArgumentException.class, () -> LocalRunnerSupervisor.validateExposure(Map.of(), authentication, false));
        }
        var wildcard = new AuthenticationConfiguration(new InetSocketAddress("0.0.0.0", 8080), new DisabledLoopbackAuthenticator(), "disabled");
        var guarded = Map.of("RAVENROOT_CONTAINER_LOOPBACK_ONLY", "true", "RAVENROOT_LOCAL_HOST_BIND_ADDRESS", "127.0.0.1");
        LocalRunnerSupervisor.validateExposure(guarded, wildcard, true);
        assertThrows(IllegalArgumentException.class, () -> LocalRunnerSupervisor.validateExposure(guarded, wildcard, false));
        assertThrows(IllegalArgumentException.class, () -> LocalRunnerSupervisor.validateExposure(Map.of("RAVENROOT_CONTAINER_LOOPBACK_ONLY", "true",
                "RAVENROOT_LOCAL_HOST_BIND_ADDRESS", "0.0.0.0"), wildcard, true));
        for (String mode : List.of("oidc", "local-token")) assertThrows(IllegalArgumentException.class, () -> LocalRunnerSupervisor.validateExposure(Map.of(),
                new AuthenticationConfiguration(new InetSocketAddress("127.0.0.1", 8080), new DisabledLoopbackAuthenticator(), mode), false));
    }

    @Test void fixedInternalWorkloadWorksWhileAllPublicHeadersRemainUserAndConfigCannotSmuggleIdentity() throws Exception {
        try (var store = new InMemoryExecutionStore(clock); var engine = new PekkoExecutionEngine("local-principals")) {
            var jobs = new RunnerJobService(store, clock, List.of(definition), List.of(registration), Map.of("local", policy), List.of(profile));
            var control = new AuthorizedRunnerControl(jobs, new DefaultAuthorizationService(ignored -> { }), plane().issuer(), new RunnerArtifactStore(directory.resolve("artifacts")), clock);
            var client = new LocalRunnerClient(control, workload(), RunnerWorkerConfiguration.defaults());
            client.register(registration); client.availability(7, 0, Set.of("agent"), Duration.ofSeconds(30));
            assertEquals(1, store.runnerAvailability("local").toCompletableFuture().join().size());
            for (String field : List.of("endpoint", "tokenFile", "issuer", "principalType")) {
                var invalid = new LinkedHashMap<>(document()); invalid.put(field, "forbidden");
                assertThrows(IllegalArgumentException.class, () -> new LocalRunnerSupervisor(jobs, control, plane(), invalid));
            }
            var wrongTenant = new LinkedHashMap<>(document()); wrongTenant.put("tenantId", "other");
            assertThrows(IllegalArgumentException.class, () -> new LocalRunnerSupervisor(jobs, control, plane(), wrongTenant));
            assertThrows(IllegalArgumentException.class, () -> new LocalRunnerClient(control,
                    new RequestContext("user", "local-worker", PrincipalType.USER, plane().issuer(), "local", Set.of(Role.PLATFORM_ADMIN), Set.of()), RunnerWorkerConfiguration.defaults()));
            try (var server = new RavenrootServer(new DefaultRavenrootApplication(engine, new ExecutionMonitor()),
                    new InetSocketAddress("127.0.0.1", 0), directory, new DisabledLoopbackAuthenticator())) {
                server.installRunnerPlane(control, null); server.start();
                var http = HttpClient.newHttpClient();
                var base = URI.create("http://127.0.0.1:" + server.port() + "/v1/runner-plane/");
                assertEquals(200, http.send(HttpRequest.newBuilder(base.resolve("catalog")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                for (String token : List.of("", "Bearer invented", "Bearer local-worker")) {
                    var request = HttpRequest.newBuilder(base.resolve("register")).header("Authorization", token)
                            .header("X-Principal-Type", "WORKLOAD").header("X-Runner-Id", "local-worker").header("X-Forwarded-For", "127.0.0.1")
                            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(RunnerJson.write(RunnerJson.registration(registration)))).build();
                    assertEquals(403, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
                }
                assertEquals(403, http.send(HttpRequest.newBuilder(base.resolve("assignments")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            }
        }
    }

    @Test void immediateRestartWaitsForThePriorStoreLeaseWithoutTakingOverALiveSession() throws Exception {
        try (var store = new ai.ravenroot.persistence.sqlite.SqliteExecutionStore(directory.resolve("restart.db"), clock)) {
            var jobs = new RunnerJobService(store, clock, List.of(definition), List.of(registration), Map.of("local", policy), List.of(profile));
            var control = new AuthorizedRunnerControl(jobs, new DefaultAuthorizationService(ignored -> { }), plane().issuer(), new RunnerArtifactStore(directory.resolve("restart-artifacts")), clock);
            var configured = new LinkedHashMap<>(document());
            configured.put("worker", Map.of("pollInterval", "PT0.01S", "heartbeatInterval", "PT0.02S", "availabilityTtl", "PT0.5S"));
            java.util.function.Supplier<RunnerDriver> idleDriver = () -> new RunnerDriver() {
                public RunnerRegistration registration() { return registration; }
                public Set<String> runtimeProfiles() { return Set.of("agent"); }
                public CompletionStage<RunnerResult> execute(RunnerAssignment assignment) { throw new AssertionError("empty catalog must not dispatch"); }
                public CompletionStage<RunnerResult> reconcile(RunnerAssignment assignment) { throw new AssertionError("empty catalog must not reconcile"); }
                public CompletionStage<Void> cancel(RunnerAssignment assignment) { throw new AssertionError("empty catalog has no active jobs"); }
                public void close() { }
            };
            try (var first = new LocalRunnerSupervisor(jobs, control, plane(), configured)) { first.start(idleDriver.get()); }
            var previous = store.runnerAvailability("local").toCompletableFuture().join().getFirst();
            assertTrue(previous.live(clock.instant()), "the restart regression requires a still-live persisted incarnation");
            try (var replacement = new LocalRunnerSupervisor(jobs, control, plane(), configured)) {
                replacement.start(idleDriver.get());
                var accepted = store.runnerAvailability("local").toCompletableFuture().join().getFirst();
                assertNotEquals(previous.sessionId(), accepted.sessionId());
                assertFalse(accepted.observedAt().isBefore(previous.leaseUntil()), "no live-session takeover is permitted");
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"idle", "opening", "failed-quiescence"})
    void shutdownStopsRetainedAndOpeningWorkspacesAndNeverInventsQuiescence(String mode) throws Exception {
        try (var store = new ai.ravenroot.persistence.sqlite.SqliteExecutionStore(directory.resolve("store.db"), clock)) {
            var jobs = new RunnerJobService(store, clock, List.of(definition), List.of(registration), Map.of("local", policy), List.of(profile));
            var control = new AuthorizedRunnerControl(jobs, new DefaultAuthorizationService(ignored -> { }), plane().issuer(), new RunnerArtifactStore(directory.resolve("artifacts")), clock);
            var client = new LocalRunnerClient(control, workload(), RunnerWorkerConfiguration.defaults());
            var key = new ExecutionKey("local", UUID.randomUUID());
            var identity = new RunnerJobIdentity(key, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            var opening = new AgentDefinition(new AgentDefinition.Reference("local", "workspace-lifecycle", 1), "Open", "agent", "none",
                    Map.of("open", new AgentCommand("open", false, policy, Set.of("ready"))), Set.of(), Set.of(), policy, Duration.ofHours(1), "object");
            var invocation = new NodeInvocation(identity.invocationId(), "agent", Set.of(), NodeInvocationStatus.WAITING,
                    List.of(new NodeAttempt(identity.attemptId(), 1, NodeAttemptStatus.WAITING)), NodeCommand.application("open"));
            var process = new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.WAITING, Map.of(identity.traversalId(),
                    new Traversal(identity.traversalId(), "agent", TraversalStatus.WAITING, Map.of(identity.invocationId(), invocation))));
            var resource = new WorkspaceResource("repository", UUID.randomUUID(), profile, registration.runnerId(), WorkspaceResource.State.UNMATERIALIZED,
                    null, null, false, clock.instant());
            var submit = new RunnerJobOperation.Submit(identity, opening, "open", policy, registration, OpaquePayload.of("{}".getBytes(), "application/json"),
                    clock.instant().plusSeconds(300), resource.workspaceId(), OpaquePayload.empty("application/vnd.ravenroot.runner-continuation.v1"), resource, "open");
            store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent()).apply(new ExecutionTransition.ProcessCreated(process, new GraphVersionPin("local-test")))
                    .runner(submit).build()).toCompletableFuture().join();
            client.availability(7, 0, Set.of("agent"), Duration.ofSeconds(1));
            var claimed = client.claim(key.processInstanceId(), identity.runnerJobId(), false);
            if (!mode.equals("opening")) client.complete(claimed, new RunnerResult("ready", OpaquePayload.of("{}".getBytes(), "application/json"), List.of(), UUID.randomUUID(),
                    new RunnerResult.WorkspaceObservation(resource.workspaceId(), "retained-container", null)));
            var stops = new AtomicInteger(); var closes = new AtomicInteger();
            var driver = new RunnerDriver() {
                public RunnerRegistration registration() { return registration; }
                public CompletionStage<RunnerResult> execute(RunnerAssignment assignment) { throw new AssertionError("idle resource must not execute"); }
                public CompletionStage<RunnerResult> reconcile(RunnerAssignment assignment) { throw new AssertionError("completed job must not rerun"); }
                public CompletionStage<Void> cancel(RunnerAssignment assignment) { throw new AssertionError("idle runtime is not an active job"); }
                public CompletionStage<Void> stopWorkspace(RunnerAssignment assignment) {
                    assertTrue(store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow().workspaces().get("repository").stopRequested());
                    assertEquals(mode.equals("opening") ? null : "retained-container", assignment.workspace().runtimeId()); stops.incrementAndGet();
                    return mode.equals("failed-quiescence") ? CompletableFuture.failedFuture(new IllegalStateException("unconfirmed physical stop"))
                            : CompletableFuture.completedFuture(null);
                }
                public void close() { assertEquals(1, stops.get()); closes.incrementAndGet(); }
            };
            var supervisor = new LocalRunnerSupervisor(jobs, control, plane(), document());
            var advertisedCapacity = new AtomicInteger();
            jobs.telemetry().install(new RunnerTelemetry() {
                public void increment(Counter counter) { }
                public void activeJobs(int active) { }
                public void workerCapacity(int capacity, int available) { advertisedCapacity.set(capacity); }
            });
            supervisor.start(driver);
            assertEquals(RunnerWorkerConfiguration.defaults().maxConcurrentJobs(), advertisedCapacity.get());
            if (mode.equals("failed-quiescence")) assertThrows(IllegalStateException.class, supervisor::close);
            else supervisor.close();
            supervisor.close();
            assertEquals(1, stops.get()); assertEquals(1, closes.get());
            var aborted = store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow().workspaces().get("repository");
            assertTrue(aborted.stopRequested());
            assertEquals(mode.equals("failed-quiescence") ? WorkspaceResource.State.ABORTING : WorkspaceResource.State.ABORTED, aborted.state());
            assertEquals(mode.equals("opening") ? RunnerJob.State.CANCELLING : RunnerJob.State.COMPLETED,
                    store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow().jobs().get(identity.runnerJobId()).job().state());
        }
        try (var reopened = new ai.ravenroot.persistence.sqlite.SqliteExecutionStore(directory.resolve("store.db"), clock)) {
            var page = reopened.listProcessInstances("local", ProcessInventoryQuery.everything(10)).toCompletableFuture().join();
            assertEquals(mode.equals("failed-quiescence") ? WorkspaceResource.State.ABORTING : WorkspaceResource.State.ABORTED,
                    reopened.loadRunnerWorkspace(page.items().getFirst().key()).toCompletableFuture().join().orElseThrow()
                    .workspaces().get("repository").state());
        }
    }
}
