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
    @Test void designatedRemoteClientUsesAuthenticatedFencedArtifactProtocol(@TempDir Path directory) throws Exception {
        var clock = Clock.systemUTC();
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
            var service = new RunnerJobService(store, clock, List.of(definition), List.of(registration), Map.of("tenant", policy));
            var submit = new RunnerJobOperation.Submit(identity, definition, "read", policy, registration,
                    OpaquePayload.of("{}".getBytes(), "application/json"), clock.instant().plusSeconds(300), UUID.randomUUID(),
                    OpaquePayload.empty("application/vnd.ravenroot.runner-continuation.v1"));
            store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent()).apply(
                    new ExecutionTransition.ProcessCreated(process, new GraphVersionPin("test-pin"))).runner(submit).build()).toCompletableFuture().join();
            var audit = new java.util.concurrent.CopyOnWriteArrayList<AuthorizationAuditEvent>();
            var control = new AuthorizedRunnerControl(service, new DefaultAuthorizationService(audit::add), "trusted",
                    new RunnerArtifactStore(directory.toRealPath().resolve("artifacts")), clock);
            try (var continuations = new PinnedRunnerContinuationExecutor(service, graphs, engine,
                    BehaviorRegistry.standard().withRunnerJobs(service), new ExecutionMonitor(), GraphExecutionLimits.DEFAULTS, null);
                 var server = new RavenrootServer(new DefaultRavenrootApplication(engine, new ExecutionMonitor()),
                         new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), directory, headers -> {
                             String token = headers.getFirst("Authorization");
                             if (token == null) throw new AuthenticationException("required");
                             boolean user = token.equals("Bearer operator");
                             return new AuthenticatedPrincipal(user ? "operator" : "designated",
                                     user ? AuthenticatedPrincipal.Type.USER : AuthenticatedPrincipal.Type.WORKLOAD,
                                     token.equals("Bearer wrong-issuer") ? "other" : "trusted",
                                     token.equals("Bearer other-tenant") ? "other" : "tenant", Set.of(Role.TENANT_ADMIN),
                                     Set.of("ravenroot.runner.read", "ravenroot.runner.admin", "ravenroot.runner.dispatch", "ravenroot.runner.control"));
                         })) {
                server.installRunnerPlane(control, continuations); server.start();
                URI endpoint = URI.create("http://127.0.0.1:" + server.port());
                var http = HttpClient.newHttpClient();
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
            }
        }
    }
}
