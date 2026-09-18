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
import static org.junit.jupiter.api.Assertions.*;

class RunnerProtocolHttpTest {
    @Test void disabledPlaneReturnsStructuredUnavailableForConcreteRoutes(@TempDir Path directory) throws Exception {
        try (var engine = new PekkoExecutionEngine("runner-disabled");
             var server = new RavenrootServer(new DefaultRavenrootApplication(engine, new ExecutionMonitor()),
                     new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), directory, new DisabledLoopbackAuthenticator())) {
            server.start(); var http = HttpClient.newHttpClient();
            var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/runner-plane/health"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(501, response.statusCode());
            assertEquals("RUNNER_PLANE_UNAVAILABLE", RunnerJson.read(response.body().getBytes()).get("error"));
        }
    }

    @Test void designatedRemoteClientUsesAuthenticatedFencedArtifactProtocol(@TempDir Path directory) throws Exception {
        var time = new java.util.concurrent.atomic.AtomicReference<>(Instant.now());
        var clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return time.get(); }
        };
        var policy = new RunnerPolicy(Set.of(RunnerPolicy.Capability.WORKSPACE_READ), Set.of(), Set.of(), Set.of(), Set.of(),
                new RunnerPolicy.Limits(Duration.ofMinutes(5), 64_000_000, 1, 1_000_000, 4096, 1024, 4096));
        var command = new AgentCommand("read", true, policy, Set.of("answered", "blocked"));
        var definition = new AgentDefinition(new AgentDefinition.Reference("tenant", "researcher", 1), "Read only",
                "reference", "reference", Map.of("read", command), Set.of(), Set.of(), policy, Duration.ofHours(1), "object");
        var registration = new RunnerRegistration(1, "tenant", "designated", "local-container-v1", Set.of(), policy);
        var key = new ExecutionKey("tenant", UUID.randomUUID());
        var identity = new RunnerJobIdentity(key, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var invocation = new NodeInvocation(identity.invocationId(), "agent", Set.of(), NodeInvocationStatus.WAITING,
                List.of(new NodeAttempt(identity.attemptId(), 1, NodeAttemptStatus.WAITING)), NodeCommand.application("read"));
        var process = new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.WAITING, Map.of(identity.traversalId(),
                new Traversal(identity.traversalId(), "agent", TraversalStatus.WAITING, Map.of(identity.invocationId(), invocation))));
        try (var store = new InMemoryExecutionStore(clock); var graphs = new InMemoryGraphDefinitionStore(clock);
             var engine = new PekkoExecutionEngine("runner-http")) {
            var canonical = CanonicalGraphMl.of("""
                    <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                      <key id="k" for="node" attr.name="kind" attr.type="string"/>
                      <key id="b" for="node" attr.name="behavior" attr.type="string"/>
                      <graph edgedefault="directed">
                        <node id="start"><data key="k">START</data></node>
                        <node id="error"><data key="k">ERROR</data></node>
                        <node id="end"><data key="k">END</data></node>
                        <node id="agent"><data key="k">BEHAVIOR</data><data key="b">log</data></node>
                        <edge source="start" target="agent"/><edge source="agent" target="end"/>
                      </graph>
                    </graphml>
                    """.getBytes());
            graphs.put("tenant", GraphDefinitionIdentity.forSubmission(canonical.contentId()), canonical).toCompletableFuture().join();
            var nativeRegistration = new RunnerRegistration(1, "tenant", "native-manager", "kubernetes-pod-v1", Set.of(), policy);
            var service = new RunnerJobService(store, clock, List.of(definition), List.of(registration, nativeRegistration), Map.of("tenant", policy));
            var submit = new RunnerJobOperation.Submit(identity, definition, "read", policy, registration,
                    OpaquePayload.of("{}".getBytes(), "application/json"), clock.instant().plusSeconds(300), UUID.randomUUID(),
                    OpaquePayload.empty("application/vnd.ravenroot.runner-continuation.v1"));
            store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent()).apply(
                    new ExecutionTransition.ProcessCreated(process, new GraphVersionPin(canonical.contentId().value()))).runner(submit).build()).toCompletableFuture().join();
            var audit = new java.util.concurrent.CopyOnWriteArrayList<AuthorizationAuditEvent>();
            var control = new AuthorizedRunnerControl(service, new DefaultAuthorizationService(audit::add), "trusted",
                    new RunnerArtifactStore(directory.toRealPath().resolve("artifacts")), clock);
            try (var continuations = new PinnedRunnerContinuationExecutor(service, graphs, engine,
                    BehaviorRegistry.standard().withRunnerJobs(service), new ExecutionMonitor(), GraphExecutionLimits.DEFAULTS, null);
                 var server = new RavenrootServer(new DefaultRavenrootApplication(engine, new ExecutionMonitor()),
                         new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), directory, headers -> {
                             String token = headers.getFirst("Authorization");
                             if (token == null) throw new AuthenticationException("required");
                             boolean user = token.equals("Bearer operator") || token.equals("Bearer other-tenant-operator");
                             return new AuthenticatedPrincipal(user ? "operator" : token.equals("Bearer native") ? "native-manager" : "designated",
                                     user ? AuthenticatedPrincipal.Type.USER : AuthenticatedPrincipal.Type.WORKLOAD,
                                     token.equals("Bearer wrong-issuer") ? "other" : "trusted",
                                     token.startsWith("Bearer other-tenant") ? "other" : "tenant", Set.of(Role.TENANT_ADMIN),
                                     Set.of("ravenroot.runner.read", "ravenroot.runner.admin", "ravenroot.runner.dispatch", "ravenroot.runner.control"));
                         })) {
                server.installRunnerPlane(control, continuations); server.start();
                URI endpoint = URI.create("http://127.0.0.1:" + server.port());
                var http = HttpClient.newHttpClient();
                for (String codecs : List.of("", "workspace=3,assignment=2,result=2,profile=1", RunnerCodec.NATIVE_CAPABILITIES)) {
                    var request = HttpRequest.newBuilder(endpoint.resolve("/v1/runner-plane/assignments"))
                            .header("Authorization", "Bearer native");
                    if (!codecs.isEmpty()) request.header("X-Ravenroot-Runner-Codecs", codecs);
                    var response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(codecs.equals(RunnerCodec.NATIVE_CAPABILITIES) ? 200 : 409, response.statusCode(), response.body());
                }
                for (String method : List.of("GET", "PUT", "POST")) {
                    assertEquals(404, http.send(HttpRequest.newBuilder(endpoint.resolve("/v1/runner-plane"))
                            .header("Authorization", "Bearer operator").method(method, HttpRequest.BodyPublishers.noBody())
                            .build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                }
                var unauthenticated = HttpRequest.newBuilder(endpoint.resolve("/v1/runner-plane/catalog")).GET().build();
                assertEquals(401, http.send(unauthenticated, HttpResponse.BodyHandlers.ofString()).statusCode());
                var client = new RemoteRunnerClient(endpoint, () -> "runner");
                client.register(registration);
                assertThrows(Exception.class, () -> new RemoteRunnerClient(endpoint, () -> "wrong-issuer").register(registration));
                assertThrows(Exception.class, () -> new RemoteRunnerClient(endpoint, () -> "other-tenant")
                        .assignment(key.processInstanceId(), identity.runnerJobId()));
                var advertised = client.assignments(null);
                assertEquals(1, ((List<?>) advertised.get("items")).size());
                var claimed = client.claim(key.processInstanceId(), identity.runnerJobId(), false);
                assertEquals(1, claimed.job().fence());
                assertThrows(Exception.class, () -> client.claim(key.processInstanceId(), identity.runnerJobId(), false));
                claimed = client.heartbeat(claimed);
                String jobPath = "/v1/runner-plane/workspaces/" + key.processInstanceId() + "/jobs/" + identity.runnerJobId();
                for (String kind : List.of("", "INVALID")) {
                    var bad = HttpRequest.newBuilder(endpoint.resolve(jobPath + "/artifacts"))
                            .header("Authorization", "Bearer runner").header("Content-Type", "application/octet-stream")
                            .header("X-Runner-Fence", "1");
                    if (!kind.isEmpty()) bad.header("X-Runner-Artifact-Kind", kind);
                    var invalid = http.send(bad.POST(HttpRequest.BodyPublishers.ofString("x")).build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(400, invalid.statusCode()); assertTrue(invalid.body().contains("INVALID_RUNNER_REQUEST"));
                }
                var staleUpload = http.send(HttpRequest.newBuilder(endpoint.resolve(jobPath + "/artifacts"))
                        .header("Authorization", "Bearer runner").header("Content-Type", "application/octet-stream")
                        .header("X-Runner-Fence", "0").header("X-Runner-Artifact-Kind", "LOG")
                        .POST(HttpRequest.BodyPublishers.ofString("x")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(409, staleUpload.statusCode());
                assertEquals(400, http.send(HttpRequest.newBuilder(endpoint.resolve(jobPath + "/artifacts"))
                        .header("Authorization", "Bearer runner").header("Content-Type", "application/octet-stream")
                        .header("X-Runner-Artifact-Kind", "LOG").POST(HttpRequest.BodyPublishers.ofString("x")).build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode());
                for (String token : List.of("runner", "operator")) {
                    var resolution = http.send(HttpRequest.newBuilder(endpoint.resolve(jobPath + "/resolve-continuation"))
                            .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"expectedRevision\":1,\"resolution\":\"RESUME\"}"))
                            .build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(token.equals("runner") ? 403 : 409, resolution.statusCode(), resolution.body());
                }
                for (String invalidPage : List.of("/health?wrong=x", "/audit?afterOffset=-1", "/audit?afterOffset=no")) {
                    assertEquals(400, http.send(HttpRequest.newBuilder(endpoint.resolve("/v1/runner-plane" + invalidPage))
                            .header("Authorization", "Bearer operator").GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                }
                var health = http.send(HttpRequest.newBuilder(endpoint.resolve("/v1/runner-plane/health"))
                        .header("Authorization", "Bearer operator").GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, health.statusCode(), health.body());
                assertTrue(health.body().contains("CLAIMED"));
                assertTrue(health.body().contains(identity.runnerJobId().toString()));
                assertFalse(health.body().contains("Read only"), "health must not expose definition instructions");
                var artifact = client.upload(claimed, RunnerArtifact.Kind.STDERR, "bounded evidence".getBytes());
                var result = new RunnerResult("answered", OpaquePayload.of("{}".getBytes(), "application/json"), List.of(artifact), UUID.randomUUID());
                client.complete(claimed, result);
                client.complete(claimed, result);
                assertEquals(RunnerJob.State.COMPLETED, store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow()
                        .jobs().get(identity.runnerJobId()).job().state());
                String artifactPath = "/v1/runner-plane/workspaces/" + key.processInstanceId() + "/jobs/" + identity.runnerJobId()
                        + "/artifacts/" + artifact.artifactId();
                var preview = http.send(HttpRequest.newBuilder(endpoint.resolve(artifactPath)).header("Authorization", "Bearer operator")
                        .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, preview.statusCode(), preview.body());
                assertTrue(preview.body().contains("bounded evidence"));
                var binary = http.send(HttpRequest.newBuilder(endpoint.resolve(artifactPath)).header("Authorization", "Bearer operator")
                        .header("Accept", "application/octet-stream").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200, binary.statusCode());
                assertEquals("application/octet-stream", binary.headers().firstValue("Content-Type").orElseThrow());
                assertEquals("bounded evidence", new String(binary.body()));
                assertFalse(preview.body().contains(directory.toString()));
                var history = http.send(HttpRequest.newBuilder(endpoint.resolve("/v1/runner-plane/audit?afterOffset=0"))
                        .header("Authorization", "Bearer operator").GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, history.statusCode(), history.body());
                var historyItems = (List<?>) ((Map<?, ?>) RunnerJson.read(history.body().getBytes())).get("items");
                assertEquals(1, historyItems.stream().map(item -> (Map<?, ?>) item)
                        .filter(item -> item.get("type").equals("RUNNER_JOB_TERMINAL_REPORTED")).count(),
                        "duplicate terminal delivery must not duplicate its audit event");
                assertTrue(history.body().contains(identity.runnerJobId().toString()));
                var foreignHistory = http.send(HttpRequest.newBuilder(endpoint.resolve("/v1/runner-plane/audit?afterOffset=0"))
                        .header("Authorization", "Bearer other-tenant").GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, foreignHistory.statusCode());
                assertFalse(foreignHistory.body().contains(identity.runnerJobId().toString()));
                assertTrue(audit.stream().anyMatch(event -> event.action() == AuthorizationAction.RUNNER_DISPATCH));
                time.set(time.get().plus(Duration.ofHours(2)));
                assertEquals(200, http.send(HttpRequest.newBuilder(endpoint.resolve(artifactPath)).header("Authorization", "Bearer operator")
                        .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "an early job artifact remains available while its process is open");
                service.mutate(new SecurityContext("mark-uncertain", "tenant", "control", PrincipalType.WORKLOAD, "trusted"), key,
                        new RunnerJobOperation.ContinuationUncertain(identity.runnerJobId()));
                assertEquals(409, http.send(HttpRequest.newBuilder(endpoint.resolve("/v1/runner-plane/workspaces/" + key.processInstanceId() + "/release"))
                        .header("Authorization", "Bearer runner").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                var observations = new EnumMap<RunnerTelemetry.Counter, Integer>(RunnerTelemetry.Counter.class);
                service.telemetry().install(new RunnerTelemetry() {
                    public void increment(Counter counter) { observations.merge(counter, 1, Integer::sum); }
                    public void activeJobs(int count) { }
                });
                try (var recovery = new RunnerRecoveryLoop(service, continuations, Set.of("tenant"), clock)) {
                    recovery.sweep();
                    assertEquals(1, observations.get(RunnerTelemetry.Counter.RECOVERY_OBSERVED));
                    assertEquals(1, observations.get(RunnerTelemetry.Counter.UNKNOWN_OBSERVED));
                }
                long revision = store.load(key).toCompletableFuture().join().revision();
                String resolutionBody = "{\"expectedRevision\":" + revision + ",\"resolution\":\"ABANDON\"}";
                for (int index = 0; index < 3; index++) {
                    String token = index == 0 ? "other-tenant-operator" : "operator";
                    var resolved = http.send(HttpRequest.newBuilder(endpoint.resolve(jobPath + "/resolve-continuation"))
                            .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(resolutionBody)).build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(List.of(404, 200, 409).get(index), resolved.statusCode(), resolved.body());
                }
                assertFalse(store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow().jobs().get(identity.runnerJobId()).continuationUncertain());
                assertEquals(ProcessInstanceStatus.FAILED, store.load(key).toCompletableFuture().join().state().status());
                var resolvedHistory = http.send(HttpRequest.newBuilder(endpoint.resolve("/v1/runner-plane/audit"))
                        .header("Authorization", "Bearer operator").GET().build(), HttpResponse.BodyHandlers.ofString());
                assertTrue(resolvedHistory.body().contains("RUNNER_JOB_CONTINUATION_RESOLVED"));
                time.set(time.get().plusSeconds(3599));
                assertEquals(200, http.send(HttpRequest.newBuilder(endpoint.resolve(artifactPath)).header("Authorization", "Bearer operator")
                        .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                time.set(time.get().plusSeconds(1));
                assertEquals(404, http.send(HttpRequest.newBuilder(endpoint.resolve(artifactPath)).header("Authorization", "Bearer operator")
                        .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(200, http.send(HttpRequest.newBuilder(endpoint.resolve("/v1/runner-plane/workspaces/" + key.processInstanceId() + "/release"))
                        .header("Authorization", "Bearer runner").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                var unavailable = (ExecutionStore) java.lang.reflect.Proxy.newProxyInstance(ExecutionStore.class.getClassLoader(),
                        new Class<?>[]{ExecutionStore.class}, (proxy, method, arguments) -> {
                            if (method.getName().equals("listProcessInstances")) return java.util.concurrent.CompletableFuture.failedFuture(
                                    new IllegalStateException("inventory temporarily unavailable"));
                            return method.invoke(store, arguments);
                        });
                var unavailableService = new RunnerJobService(unavailable, clock, List.of(definition), List.of(registration), Map.of("tenant", policy));
                unavailableService.telemetry().install(service.telemetry());
                try (var recovery = new RunnerRecoveryLoop(unavailableService, continuations, Set.of("tenant"), clock)) {
                    recovery.sweep();
                    assertEquals(1, observations.get(RunnerTelemetry.Counter.RECOVERY_CONFLICT));
                }
            }
        }
    }
}
