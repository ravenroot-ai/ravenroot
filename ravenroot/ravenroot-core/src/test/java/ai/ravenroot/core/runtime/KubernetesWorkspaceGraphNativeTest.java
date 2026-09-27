package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.persistence.*;
import ai.ravenroot.api.runner.*;
import ai.ravenroot.api.security.*;
import ai.ravenroot.core.graph.*;
import ai.ravenroot.core.runner.*;
import ai.ravenroot.persistence.sqlite.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real API + production gateway + unchanged Agent runtime, never a labelled synthetic report. */
class KubernetesWorkspaceGraphNativeTest {
    @Test void literalThreeAgentGraph(@TempDir Path directory) throws Exception {
        cycle(directory, WorkspaceProfile.RuntimeLifecycle.PER_WORKSPACE, true);
    }
    @Test void retainedPodDevelopmentAndRemediationLoop(@TempDir Path directory) throws Exception {
        cycle(directory, WorkspaceProfile.RuntimeLifecycle.PER_WORKSPACE, false);
    }
    @Test void invocationPodsKeepTheSameUncommittedFilesystem(@TempDir Path directory) throws Exception {
        cycle(directory, WorkspaceProfile.RuntimeLifecycle.PER_INVOCATION, false);
    }
    @Test void ephemeralInvocationsHaveFreshPodAndVolumeWithoutSentinelLeakage(@TempDir Path directory) throws Exception {
        cycle(directory, WorkspaceProfile.RuntimeLifecycle.PER_WORKSPACE, true, WorkspaceProfile.Scope.EPHEMERAL);
    }

    private void cycle(Path directory, WorkspaceProfile.RuntimeLifecycle lifecycle, boolean minimal) throws Exception {
        cycle(directory, lifecycle, minimal, WorkspaceProfile.Scope.PROCESS_INSTANCE);
    }
    private void cycle(Path directory, WorkspaceProfile.RuntimeLifecycle lifecycle, boolean minimal, WorkspaceProfile.Scope scope) throws Exception {
        String kubeconfig = System.getProperty("ravenroot.kubernetes.kubeconfig", "");
        org.junit.jupiter.api.Assumptions.assumeFalse(kubeconfig.isBlank(), "native Kubernetes fixture is required");
        String image = System.getProperty("ravenroot.kubernetes.image"); assertNotNull(image);
        Path model = Path.of(Objects.requireNonNull(System.getProperty("ravenroot.kubernetes.modelConfiguration")));
        var modelConfig = RunnerJson.map(RunnerJson.read(Files.readAllBytes(model)).get("agentRuntime"));
        Path samples = Path.of("../../docs/examples/governed-runner");
        var config = RunnerJson.read(Files.readAllBytes(samples.resolve("control-plane.json")));
        var tenant = RunnerJson.map(RunnerJson.map(config.get("tenants")).get("example-tenant"));
        tenant = RunnerJson.map(nativeProfile(tenant));
        var policy = RunnerJson.policy(RunnerJson.map(tenant.get("policy")));
        var definitions = ((List<?>) tenant.get("definitions")).stream()
                .map(value -> RunnerJson.definition("example-tenant", RunnerJson.map(value))).toList();
        var originalRegistration = RunnerJson.registration("example-tenant", RunnerJson.map(((List<?>) tenant.get("runners")).getFirst()));
        var registration = new RunnerRegistration(originalRegistration.protocolVersion(), originalRegistration.tenantId(),
                originalRegistration.runnerId(), "kubernetes-pod-v1", originalRegistration.labels(), originalRegistration.capabilities());
        var profiles = ((List<?>) tenant.get("workspaceProfiles")).stream()
                .map(value -> RunnerJson.workspaceProfile("example-tenant", RunnerJson.map(value)))
                .map(profile -> new WorkspaceProfile(profile.reference(), scope, lifecycle, profile.runnerPool(),
                        profile.runtimeProfile(), profile.policy(), profile.capacity(), Duration.ZERO, profile.completionPolicy(),
                        profile.allowedAgents(), profile.fleetLimits(), 500, WorkspaceProfile.Driver.KUBERNETES)).toList();
        var settings = new KubernetesRunnerConfiguration(Path.of(System.getProperty("ravenroot.kubernetes.kubectl", "/usr/local/bin/kubectl")),
                Path.of(kubeconfig), "native-test", "ravenroot-446", "ravenroot-fixed-ext4", "agent", "", Map.of(), Duration.ofMinutes(2), 1,
                System.getProperty("ravenroot.kubernetes.networkControlHost"));
        var clock = Clock.systemUTC();
        var key = new ExecutionKey("example-tenant", UUID.randomUUID()); UUID traversal = UUID.randomUUID(), session = UUID.randomUUID();
        var security = new SecurityContext("native-kubernetes", key.tenantId(), "operator", PrincipalType.USER, "test");
        var canonical = CanonicalGraphMl.of(Files.readAllBytes(samples.resolve(minimal ? "three-agents.graphml" : "development-cycle.graphml")));
        Path database = directory.resolve("native.db"), managerState = Files.createDirectory(directory.resolve("manager")).toRealPath();
        try (var store = new SqliteExecutionStore(database, clock);
             var graphs = new SqliteGraphDefinitionStore(database, clock, GraphDefinitionReferences.NONE);
             var engine = new JoinTestEngine();
             var graph = GraphManager.readGraphMl(new java.io.ByteArrayInputStream(canonical.bytes()))) {
            graphs.put(key.tenantId(), GraphDefinitionIdentity.forSubmission(canonical.contentId()), canonical).toCompletableFuture().join();
            advertise(store, registration, clock, session);
            var service = new RunnerJobService(store, clock, definitions, List.of(registration), Map.of(key.tenantId(), policy), profiles);
            long revision = store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent()).apply(
                    new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.RUNNING,
                            Map.of(traversal, new Traversal(traversal, "start", TraversalStatus.RUNNING, Map.of()))),
                            new GraphVersionPin(canonical.contentId().value()))).build()).toCompletableFuture().join().revision();
            try (var runner = new GraphRunner(graph, engine, BehaviorRegistry.standard().withRunnerJobs(service), new ExecutionMonitor());
                 var recorder = ExecutionRecorder.open(store, key, "native-start", Duration.ofSeconds(30), revision)) {
                assertInstanceOf(RunnerJobSuspension.class, assertThrows(CompletionException.class, () -> runner.execute(security,
                        key.processInstanceId(), traversal, RunnerJson.read(Files.readAllBytes(samples.resolve("input.json"))),
                        canonical.contentId().value(), null, null, recorder).toCompletableFuture().join()).getCause());
            }
        }
        var commands = minimal ? List.of("open", "implement", "read", "review", "close")
                : List.of("open", "plan", "read", "resume", "implement", "test", "remediate", "test", "review", "handoff", "close");
        var outcomes = minimal ? List.of("ready", "completed", "answered", "changes-requested", "closed")
                : List.of("ready", "answered", "answered", "completed", "completed", "failed", "fixed", "passed", "approved", "completed", "closed");
        UUID podUid = null, claimUid = null; var invocationPods = new HashSet<UUID>(); var invocationClaims = new HashSet<UUID>();
        for (int index = 0; index < commands.size(); index++) {
            try (var store = new SqliteExecutionStore(database, clock);
                 var graphs = new SqliteGraphDefinitionStore(database, clock, GraphDefinitionReferences.NONE);
                 var engine = new JoinTestEngine()) {
                advertise(store, registration, clock, session);
                var service = new RunnerJobService(store, clock, definitions, List.of(registration), Map.of(key.tenantId(), policy), profiles);
                var state = store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow();
                var entry = state.jobs().values().stream().filter(value -> value.job().retainsWorkspace()).findFirst().orElseThrow();
                var resource = state.workspaces().get("repository");
                var job = entry.job(); assertEquals(commands.get(index), job.command().name());
                var claimed = service.mutate(security, key, new RunnerJobOperation.Claim(job.identity().runnerJobId(),
                        registration.runnerId(), Duration.ofSeconds(120), session)).jobs().get(job.identity().runnerJobId()).job();
                var assignment = new RunnerAssignment(1, resource.invocationWorkspaceId(claimed.identity().runnerJobId(), entry.lifecycleCommand()), claimed, resource, entry.lifecycleCommand());
                RunnerResult result;
                try (var driver = driver(managerState, registration, settings, image, clock, modelConfig)) {
                    result = driver.execute(assignment).toCompletableFuture().get(120, TimeUnit.SECONDS);
                    assertEquals(outcomes.get(index), result.outcome());
                    assertEquals(WorkspaceProfile.Driver.KUBERNETES, driver.observe(assignment).workspace().profile().driver());
                }
                var physical = result.workspace().kubernetes();
                if (!(scope == WorkspaceProfile.Scope.EPHEMERAL && "open".equals(entry.lifecycleCommand()))) assertNotNull(physical);
                else assertNull(physical, "ephemeral opening does not allocate an unused base filesystem");
                if (scope != WorkspaceProfile.Scope.EPHEMERAL) {
                    if (claimUid == null) claimUid = physical.claimUid();
                    assertEquals(claimUid, physical.claimUid(), "PVC identity survives every Agent and manager restart");
                } else if (entry.lifecycleCommand() == null) assertTrue(invocationClaims.add(physical.claimUid()), "each invocation has a fresh PVC UID");
                if (lifecycle == WorkspaceProfile.RuntimeLifecycle.PER_WORKSPACE && scope != WorkspaceProfile.Scope.EPHEMERAL) {
                    if (podUid == null) podUid = physical.podUid();
                    assertEquals(podUid, physical.podUid(), "the actual Pod UID survives re-entry and remediation");
                } else if (entry.lifecycleCommand() == null) {
                    assertTrue(invocationPods.add(physical.podUid()), "each invocation has a fresh Pod UID");
                    assertEquals(KubernetesWorkload.Phase.ABSENT, physical.phase(), "no result before UID-absence proof");
                }
                if (commands.get(index).equals("review")) {
                    String observed = new String(result.payload().bytes(), java.nio.charset.StandardCharsets.UTF_8);
                    assertEquals(scope != WorkspaceProfile.Scope.EPHEMERAL, observed.contains("Observe failing addition test"),
                            "reviewer observes an uncommitted sentinel only when the declared filesystem is shared");
                }
                try (var restarted = driver(managerState, registration, settings, image, clock, modelConfig)) {
                    assertEquals(result, restarted.reconcile(assignment).toCompletableFuture().get(30, TimeUnit.SECONDS));
                    assertThrows(Exception.class, () -> restarted.execute(assignment).toCompletableFuture().get(10, TimeUnit.SECONDS));
                }
                var heartbeat = service.mutate(security, key, new RunnerJobOperation.Heartbeat(job.identity().runnerJobId(),
                        registration.runnerId(), claimed.fence(), Duration.ofSeconds(120), session, physical));
                assertEquals(physical, heartbeat.jobs().get(job.identity().runnerJobId()).kubernetes());
                service.mutate(security, key, new RunnerJobOperation.Complete(job.identity().runnerJobId(), registration.runnerId(), claimed.fence(), result));
                try (var continuation = new PinnedRunnerContinuationExecutor(service, graphs, engine,
                        BehaviorRegistry.standard().withRunnerJobs(service), new ExecutionMonitor(), GraphExecutionLimits.DEFAULTS, null)) {
                    continuation.resume(key, job.identity().runnerJobId()).toCompletableFuture().get(10, TimeUnit.SECONDS);
                    long revision = store.load(key).toCompletableFuture().join().revision();
                    continuation.resume(key, job.identity().runnerJobId()).toCompletableFuture().join();
                    assertEquals(revision, store.load(key).toCompletableFuture().join().revision());
                }
                if (index == commands.size() - 1) {
                    assertEquals(ProcessInstanceStatus.COMPLETED, store.load(key).toCompletableFuture().join().state().status());
                    assertEquals(KubernetesWorkload.Phase.ABSENT, physical.phase());
                    try (var driver = driver(managerState, registration, settings, image, clock, modelConfig)) {
                        driver.release(new RunnerWorkspaceRelease(1, key, resource.workspaceId(), registration.runnerId(),
                                store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow().jobs().keySet(), clock.instant(), scope, resource.generation(), true))
                                .toCompletableFuture().get(60, TimeUnit.SECONDS);
                    }
                }
            }
        }
    }
    private static Object nativeProfile(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, raw) -> result.put((String) key, key.equals("processes") ? 256L : nativeProfile(raw)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(KubernetesWorkspaceGraphNativeTest::nativeProfile).toList();
        return value;
    }
    private static void advertise(ExecutionStore store, RunnerRegistration registration, Clock clock, UUID session) {
        store.renewRunnerAvailability(new RunnerAvailability(registration.tenantId(), registration.runnerId(), session,
                8, 0, Set.of("agent"), clock.instant(), clock.instant().plusSeconds(120)), Duration.ofSeconds(120)).toCompletableFuture().join();
    }
    private static KubernetesPodRunner driver(Path state, RunnerRegistration registration, KubernetesRunnerConfiguration settings,
                                               String image, Clock clock, Map<String, Object> model) throws Exception {
        var artifacts = new RunnerArtifactStore(state.resolve("artifacts"));
        return new KubernetesPodRunner(registration, settings, state, Map.of("agent", image), clock,
                (assignment, kind, bytes) -> artifacts.put(assignment.job(), kind, new java.io.ByteArrayInputStream(bytes)),
                RunnerWorkerConfiguration.defaults()).withAgentRuntime(RunnerAgentRuntime.fromConfiguration(model,
                        new SecretProvider() {
                            public String id() { return "hermetic-no-secrets"; }
                            public Optional<SecretValue> get(String reference) {
                                throw new AssertionError("the hermetic fixture must never request a credential");
                            }
                        }));
    }
}
