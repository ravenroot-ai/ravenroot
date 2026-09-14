package ai.ravenroot.server;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.persistence.*;
import ai.ravenroot.api.runner.*;
import ai.ravenroot.api.security.*;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.persistence.InMemoryGraphDefinitionStore;
import ai.ravenroot.core.process.ProcessLifecycleService;
import ai.ravenroot.core.runner.*;
import ai.ravenroot.core.runtime.*;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class RunnerProcessLifecycleHttpTest {
    @TempDir Path directory;

    @ParameterizedTest
    @EnumSource(value = ProcessLifecycleService.Command.class, names = {"PAUSE", "STOP", "CANCEL"})
    void processHttpAuthorityGovernsRemoteReportsResolutionAndResume(ProcessLifecycleService.Command hold) throws Exception {
        var clock = new MutableClock();
        var key = new ExecutionKey("tenant", UUID.randomUUID());
        var policy = new RunnerPolicy(Set.of(RunnerPolicy.Capability.WORKSPACE_READ), Set.of(), Set.of(), Set.of(), Set.of(),
                new RunnerPolicy.Limits(Duration.ofMinutes(5), 64_000_000, 1, 1_000_000, 4096, 1024, 4096));
        var definition = new AgentDefinition(new AgentDefinition.Reference("tenant", "specialist", 1), "Read only",
                "reference", "reference", Map.of("plan", new AgentCommand("plan", true, policy, AgentCommand.STANDARD_OUTCOMES)),
                Set.of(), Set.of(), policy, Duration.ofHours(1), "object");
        var registration = new RunnerRegistration(1, "tenant", "designated", "local-container-v1", Set.of(), policy);
        var base = ai.ravenroot.persistence.sqlite.SqliteStoreConfig.defaults();
        var config = new ai.ravenroot.persistence.sqlite.SqliteStoreConfig(base.synchronousMode(), base.busyTimeout(),
                base.maxLeaseTtl(), base.maxPayloadBytes(), base.maxClockSkew(), Duration.ofSeconds(1),
                base.maxInventoryPageSize(), base.terminalRetention(), base.executionResultRetention());
        try (var store = new SqliteExecutionStore(directory.resolve("http.db"), clock, config);
             var graphs = new InMemoryGraphDefinitionStore(clock);
             var engine = new PekkoExecutionEngine("runner-lifecycle-http")) {
            var jobs = new RunnerJobService(store, clock, List.of(definition), List.of(registration), Map.of("tenant", policy));
            var behaviors = BehaviorRegistry.standard().withRunnerJobs(jobs);
            var monitor = new ExecutionMonitor();
            var application = new DefaultRavenrootApplication(engine, monitor);
            var canonical = CanonicalGraphMl.of("""
                    <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                      <key id="k" for="node" attr.name="kind" attr.type="string"/>
                      <key id="b" for="node" attr.name="behavior" attr.type="string"/>
                      <key id="d" for="node" attr.name="agentDefinition" attr.type="string"/>
                      <key id="r" for="node" attr.name="runner" attr.type="string"/>
                      <key id="c" for="edge" attr.name="command" attr.type="string"/>
                      <graph edgedefault="directed">
                        <node id="start"><data key="k">START</data></node>
                        <node id="error"><data key="k">ERROR</data></node>
                        <node id="end"><data key="k">END</data></node>
                        <node id="agent"><data key="k">BEHAVIOR</data><data key="b">workspace-agent</data><data key="d">specialist</data><data key="r">designated</data></node>
                        <edge source="start" target="agent"><data key="c">plan</data></edge><edge source="agent" target="end"/>
                      </graph>
                    </graphml>
                    """.getBytes());
            graphs.put("tenant", GraphDefinitionIdentity.forSubmission(canonical.contentId()), canonical).toCompletableFuture().join();
            UUID traversalId = UUID.randomUUID();
            long initial = store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                    .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.RUNNING,
                            Map.of(traversalId, new Traversal(traversalId, "start", TraversalStatus.RUNNING, Map.of()))),
                            new GraphVersionPin(canonical.contentId().value()))).build()).toCompletableFuture().join().revision();
            try (var manager = GraphManager.readGraphMl(new java.io.ByteArrayInputStream(canonical.bytes()));
                 var runner = new GraphRunner(manager, engine, behaviors, monitor);
                 var recorder = ExecutionRecorder.open(store, key, "initial", Duration.ofSeconds(30), initial)) {
                var actor = new SecurityContext("request", "tenant", "operator", PrincipalType.USER, "trusted");
                assertInstanceOf(RunnerJobSuspension.class, assertThrows(CompletionException.class, () -> runner.execute(actor,
                        key.processInstanceId(), traversalId, Map.of(), canonical.contentId().value(), null, null, recorder)
                        .toCompletableFuture().join()).getCause());
            }
            var id = store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow().jobs().values().iterator().next().job().identity();
            var lifecycle = new ProcessLifecycleService(store, application, null, clock);
            var control = new AuthorizedRunnerControl(jobs, new DefaultAuthorizationService(ignored -> { }), "trusted",
                    new RunnerArtifactStore(directory.toRealPath().resolve("artifacts")), clock);
            try (var continuations = new PinnedRunnerContinuationExecutor(jobs, graphs, engine, behaviors, monitor, GraphExecutionLimits.DEFAULTS, null);
                 var server = new RavenrootServer(application, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), directory,
                         headers -> {
                             String token = headers.getFirst("Authorization");
                             boolean user = !"Bearer runner".equals(token);
                             return new AuthenticatedPrincipal(user ? "operator" : "designated",
                                     user ? AuthenticatedPrincipal.Type.USER : AuthenticatedPrincipal.Type.WORKLOAD,
                                     "trusted", "Bearer other".equals(token) ? "other" : "tenant", Set.of(Role.TENANT_ADMIN),
                                     Arrays.stream(AuthorizationAction.values()).filter(AuthorizationAction::available)
                                             .map(AuthorizationAction::requiredScope).collect(java.util.stream.Collectors.toSet()));
                         })) {
                // Both embedding composition orders must install the same process-RESUME hook.
                if (hold == ProcessLifecycleService.Command.STOP) {
                    server.installRunnerPlane(control, continuations); server.installProcessLifecycle(lifecycle);
                } else {
                    server.installProcessLifecycle(lifecycle); server.installRunnerPlane(control, continuations);
                }
                server.start();
                URI endpoint = URI.create("http://127.0.0.1:" + server.port());
                var client = new RemoteRunnerClient(endpoint, () -> "runner");
                long queued = revision(store, key);
                assertEquals(404, lifecycle(endpoint, key, "pause", queued, "foreign", "other").statusCode());
                assertEquals(queued, revision(store, key));
                assertEquals(200, lifecycle(endpoint, key, "pause", queued, "queued-pause", "operator").statusCode());
                assertThrows(Exception.class, () -> client.claim(key.processInstanceId(), id.runnerJobId(), false));
                assertEquals(200, lifecycle(endpoint, key, "resume", revision(store, key), "queued-resume", "operator").statusCode());
                var claimed = client.claim(key.processInstanceId(), id.runnerJobId(), false);
                long generation = revision(store, key);
                var response = lifecycle(endpoint, key, hold.name().toLowerCase(Locale.ROOT), generation, "hold", "operator");
                assertEquals(200, response.statusCode(), response.body());
                Instant heldAt = clock.instant();
                var journal = store.readJournal("tenant", 0, 100).toCompletableFuture().join();
                store.advanceOutboxCursor(store.outboxCursor("tenant", "fixture").toCompletableFuture().join(),
                        journal.getLast().journalOffset()).toCompletableFuture().join();
                clock.now = clock.now.plusSeconds(2);
                assertEquals(journal.size(), store.compactJournal("tenant").toCompletableFuture().join());
                var report = new RunnerResult("answered", OpaquePayload.of("{}".getBytes(), "application/json"), List.of(), UUID.randomUUID());
                client.complete(claimed, report); client.complete(claimed, report);
                continuations.resume(key, id.runnerJobId()).toCompletableFuture().join();
                var workspace = store.loadRunnerWorkspace(key).toCompletableFuture().join().orElseThrow();
                assertEquals(report, workspace.jobs().get(id.runnerJobId()).job().result());
                var state = store.load(key).toCompletableFuture().join().state();
                assertFalse(state.traversals().get(traversalId).invocations().values().stream()
                        .anyMatch(invocation -> invocation.parentInvocationIds().contains(id.invocationId())));
                if (hold == ProcessLifecycleService.Command.CANCEL) {
                    assertEquals(RunnerJob.State.CANCELLED, workspace.jobs().get(id.runnerJobId()).job().state());
                    assertEquals(ExecutionTerminationReason.CANCELLED, state.terminationReason());
                    assertEquals(heldAt, workspace.processTerminalAt());
                    assertTrue(lifecycle(endpoint, key, "cancel", generation, "hold", "operator").body().contains("REPLAYED"));
                    assertTrue(lifecycle(endpoint, key, "resume", revision(store, key), "resume", "operator").body().contains("TERMINAL"));
                    assertEquals(409, resolve(endpoint, key, id, revision(store, key), "operator").statusCode());
                } else {
                    assertEquals(ProcessInstanceStatus.WAITING, state.status());
                    jobs.mutate(new SecurityContext("mark", "tenant", "operator", PrincipalType.USER, "trusted"), key,
                            new RunnerJobOperation.ContinuationUncertain(id.runnerJobId()));
                    long expected = revision(store, key);
                    assertEquals(403, resolve(endpoint, key, id, expected, "runner").statusCode());
                    assertEquals(404, resolve(endpoint, key, id, expected, "other").statusCode());
                    assertEquals(200, resolve(endpoint, key, id, expected, "operator").statusCode());
                    assertEquals(409, resolve(endpoint, key, id, expected, "operator").statusCode());
                    continuations.resume(key, id.runnerJobId()).toCompletableFuture().join();
                    assertEquals(ProcessInstanceStatus.WAITING, store.load(key).toCompletableFuture().join().state().status());
                    assertEquals(200, lifecycle(endpoint, key, "resume", revision(store, key), "resume", "operator").statusCode());
                    long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                    while (!store.load(key).toCompletableFuture().join().state().status().terminal() && System.nanoTime() < until) Thread.sleep(10);
                    state = store.load(key).toCompletableFuture().join().state();
                    assertEquals(ProcessInstanceStatus.COMPLETED, state.status());
                    assertEquals(1, state.traversals().get(traversalId).invocations().get(id.invocationId()).attempts().size());
                }
            }
        }
    }

    private static long revision(ExecutionStore store, ExecutionKey key) { return store.load(key).toCompletableFuture().join().revision(); }
    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
    }
    private static HttpResponse<String> lifecycle(URI endpoint, ExecutionKey key, String command, long generation, String idempotency, String actor) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(endpoint.resolve("/v1/processes/" + key.processInstanceId() + "/" + command))
                .header("Authorization", "Bearer " + actor).header("Idempotency-Key", idempotency)
                .header("X-Ravenroot-Expected-Generation", Long.toString(generation))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static HttpResponse<String> resolve(URI endpoint, ExecutionKey key, RunnerJobIdentity id, long revision, String actor) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(endpoint.resolve("/v1/runner-plane/workspaces/" + key.processInstanceId()
                        + "/jobs/" + id.runnerJobId() + "/resolve-continuation"))
                .header("Authorization", "Bearer " + actor).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"expectedRevision\":" + revision + ",\"resolution\":\"RESUME\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
