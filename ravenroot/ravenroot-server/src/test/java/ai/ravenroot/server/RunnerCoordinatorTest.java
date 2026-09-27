package ai.ravenroot.server;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.persistence.*;
import ai.ravenroot.api.runner.*;
import ai.ravenroot.api.security.*;
import ai.ravenroot.core.runner.*;
import ai.ravenroot.server.persistence.*;
import ai.ravenroot.server.security.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real shared-store composition, not permission to replicate the full authoring server. */
class RunnerCoordinatorTest {
    @TempDir Path directory;
    private static final Clock CLOCK = Clock.systemUTC();
    private static RunnerPolicy policy() {
        return new RunnerPolicy(Set.of(RunnerPolicy.Capability.WORKSPACE_READ), Set.of(), Set.of(), Set.of(), Set.of(),
                new RunnerPolicy.Limits(Duration.ofMinutes(5), 64_000_000, 1, 1_000_000, 4096, 1024, 4096));
    }
    private static AgentDefinition definition() {
        return new AgentDefinition(new AgentDefinition.Reference("tenant", "reviewer", 1), "Conformance test identity",
                "runtime", "model", Map.of("review", new AgentCommand("review", true, policy(), Set.of("completed"))),
                Set.of(), Set.of(), policy(), Duration.ZERO, "object");
    }
    private static RunnerRegistration worker() {
        return new RunnerRegistration(1, "tenant", "worker", "local-container-v1", Set.of(), policy());
    }
    private static AuthenticatedPrincipal principal() {
        return new AuthenticatedPrincipal("worker", AuthenticatedPrincipal.Type.WORKLOAD, "trusted", "tenant", Set.of(Role.TENANT_ADMIN),
                Arrays.stream(AuthorizationAction.values()).filter(AuthorizationAction::available)
                        .map(AuthorizationAction::requiredScope).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void topologyRejectsLocalAuthorityMissingIdentityOrUnattestedSharedArtifacts() {
        var environment = new HashMap<String, String>();
        environment.put("RAVENROOT_EXECUTION_STORE", "postgresql");
        environment.put("RAVENROOT_EXECUTION_STORE_URL", "jdbc:postgresql://localhost/fixture");
        var shared = ExecutionStoreConfiguration.fromEnvironment(environment);
        assertThrows(IllegalArgumentException.class, () -> RunnerCoordinatorMain.validateTopology(environment, shared));
        environment.put("RAVENROOT_AUTH_MODE", "oidc");
        assertThrows(IllegalArgumentException.class, () -> RunnerCoordinatorMain.validateTopology(environment, shared));
        environment.put("RAVENROOT_RUNNER_SHARED_ARTIFACTS", "true");
        assertDoesNotThrow(() -> RunnerCoordinatorMain.validateTopology(environment, shared));
        assertThrows(IllegalArgumentException.class, () -> RunnerCoordinatorMain.validateTopology(environment,
                ExecutionStoreConfiguration.fromEnvironment(Map.of())));
        environment.put("RAVENROOT_EXECUTION_STORE_DIR", directory.toString());
        assertThrows(IllegalArgumentException.class, () -> RunnerCoordinatorMain.validateTopology(environment, shared));
    }
    @Test void twoCoordinatorsShareCatalogAvailabilityClaimsAndReportsWhileUnsafeServicesAreAbsent() throws Exception {
        try (var database = new PostgreSQLContainer<>("postgres:17-alpine")) {
            database.start();
            var configuration = ExecutionStoreConfiguration.fromEnvironment(Map.of(
                    "RAVENROOT_EXECUTION_STORE", "postgresql", "RAVENROOT_EXECUTION_STORE_URL", database.getJdbcUrl(),
                    "RAVENROOT_EXECUTION_STORE_USER", database.getUsername(), "RAVENROOT_EXECUTION_STORE_PASSWORD", database.getPassword()));
            try (var first = ExecutionStoreBootstrap.openOwned(configuration, CLOCK);
                 var second = ExecutionStoreBootstrap.openOwned(configuration, CLOCK);
                 var bootstrap = Executors.newFixedThreadPool(2)) {
                var gate = new CountDownLatch(1);
                var services = List.of(first, second).stream().map(open -> bootstrap.submit(() -> {
                    gate.await(); return new RunnerJobService(open.store(), CLOCK, List.of(definition()), List.of(worker()), Map.of("tenant", policy()));
                })).toList();
                gate.countDown();
                var one = services.get(0).get(30, TimeUnit.SECONDS); var two = services.get(1).get(30, TimeUnit.SECONDS);
                RequestAuthenticator authenticate = headers -> {
                    if (!"Bearer fixture".equals(headers.getFirst("Authorization"))) throw new AuthenticationException("missing fixture identity");
                    return principal();
                };
                var artifacts = directory.toRealPath().resolve("shared-artifacts");
                try (var a = new RunnerCoordinator(new InetSocketAddress("127.0.0.1", 0), authenticate,
                        new DefaultAuthorizationService(ignored -> {}), one, "trusted", new RunnerArtifactStore(artifacts), CLOCK, 2, 7);
                     var b = new RunnerCoordinator(new InetSocketAddress("127.0.0.1", 0), authenticate,
                        new DefaultAuthorizationService(ignored -> {}), two, "trusted", new RunnerArtifactStore(artifacts), CLOCK, 7, 2)) {
                    a.start(); b.start();
                    var left = new RemoteRunnerClient(URI.create("http://127.0.0.1:" + a.port()), () -> "fixture");
                    var right = new RemoteRunnerClient(URI.create("http://127.0.0.1:" + b.port()), () -> "fixture");
                    left.availability(7, 0, Set.of("runtime"), Duration.ofSeconds(30));
                    assertEquals(7, second.store().runnerAvailability("tenant").toCompletableFuture().join().getFirst().capacity());
                    assertThrows(RemoteRunnerClient.ProtocolRefusal.class, () -> right.availability(2, 0, Set.of("runtime"), Duration.ofSeconds(30)),
                            "a second process cannot steal a live worker incarnation through another coordinator");
                    var key = new ExecutionKey("tenant", UUID.randomUUID());
                    var identity = new RunnerJobIdentity(key, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
                    var invocation = new NodeInvocation(identity.invocationId(), "reviewer", Set.of(), NodeInvocationStatus.WAITING,
                            List.of(new NodeAttempt(identity.attemptId(), 1, NodeAttemptStatus.WAITING)), ai.ravenroot.api.execution.NodeCommand.application("review"));
                    var traversal = new Traversal(identity.traversalId(), "reviewer", TraversalStatus.WAITING, Map.of(identity.invocationId(), invocation));
                    var submit = new RunnerJobOperation.Submit(identity, definition(), "review", policy(), worker(),
                            OpaquePayload.of("{}".getBytes(), "application/json"), CLOCK.instant().plusSeconds(120), UUID.randomUUID(),
                            OpaquePayload.empty("application/vnd.ravenroot.runner-continuation.v1"));
                    first.store().apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                            .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.WAITING,
                                    Map.of(identity.traversalId(), traversal)), new GraphVersionPin("fixture-graph"))).runner(submit).build()).toCompletableFuture().join();
                    var claimGate = new CountDownLatch(1);
                    var claims = List.of(left, right).stream().map(client -> bootstrap.submit(() -> {
                        claimGate.await(); try { return client.claim(key.processInstanceId(), submit.jobId(), false); }
                        catch (RemoteRunnerClient.ProtocolRefusal conflict) { assertEquals(409, conflict.status()); return null; }
                    })).toList();
                    claimGate.countDown();
                    var firstClaim = claims.get(0).get(10, TimeUnit.SECONDS); var secondClaim = claims.get(1).get(10, TimeUnit.SECONDS);
                    assertNotEquals(firstClaim == null, secondClaim == null);
                    var accepted = firstClaim == null ? secondClaim : firstClaim;
                    var result = new RunnerResult("completed", OpaquePayload.of("{\"result\":\"reviewed\"}".getBytes(), "application/json"), List.of(), UUID.randomUUID());
                    right.complete(accepted, result); left.complete(accepted, result);
                    assertEquals(result, second.store().loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow().jobs().get(submit.jobId()).job().result());
                    var http = HttpClient.newHttpClient();
                    for (String absent : List.of("/v1/graphs", "/v1/execute", "/v1/program-artifacts", "/v1/credentials", "/v1/consent", "/", "/health")) {
                        assertEquals(404, http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + a.port() + absent)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
                    }
                    assertEquals(401, http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + b.port() + "/v1/runner-plane/availability"))
                            .GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
                }
            }
        }
    }
}
