package ai.ravenroot.core.runner;

import ai.ravenroot.api.persistence.*;
import ai.ravenroot.api.runner.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real Kubernetes acceptance; the full-tier fixture requires execution and rejects skipped reports. */
class KubernetesPodRunnerNativeTest {
    @Test void materializesAttestedPodAndPvcWithFencedRestartAndCleanup(@TempDir Path directory) throws Exception {
        String config = System.getProperty("ravenroot.kubernetes.kubeconfig", "");
        org.junit.jupiter.api.Assumptions.assumeFalse(config.isBlank(), "native Kubernetes fixture is required");
        String image = System.getProperty("ravenroot.kubernetes.image"); assertNotNull(image);
        var clock = Clock.systemUTC();
        var policy = new RunnerPolicy(Set.of(RunnerPolicy.Capability.WORKSPACE_READ, RunnerPolicy.Capability.WORKSPACE_WRITE,
                RunnerPolicy.Capability.PROCESS_EXECUTE), Set.of(), Set.of(), Set.of(), Set.of(),
                new RunnerPolicy.Limits(Duration.ofMinutes(5), 134_217_728, 256, 67_108_864, 1_048_576, 65_536, 65_536));
        var registration = new RunnerRegistration(1, "native-tenant", "native-manager", "kubernetes-pod-v1", Set.of("native"), policy);
        var profile = new WorkspaceProfile(new AgentDefinition.Reference("native-tenant", "native", 1),
                WorkspaceProfile.Scope.PROCESS_INSTANCE, WorkspaceProfile.RuntimeLifecycle.PER_WORKSPACE, "native", "native", policy,
                new WorkspaceProfile.Capacity(1, 1, 4, 268_435_456, 16, 32, WorkspaceProfile.Admission.QUEUE), Duration.ZERO,
                WorkspaceProfile.CompletionPolicy.REQUIRE_CLOSED, Set.of("agent"), RunnerFleetLimits.from(
                        new WorkspaceProfile.Capacity(1, 1, 4, 268_435_456, 16, 32, WorkspaceProfile.Admission.QUEUE)),
                500, WorkspaceProfile.Driver.KUBERNETES);
        var commands = Map.of("open", new AgentCommand("open", false, policy, Set.of("ready")),
                "close", new AgentCommand("close", false, policy, Set.of("closed")));
        var definition = new AgentDefinition(new AgentDefinition.Reference("native-tenant", "workspace", 1), "Open a native Workspace",
                "native", "none", commands, Set.of(), Set.of("native"), policy, Duration.ZERO, "workspace-state");
        var execution = new ExecutionKey("native-tenant", UUID.randomUUID());
        var resource = new WorkspaceResource("repository", UUID.randomUUID(), profile, registration.runnerId(),
                WorkspaceResource.State.OPENING, null, null, false, clock.instant());
        var settings = new KubernetesRunnerConfiguration(Path.of(System.getProperty("ravenroot.kubernetes.kubectl", "/opt/homebrew/bin/kubectl")),
                Path.of(config), "native-test", "ravenroot-446", "ravenroot-fixed-ext4", "agent", "", Map.of(), Duration.ofMinutes(2), 1,
                System.getProperty("ravenroot.kubernetes.networkControlHost"));
        var opening = assignment(execution, resource, registration, definition, "open", clock);
        RunnerResult ready;
        try (var driver = driver(directory, registration, settings, image, clock)) {
            ready = driver.execute(opening).toCompletableFuture().get(150, TimeUnit.SECONDS);
            assertEquals("ready", ready.outcome()); assertNotNull(ready.workspace().kubernetes());
            assertEquals(KubernetesWorkload.Phase.RUNNING, ready.workspace().kubernetes().phase());
            assertTrue(ready.workspace().kubernetes().enforcedBytes() <= policy.limits().workspaceBytes());
            assertEquals(ready.workspace().runtimeId(), ready.workspace().kubernetes().podUid().toString());
            // Actual admission and RBAC, not manifest-text assertions: a manager cannot gain a
            // shell channel, read Secrets or manage cluster Nodes with this workload identity.
            for (var denied : List.of(List.of("exec", ready.workspace().kubernetes().podName(), "-c", "agent", "--", "sh", "-c", "true"),
                    List.of("get", "secrets"), List.of("get", "nodes"))) {
                var argv = new ArrayList<>(List.of(settings.kubectl().toString(), "--kubeconfig=" + settings.kubeconfig(), "--namespace=" + settings.namespace()));
                argv.addAll(denied);
                var refused = new ProcessBuilder(argv).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                assertTrue(refused.waitFor(15, TimeUnit.SECONDS)); assertNotEquals(0, refused.exitValue(), "native authority must be refused");
            }
            assertThrows(Exception.class, () -> driver.execute(opening).toCompletableFuture().get(10, TimeUnit.SECONDS));
        }
        resource = resource.observed("open", ready.workspace().runtimeId(), null, clock.instant(), ready.workspace().kubernetes());
        var closing = assignment(execution, resource, registration, definition, "close", clock);
        try (var restarted = driver(directory, registration, settings, image, clock)) {
            assertEquals(ready, restarted.reconcile(opening).toCompletableFuture().get(15, TimeUnit.SECONDS));
            var closed = restarted.execute(closing).toCompletableFuture().get(60, TimeUnit.SECONDS);
            assertEquals("closed", closed.outcome()); assertEquals(KubernetesWorkload.Phase.ABSENT, closed.workspace().kubernetes().phase());
            assertEquals(ready.workspace().runtimeId(), closed.workspace().runtimeId());
            restarted.release(new RunnerWorkspaceRelease(1, execution, resource.workspaceId(), registration.runnerId(),
                    Set.of(opening.job().identity().runnerJobId(), closing.job().identity().runnerJobId()), clock.instant()))
                    .toCompletableFuture().get(60, TimeUnit.SECONDS);
        }

        // Independent resources in the same process: a Workspace stop cannot terminate its peer.
        var first = new WorkspaceResource("source", UUID.randomUUID(), profile, registration.runnerId(),
                WorkspaceResource.State.OPENING, null, null, false, clock.instant());
        var second = new WorkspaceResource("documentation", UUID.randomUUID(), profile, registration.runnerId(),
                WorkspaceResource.State.OPENING, null, null, false, clock.instant());
        var firstOpen = assignment(execution, first, registration, definition, "open", clock);
        var secondOpen = assignment(execution, second, registration, definition, "open", clock);
        try (var manager = driver(directory, registration, settings, image, clock)) {
            var a = manager.execute(firstOpen).toCompletableFuture().get(150, TimeUnit.SECONDS);
            var b = manager.execute(secondOpen).toCompletableFuture().get(150, TimeUnit.SECONDS);
            assertNotEquals(a.workspace().kubernetes().claimUid(), b.workspace().kubernetes().claimUid());
            manager.stopWorkspace(new RunnerAssignment(1, first.workspaceId(), firstOpen.job(), first.request("abort", true, clock.instant()), "open"))
                    .toCompletableFuture().get(60, TimeUnit.SECONDS);
            assertEquals(KubernetesWorkload.Phase.RUNNING, manager.observe(secondOpen).workspace().kubernetes().phase());
            assertThrows(Exception.class, () -> manager.execute(assignment(execution, first, registration, definition, "open", clock)).toCompletableFuture().join());
            manager.stopWorkspace(new RunnerAssignment(1, second.workspaceId(), secondOpen.job(), second.request("abort", true, clock.instant()), "open"))
                    .toCompletableFuture().get(60, TimeUnit.SECONDS);
            for (var owned : List.of(firstOpen, secondOpen)) manager.release(release(owned, clock)).toCompletableFuture().get(60, TimeUnit.SECONDS);
        }

        // NAMED reacquisition keeps the claim but changes the Pod UID and ownership generation.
        var namedProfile = new WorkspaceProfile(profile.reference(), WorkspaceProfile.Scope.NAMED, profile.runtimeLifecycle(),
                profile.runnerPool(), profile.runtimeProfile(), policy, profile.capacity(), profile.retention(), profile.completionPolicy(),
                profile.allowedAgents(), profile.fleetLimits(), profile.cpuMillicores(), WorkspaceProfile.Driver.KUBERNETES);
        var named = new WorkspaceResource("named-repository", WorkspaceResource.namedWorkspaceId("native-tenant", UUID.randomUUID().toString()),
                namedProfile, registration.runnerId(), WorkspaceResource.State.OPENING, null, null, false, clock.instant());
        var oldOpen = assignment(execution, named, registration, definition, "open", clock);
        var oldClose = assignment(execution, named, registration, definition, "close", clock);
        RunnerResult oldReady;
        try (var manager = driver(directory, registration, settings, image, clock)) {
            oldReady = manager.execute(oldOpen).toCompletableFuture().get(150, TimeUnit.SECONDS);
            manager.execute(oldClose).toCompletableFuture().get(60, TimeUnit.SECONDS);
        }
        var newNamed = new WorkspaceResource(named.nodeId(), named.workspaceId(), namedProfile, registration.runnerId(),
                WorkspaceResource.State.OPENING, null, null, false, clock.instant(), 2);
        var newOpen = assignment(execution, newNamed, registration, definition, "open", clock);
        var newClose = assignment(execution, newNamed, registration, definition, "close", clock);
        try (var manager = driver(directory, registration, settings, image, clock)) {
            var newReady = manager.execute(newOpen).toCompletableFuture().get(150, TimeUnit.SECONDS);
            assertEquals(oldReady.workspace().kubernetes().claimUid(), newReady.workspace().kubernetes().claimUid());
            assertNotEquals(oldReady.workspace().kubernetes().podUid(), newReady.workspace().kubernetes().podUid());
            assertThrows(Exception.class, () -> manager.release(new RunnerWorkspaceRelease(1, execution, named.workspaceId(),
                    registration.runnerId(), Set.of(oldOpen.job().identity().runnerJobId(), oldClose.job().identity().runnerJobId()),
                    clock.instant(), WorkspaceProfile.Scope.NAMED, 1, true)).toCompletableFuture().join());
            assertEquals(KubernetesWorkload.Phase.RUNNING, manager.observe(newOpen).workspace().kubernetes().phase());
            manager.execute(newClose).toCompletableFuture().get(60, TimeUnit.SECONDS);
            manager.release(new RunnerWorkspaceRelease(1, execution, named.workspaceId(), registration.runnerId(),
                    Set.of(newOpen.job().identity().runnerJobId(), newClose.job().identity().runnerJobId()), clock.instant(), WorkspaceProfile.Scope.NAMED, 2, true))
                    .toCompletableFuture().get(60, TimeUnit.SECONDS);
        }

        // Real API fault injection: the server creates a Pod, but the response is lost before UID
        // persistence. A replacement manager may only recover identity and stop, never execute.
        var uncertainResource = new WorkspaceResource("uncertain", UUID.randomUUID(), profile, registration.runnerId(),
                WorkspaceResource.State.OPENING, null, null, false, clock.instant());
        var uncertain = assignment(execution, uncertainResource, registration, definition, "open", clock);
        var transport = new LostAcknowledgement(new KubernetesApi(settings.kubectl(), settings.kubeconfig(), settings.namespace(), Duration.ofSeconds(30), 65536));
        try (var manager = new KubernetesPodRunner(registration, settings, directory.toRealPath(), Map.of("native", image), clock,
                (a, k, bytes) -> { throw new AssertionError("no model/artifact effect allowed"); }, RunnerWorkerConfiguration.defaults(), transport)) {
            assertThrows(Exception.class, () -> manager.execute(uncertain).toCompletableFuture().get(150, TimeUnit.SECONDS));
        }
        try (var manager = driver(directory, registration, settings, image, clock)) {
            assertThrows(Exception.class, () -> manager.execute(uncertain).toCompletableFuture().join());
            assertThrows(Exception.class, () -> manager.reconcile(uncertain).toCompletableFuture().join());
            var stopped = new RunnerAssignment(1, uncertain.workspaceId(), uncertain.job().cancel(clock.instant()), uncertainResource, "open");
            assertEquals(KubernetesWorkload.Phase.ABSENT, manager.reconcile(stopped).toCompletableFuture().get(60, TimeUnit.SECONDS).workspace().kubernetes().phase());
            manager.release(release(uncertain, clock)).toCompletableFuture().get(60, TimeUnit.SECONDS);
        }
        assertEquals(1, transport.podCreates);

        // The hard memory boundary may kill the whole cgroup. Use a sacrificial, fully owned Pod,
        // never an active Agent Workspace, and retain it until exact-UID quiescence is established.
        var memoryResource = new WorkspaceResource("memory-boundary", UUID.randomUUID(), profile, registration.runnerId(),
                WorkspaceResource.State.OPENING, null, null, false, clock.instant());
        var memoryOpen = assignment(execution, memoryResource, registration, definition, "open", clock);
        try (var manager = driver(directory, registration, settings, image, clock)) {
            var evidence = manager.execute(memoryOpen).toCompletableFuture().get(150, TimeUnit.SECONDS).workspace().kubernetes();
            var probe = new ProcessBuilder(settings.kubectl().toString(), "--kubeconfig=" + settings.kubeconfig(), "--namespace=" + settings.namespace(),
                    "exec", evidence.podName(), "-c", "agent", "--", "python3", "/opt/kubernetes_attestation.py", "--memory-boundary")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            assertTrue(probe.waitFor(30, TimeUnit.SECONDS)); assertNotEquals(0, probe.exitValue());
            long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            KubernetesWorkload observed;
            do {
                observed = manager.observe(memoryOpen).workspace().kubernetes();
                // Kubelet may publish the terminated container before updating the Pod phase.
                if (observed.reason() == KubernetesWorkload.Reason.OOM_KILLED && observed.phase() == KubernetesWorkload.Phase.FAILED) break;
                Thread.sleep(100);
            } while (System.nanoTime() < end);
            assertEquals(KubernetesWorkload.Reason.OOM_KILLED, observed.reason());
            assertEquals(KubernetesWorkload.Phase.FAILED, observed.phase());
            assertThrows(Exception.class, () -> manager.reconcile(memoryOpen).toCompletableFuture().join());
            manager.cancel(memoryOpen).toCompletableFuture().get(60, TimeUnit.SECONDS);
            manager.release(release(memoryOpen, clock)).toCompletableFuture().get(60, TimeUnit.SECONDS);
        }

        // The executable manager's readiness gate must also pass on a real cluster, including
        // restart reuse of its one quota-charged probe volume. No model gateway is involved.
        UUID probeClaim;
        try (var manager = driver(directory, registration, settings, image, clock)) {
            assertThrows(IllegalStateException.class, manager::verifyAvailability);
            manager.preflight(); manager.verifyAvailability();
            var probe = preflightAssignment(directory);
            probeClaim = manager.observe(probe).workspace().kubernetes().claimUid();
            assertEquals(KubernetesWorkload.Phase.ABSENT, manager.observe(probe).workspace().kubernetes().phase());
        }
        try (var manager = driver(directory, registration, settings, image, clock)) {
            manager.preflight(); manager.verifyAvailability();
            var probe = preflightAssignment(directory);
            assertEquals(2, probe.workspace().generation());
            assertEquals(probeClaim, manager.observe(probe).workspace().kubernetes().claimUid());
            manager.release(release(probe, clock)).toCompletableFuture().get(60, TimeUnit.SECONDS);
            assertThrows(Exception.class, manager::verifyAvailability);
        }
    }
    private static RunnerAssignment preflightAssignment(Path directory) throws Exception {
        try (var files = Files.newDirectoryStream(directory, "kubernetes-preflight-*")) {
            return RunnerCodec.assignment(Files.readAllBytes(files.iterator().next()));
        }
    }
    private static RunnerWorkspaceRelease release(RunnerAssignment assignment, Clock clock) {
        return new RunnerWorkspaceRelease(1, assignment.job().identity().execution(), assignment.workspaceId(), assignment.job().runner().runnerId(),
                Set.of(assignment.job().identity().runnerJobId()), clock.instant(), assignment.workspace().profile().workspaceScope(), assignment.workspace().generation(), true);
    }
    private static final class LostAcknowledgement implements KubernetesTransport {
        private final KubernetesTransport delegate;
        int podCreates;
        LostAcknowledgement(KubernetesTransport delegate) { this.delegate = delegate; }
        public Map<String, Object> create(Map<String, Object> value) throws Exception {
            var result = delegate.create(value);
            if (value.get("kind").equals("Pod")) { podCreates++; throw new java.io.IOException("injected lost API acknowledgement after server commit"); }
            return result;
        }
        public Map<String, Object> get(String kind, String name) throws Exception { return delegate.get(kind, name); }
        public Map<String, Object> patch(String kind, String name, String uid, String revision, Map<String, String> annotations) throws Exception { return delegate.patch(kind, name, uid, revision, annotations); }
        public void delete(String kind, String name, String uid, String revision, int grace) throws Exception { delegate.delete(kind, name, uid, revision, grace); }
        public Map<String, Object> attest(String pod) throws Exception { return delegate.attest(pod); }
        public Map<String, Object> networkControl(String pod) throws Exception { return delegate.networkControl(pod); }
        public Map<String, Object> isolate(String pod, String uid, String revision) throws Exception { return delegate.isolate(pod, uid, revision); }
        public Process agent(String pod, int bytes) { throw new AssertionError("unknown effects cannot be reexecuted"); }
    }
    private static KubernetesPodRunner driver(Path directory, RunnerRegistration registration, KubernetesRunnerConfiguration settings,
                                               String image, Clock clock) throws Exception {
        return new KubernetesPodRunner(registration, settings, directory.toRealPath(), Map.of("native", image), clock,
                (assignment, kind, bytes) -> { throw new AssertionError("lifecycle must not upload artifacts"); }, RunnerWorkerConfiguration.defaults());
    }
    private static RunnerAssignment assignment(ExecutionKey key, WorkspaceResource resource, RunnerRegistration registration,
                                                AgentDefinition definition, String command, Clock clock) {
        var identity = new RunnerJobIdentity(key, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var job = RunnerJob.accept(identity, definition, command, registration.capabilities(), registration,
                OpaquePayload.of("{}".getBytes(), "application/json"), clock.instant(), clock.instant().plusSeconds(300))
                .claim(registration.runnerId(), clock.instant(), Duration.ofSeconds(240));
        return new RunnerAssignment(1, resource.workspaceId(), job, resource, command);
    }
}
