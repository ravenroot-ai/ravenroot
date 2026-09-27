package ai.ravenroot.core.runner;

import ai.ravenroot.api.persistence.*;
import ai.ravenroot.api.runner.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class KubernetesPodRunnerTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final RunnerPolicy POLICY = new RunnerPolicy(Set.of(RunnerPolicy.Capability.WORKSPACE_READ,
            RunnerPolicy.Capability.WORKSPACE_WRITE, RunnerPolicy.Capability.PROCESS_EXECUTE), Set.of(), Set.of(), Set.of(), Set.of(),
            new RunnerPolicy.Limits(Duration.ofMinutes(5), 134_217_728, 256, 67_108_864, 1_048_576, 65_536, 65_536));
    private static final RunnerRegistration REGISTRATION = new RunnerRegistration(1, "tenant", "manager", "kubernetes-pod-v1", Set.of("native"), POLICY);
    private static final WorkspaceProfile.Capacity CAPACITY = new WorkspaceProfile.Capacity(1, 1, 4, 268_435_456, 16, 32, WorkspaceProfile.Admission.QUEUE);
    private static final AgentDefinition DEFINITION = new AgentDefinition(new AgentDefinition.Reference("tenant", "workspace", 1),
            "Governed lifecycle", "native", "none", Map.of("open", new AgentCommand("open", false, POLICY, Set.of("ready")),
            "close", new AgentCommand("close", false, POLICY, Set.of("closed"))), Set.of(), Set.of(), POLICY, Duration.ZERO, "workspace-state");
    @TempDir Path state;

    @Test void apiDiagnosticsAreClosedClassesWithoutRawAdmissionContent() {
        for (var entry : Map.of("exceeded quota: secret", KubernetesApi.FailureKind.QUOTA,
                "ValidatingAdmissionPolicy rejected secret", KubernetesApi.FailureKind.ADMISSION,
                "Error from server (Forbidden): secret", KubernetesApi.FailureKind.AUTHORIZATION,
                "Error from server (Conflict): secret", KubernetesApi.FailureKind.CONFLICT,
                "arbitrary secret", KubernetesApi.FailureKind.UNAVAILABLE).entrySet()) {
            assertEquals(entry.getValue(), KubernetesApi.classify(entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertFalse(new KubernetesApi.OperationFailure(entry.getValue()).getMessage().contains("secret"));
        }
    }

    @Test void preflightReusesItsChargedVolumeAcrossOwnershipGenerations() throws Exception {
        var api = new Fake();
        String claim;
        try (var driver = driver(api, NOW)) {
            assertThrows(IllegalStateException.class, driver::verifyAvailability);
            driver.preflight(); driver.verifyAvailability();
            claim = RunnerJson.text(RunnerJson.map(api.objects.values().iterator().next().get("metadata")), "uid");
        }
        try (var driver = driver(api, NOW.plusSeconds(1))) { driver.preflight(); driver.verifyAvailability(); }
        assertEquals(1, api.objects.size());
        assertEquals(claim, RunnerJson.map(api.objects.values().iterator().next().get("metadata")).get("uid"));
        assertEquals(3, api.creates); assertEquals(2, api.deletes);
    }

    @Test void failedAvailabilityClosesAdmissionEvenWhenConnectivityReturns() throws Exception {
        var api = new Fake();
        try (var driver = driver(api, NOW)) {
            driver.preflight(); driver.verifyAvailability();
            api.fault = "get";
            assertThrows(Exception.class, driver::verifyAvailability);
            api.fault = "";
            assertThrows(Exception.class, driver::verifyAvailability);
            assertThrows(Exception.class, () -> driver.execute(assignment(resource(), "open")).toCompletableFuture().join());
        }
        assertEquals(2, api.creates, "a recovered API alone does not reopen admission");
    }

    @ParameterizedTest @ValueSource(strings = {"before-pvc", "after-pvc", "before-pod", "after-pod", "attestation", "pending"})
    void uncertainMaterializationIsNeverRedispatched(String fault) throws Exception {
        var api = new Fake(); api.fault = fault; var assignment = assignment(resource(), "open");
        try (var driver = driver(api, NOW)) {
            assertThrows(Exception.class, () -> driver.execute(assignment).toCompletableFuture().get(3, TimeUnit.SECONDS));
        }
        int creates = api.creates; api.fault = "";
        try (var restarted = driver(api, NOW)) {
            assertThrows(Exception.class, () -> restarted.reconcile(assignment).toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertThrows(Exception.class, () -> restarted.execute(assignment).toCompletableFuture().get(3, TimeUnit.SECONDS));
        }
        assertEquals(creates, api.creates); assertEquals(0, api.agentCalls);
    }

    @Test void uncertainCreateRebuildsUidForCancellationWithoutReexecution() throws Exception {
        var api = new Fake(); api.fault = "after-pod"; var assignment = assignment(resource(), "open");
        try (var driver = driver(api, NOW)) { assertThrows(Exception.class, () -> driver.execute(assignment).toCompletableFuture().join()); }
        api.fault = "";
        var stopped = new RunnerAssignment(1, assignment.workspaceId(), assignment.job().cancel(NOW), assignment.workspace(), "open");
        try (var driver = driver(api, NOW)) {
            var report = driver.reconcile(stopped).toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(KubernetesWorkload.Phase.ABSENT, report.workspace().kubernetes().phase());
            assertEquals(report, driver.reconcile(stopped).toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertTrue(api.objects.keySet().stream().noneMatch(value -> value.startsWith("pod/")));
            assertTrue(api.objects.keySet().stream().anyMatch(value -> value.startsWith("pvc/")));
        }
        assertEquals(2, api.creates); assertEquals(0, api.agentCalls);
    }

    @ParameterizedTest @ValueSource(strings = {"pod", "pvc"})
    void replacementPhysicalUidCannotBeReportedOrDeleted(String kind) throws Exception {
        var api = new Fake(); var assignment = assignment(resource(), "open");
        try (var driver = driver(api, NOW)) {
            driver.execute(assignment).toCompletableFuture().get(3, TimeUnit.SECONDS);
            var value = api.objects.entrySet().stream().filter(entry -> entry.getKey().startsWith(kind + "/")).findFirst().orElseThrow().getValue();
            RunnerJson.map(value.get("metadata")).put("uid", UUID.randomUUID().toString());
            assertThrows(Exception.class, () -> driver.reconcile(assignment).toCompletableFuture().join());
            if (kind.equals("pod")) assertThrows(Exception.class, () -> driver.cancel(assignment).toCompletableFuture().join());
        }
        assertEquals(0, api.deletes);
    }

    @Test void lostPodDoesNotRecreateAnAttestedWorkspace() throws Exception {
        var api = new Fake(); var assignment = assignment(resource(), "open");
        try (var driver = driver(api, NOW)) {
            driver.execute(assignment).toCompletableFuture().get(3, TimeUnit.SECONDS);
            api.objects.keySet().removeIf(name -> name.startsWith("pod/"));
            var second = assignment(assignment.workspace(), "open");
            assertThrows(Exception.class, () -> driver.execute(second).toCompletableFuture().join());
        }
        assertEquals(2, api.creates);
    }

    @Test void lostVolumeDoesNotCreateAnEmptyReplacement() throws Exception {
        var api = new Fake(); var assignment = assignment(resource(), "open");
        try (var driver = driver(api, NOW)) {
            driver.execute(assignment).toCompletableFuture().join();
            api.objects.keySet().removeIf(name -> name.startsWith("pvc/"));
            assertThrows(Exception.class, () -> driver.execute(assignment(assignment.workspace(), "open")).toCompletableFuture().join());
        }
        assertEquals(2, api.creates);
    }

    @Test void anUnmaterializedInvocationDoesNotReportItsPredecessorsPhysicalIdentity() throws Exception {
        var api = new Fake(); var assignment = assignment(resource(), "open");
        try (var driver = driver(api, NOW)) {
            var result = driver.execute(assignment).toCompletableFuture().join();
            var previous = assignment.workspace().observed("open", result.workspace().runtimeId(), null, NOW, result.workspace().kubernetes());
            var next = assignment(previous, "open");
            assertNotNull(previous.kubernetes());
            assertNull(driver.observe(next).workspace().kubernetes());
            assertNotNull(previous.kubernetes(), "heartbeat projection must not mutate durable ownership");
        }
    }

    @Test void staleFenceCannotReportOrCancelAfterReportOnlyClaim() throws Exception {
        var api = new Fake(); var assignment = assignment(resource(), "open");
        try (var driver = driver(api, NOW)) { driver.execute(assignment).toCompletableFuture().get(3, TimeUnit.SECONDS); }
        Instant later = NOW.plusSeconds(31);
        var reconciledJob = assignment.job().reconcileLiveness(later).beginReconciliation("manager", later, Duration.ofSeconds(30));
        var recovery = new RunnerAssignment(1, assignment.workspaceId(), reconciledJob, assignment.workspace(), "open");
        var revoked = new java.util.concurrent.atomic.AtomicInteger();
        var runtime = new RunnerAgentRuntime(1, 1, 1, 1, 4096, 4096, 1, List.of(), Map.of(), new RunnerAgentRuntime.ModelGateway() {
            public Map<String, Object> complete(RunnerAssignment a, Map<String, Object> request, int tokens, Duration timeout) {
                throw new AssertionError("report-only recovery cannot request a model turn");
            }
            public void cancel(RunnerAssignment a) { revoked.incrementAndGet(); }
        });
        try (var driver = driver(api, later).withAgentRuntime(runtime)) {
            driver.reconcile(recovery).toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(1, revoked.get(), "new report-only fence revokes the gateway before accepting old evidence");
            assertThrows(Exception.class, () -> driver.reconcile(assignment).toCompletableFuture().join());
            assertThrows(Exception.class, () -> driver.cancel(assignment).toCompletableFuture().join());
            assertEquals(0, api.deletes);
            driver.cancel(recovery).toCompletableFuture().get(3, TimeUnit.SECONDS);
        }
        assertEquals(1, api.deletes);
    }

    @Test void apiDeleteUncertaintyRetainsVolumeAndRequiresAbsenceProof() throws Exception {
        var api = new Fake(); var opening = assignment(resource(), "open");
        try (var driver = driver(api, NOW)) {
            driver.execute(opening).toCompletableFuture().get(3, TimeUnit.SECONDS); api.fault = "delete";
            assertThrows(Exception.class, () -> driver.release(new RunnerWorkspaceRelease(1, opening.job().identity().execution(),
                    opening.workspaceId(), "manager", Set.of(opening.job().identity().runnerJobId()), NOW)).toCompletableFuture().join());
            assertTrue(api.objects.keySet().stream().anyMatch(name -> name.startsWith("pvc/")));
        }
    }

    @Test void imageAndPodAuthorityArePinnedAndConcurrentManagersAreExcluded() throws Exception {
        var api = new Fake(); var assignment = assignment(resource(), "open");
        try (var driver = driver(api, NOW)) {
            assertThrows(RuntimeException.class, () -> driver(api, NOW));
            driver.execute(assignment).toCompletableFuture().get(3, TimeUnit.SECONDS);
            var pod = api.objects.entrySet().stream().filter(entry -> entry.getKey().startsWith("pod/")).findFirst().orElseThrow().getValue();
            var container = RunnerJson.map(((List<?>) RunnerJson.map(pod.get("spec")).get("containers")).getFirst());
            container.put("image", "foreign@sha256:" + "b".repeat(64));
            assertThrows(Exception.class, () -> driver.execute(assignment(assignment.workspace(), "open")).toCompletableFuture().join());
        }
        assertEquals(2, api.creates);
    }

    @ParameterizedTest @ValueSource(strings = {"Evicted", "NodeLost", "Error", "Completed", "OOMKilled"})
    void exitedOrLostRetainedPodsRequireRecoveryEvenWithRecoverableStorage(String reason) throws Exception {
        var api = new Fake(); var assignment = assignment(resource(), "open");
        try (var driver = driver(api, NOW)) {
            driver.execute(assignment).toCompletableFuture().get(3, TimeUnit.SECONDS);
            var pod = api.objects.entrySet().stream().filter(e -> e.getKey().startsWith("pod/")).findFirst().orElseThrow().getValue();
            pod.put("status", Map.of("phase", "Failed", "reason", reason));
            assertEquals(KubernetesWorkload.Phase.FAILED, driver.observe(assignment).workspace().kubernetes().phase());
            assertThrows(Exception.class, () -> driver.reconcile(assignment).toCompletableFuture().join());
            assertThrows(Exception.class, () -> driver.execute(assignment(assignment.workspace(), "open")).toCompletableFuture().join());
        }
        assertEquals(2, api.creates); assertEquals(0, api.agentCalls); assertEquals(0, api.deletes);
    }

    @ParameterizedTest @ValueSource(strings = {"Pending", "Lost"})
    void aVisiblePodDoesNotMakeAnUnavailableVolumeSafe(String phase) throws Exception {
        var api = new Fake(); var assignment = assignment(resource(), "open");
        try (var driver = driver(api, NOW)) {
            driver.execute(assignment).toCompletableFuture().join();
            api.objects.entrySet().stream().filter(e -> e.getKey().startsWith("pvc/")).findFirst().orElseThrow().getValue()
                    .put("status", Map.of("phase", phase));
            assertThrows(Exception.class, () -> driver.observe(assignment));
            assertThrows(Exception.class, () -> driver.reconcile(assignment).toCompletableFuture().join());
        }
        assertEquals(0, api.deletes);
    }

    @Test void observationDisconnectNeverBecomesAbsenceOrReplayPermission() throws Exception {
        var api = new Fake(); var assignment = assignment(resource(), "open");
        try (var driver = driver(api, NOW)) {
            var sealed = driver.execute(assignment).toCompletableFuture().join();
            api.fault = "get";
            assertThrows(Exception.class, () -> driver.observe(assignment));
            assertThrows(Exception.class, () -> driver.reconcile(assignment).toCompletableFuture().join());
            assertThrows(Exception.class, () -> driver.cancel(assignment).toCompletableFuture().join());
            api.fault = "";
            assertEquals(sealed, driver.reconcile(assignment).toCompletableFuture().join());
        }
        assertEquals(2, api.creates); assertEquals(0, api.agentCalls); assertEquals(0, api.deletes);
    }

    private WorkspaceResource resource() {
        var profile = new WorkspaceProfile(new AgentDefinition.Reference("tenant", "native", 1), WorkspaceProfile.Scope.PROCESS_INSTANCE,
                WorkspaceProfile.RuntimeLifecycle.PER_WORKSPACE, "native", "native", POLICY, CAPACITY, Duration.ZERO,
                WorkspaceProfile.CompletionPolicy.REQUIRE_CLOSED, Set.of("agent"), RunnerFleetLimits.from(CAPACITY), 500, WorkspaceProfile.Driver.KUBERNETES);
        return new WorkspaceResource("repository", UUID.randomUUID(), profile, "manager", WorkspaceResource.State.OPENING, null, null, false, NOW);
    }
    private RunnerAssignment assignment(WorkspaceResource resource, String operation) {
        var key = new ExecutionKey("tenant", UUID.nameUUIDFromBytes(resource.workspaceId().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var id = new RunnerJobIdentity(key, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var job = RunnerJob.accept(id, DEFINITION, operation, POLICY, REGISTRATION, OpaquePayload.of("{}".getBytes(), "application/json"),
                NOW, NOW.plusSeconds(300)).claim("manager", NOW, Duration.ofSeconds(30));
        return new RunnerAssignment(1, resource.workspaceId(), job, resource, operation);
    }
    private KubernetesPodRunner driver(Fake api, Instant now) throws Exception {
        return new KubernetesPodRunner(REGISTRATION, new KubernetesRunnerConfiguration(Path.of("unused"), Path.of("unused"),
                "cluster", "native", "fixed-ext4", "agent", "", Map.of(), Duration.ofMillis(10), 1), state.toRealPath(),
                Map.of("native", "registry.example.test/agent@sha256:" + "a".repeat(64)), Clock.fixed(now, ZoneOffset.UTC),
                (assignment, kind, bytes) -> { throw new AssertionError("no model work expected"); },
                new RunnerWorkerConfiguration(2, Duration.ofMillis(10), Duration.ofMillis(10), Duration.ofMillis(20), Duration.ofMillis(20)), api);
    }
    private static final class Fake implements KubernetesTransport {
        final Map<String, Map<String, Object>> objects = new LinkedHashMap<>();
        String fault = ""; int creates, deletes, agentCalls;
        public Map<String, Object> create(Map<String, Object> raw) throws Exception {
            String kind = raw.get("kind").equals("Pod") ? "pod" : "pvc"; creates++;
            if (fault.equals("before-" + kind)) throw new java.io.IOException("uncertain create");
            var value = RunnerJson.read(RunnerJson.write(raw)); var metadata = RunnerJson.map(value.get("metadata"));
            metadata.put("uid", UUID.randomUUID().toString()); metadata.put("resourceVersion", "1");
            value.put("status", Map.of("phase", kind.equals("pod") ? fault.equals("pending") ? "Pending" : "Running" : "Bound"));
            if (kind.equals("pvc")) RunnerJson.map(value.get("spec")).put("volumeName", "fixed-volume");
            assertNull(objects.putIfAbsent(kind + "/" + metadata.get("name"), value), "duplicate create is never allowed");
            if (fault.equals("after-" + kind)) throw new java.io.IOException("acknowledgement lost");
            return value;
        }
        public Map<String, Object> get(String kind, String name) {
            if (fault.equals("get")) throw new IllegalStateException("API observation disconnected");
            return objects.get(kind + "/" + name);
        }
        public Map<String, Object> patch(String kind, String name, String uid, String revision, Map<String, String> annotations) {
            var value = get(kind, name); check(value, uid, revision); RunnerJson.map(value.get("metadata")).put("annotations", annotations); return value;
        }
        public void delete(String kind, String name, String uid, String revision, int grace) throws Exception {
            check(get(kind, name), uid, revision);
            if (fault.equals("delete")) throw new java.io.IOException("unknown delete");
            if (kind.equals("pvc")) assertTrue(objects.keySet().stream().noneMatch(key -> key.startsWith("pod/")));
            objects.remove(kind + "/" + name); deletes++;
        }
        private void check(Map<String, Object> value, String uid, String revision) {
            assertEquals(uid, RunnerJson.map(value.get("metadata")).get("uid"));
            assertEquals(revision, RunnerJson.map(value.get("metadata")).get("resourceVersion"));
        }
        public Map<String, Object> attest(String pod) {
            var proof = new LinkedHashMap<String, Object>();
            for (String key : Set.of("withinQuotaWrite", "beyondQuotaRejected", "networkBlocked", "nonRoot", "capabilitiesDropped",
                    "noNewPrivileges", "readOnlyRoot", "serviceAccountAbsent", "withinMemoryAllocation", "processBoundaryRejected", "cpuThrottlingObserved")) proof.put(key, true);
            proof.putAll(Map.of("backend", "fixed-filesystem-v1", "protocolVersion", 1L, "landlockAbi", 3L, "processLimit", 256L,
                    "requestedBytes", 67_108_864L, "enforcedBytes", 60_000_000L, "memoryLimitBytes", 134_217_728L, "cpuMillicores", 500L));
            proof.put("beyondQuotaRejected", !fault.equals("attestation")); return proof;
        }
        public Map<String, Object> networkControl(String pod) { return Map.of("networkReachable", true); }
        public Map<String, Object> isolate(String pod, String uid, String revision) {
            var value = get("pod", pod); check(value, uid, revision);
            RunnerJson.map(RunnerJson.map(value.get("metadata")).get("labels")).remove("ravenroot.ai/attesting"); return value;
        }
        public Process agent(String pod, int protocolBytes) { agentCalls++; throw new AssertionError("effects may not be replayed"); }
    }
}
