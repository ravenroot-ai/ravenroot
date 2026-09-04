package ai.ravenroot.server;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.payload.PayloadEnvelope;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.persistence.CanonicalGraphMl;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphDefinitionIdentity;
import ai.ravenroot.api.persistence.GraphDefinitionReferences;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.HumanTaskQuery;
import ai.ravenroot.api.persistence.HumanTaskStatus;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphVersionKey;
import ai.ravenroot.core.graph.GraphVersionSnapshot;
import ai.ravenroot.core.humantask.DurableHumanTaskSuspension;
import ai.ravenroot.core.humantask.HumanTaskHandlerDispatcher;
import ai.ravenroot.core.humantask.HumanTaskService;
import ai.ravenroot.core.humantask.PinnedGraphHumanTaskContinuationExecutor;
import ai.ravenroot.core.recovery.ExecutionRecoveryService;
import ai.ravenroot.core.recovery.RecoveryOutcome;
import ai.ravenroot.core.recovery.RepeatabilityDeclarations;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.ExecutionRecorder;
import ai.ravenroot.core.runtime.GraphRunner;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.persistence.sqlite.SqliteGraphDefinitionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Restart proof through the production Pekko adapter, not an in-thread execution stand-in. */
class HumanTaskPekkoRestartIntegrationTest {
    private static final String TENANT = "acme";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final byte[] GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="title" for="node" attr.name="title" attr.type="string"/>
              <key id="responseSchema" for="node" attr.name="responseSchema" attr.type="string"/>
              <key id="responseSchemaVersion" for="node" attr.name="responseSchemaVersion" attr.type="string"/>
              <key id="authorizedRoles" for="node" attr.name="authorizedRoles" attr.type="string"/>
              <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="pekko-human-restart" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="review">
                  <data key="kind">BEHAVIOR</data><data key="behavior">human-task</data>
                  <data key="title">Approve release</data>
                  <data key="responseSchema">release.decision</data>
                  <data key="responseSchemaVersion">1</data>
                  <data key="authorizedRoles">APPROVER</data>
                </node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="start-review" source="start" target="review"/>
                <edge id="review-end" source="review" target="end">
                  <data key="edge-outcome">resolved</data>
                </edge>
              </graph>
            </graphml>
            """.getBytes(StandardCharsets.UTF_8);

    @Test
    void actorSystemAndStoreCanCloseAndReopenAroundAFirstClassHumanTask(@TempDir Path directory)
            throws Exception {
        Path database = directory.resolve("pekko-human-restart.db");
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();

        try (var store = new SqliteExecutionStore(database, CLOCK);
             var definitions = definitions(database);
             var engine = engine("before")) {
            CanonicalGraphMl canonical = CanonicalGraphMl.of(GRAPH);
            var stored = definitions.put(TENANT,
                    GraphDefinitionIdentity.forSubmission(canonical.contentId()), canonical)
                    .toCompletableFuture().join();
            String pin = stored.key().contentId().value();
            long revision = createRunning(store, key, traversalId, pin);
            var tasks = new HumanTaskService(store, CLOCK);
            try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(GRAPH));
                 var runner = new GraphRunner(manager, snapshot(stored.identity(), manager), engine,
                         standard(tasks), new ExecutionMonitor(), ExecutionIdentitySource.randomUuids(),
                         GraphRunner.DEFAULT_SHUTDOWN_BOUND);
                 var recorder = ExecutionRecorder.open(store, key, "pekko-before", TTL, revision);
                 var binding = tasks.bindLive(key, recorder)) {
                ExecutionException suspension = assertThrows(ExecutionException.class,
                        () -> runner.execute(requesterIdentity(), key.processInstanceId(), traversalId,
                                null, pin, null, null, recorder).toCompletableFuture()
                                .get(10, TimeUnit.SECONDS));
                assertInstanceOf(DurableHumanTaskSuspension.class, suspension.getCause());
            }
        }

        UUID taskId;
        try (var store = new SqliteExecutionStore(database, CLOCK)) {
            var tasks = new HumanTaskService(store, CLOCK);
            var task = tasks.inbox(requester(), HumanTaskQuery.everything(10)).items().getFirst();
            taskId = task.request().taskId();
            assertEquals(HumanTaskStatus.RESOLVED,
                    tasks.resolve(approver(), taskId, task.generation(), response()).task().status());
        }

        try (var store = new SqliteExecutionStore(database, CLOCK);
             var definitions = definitions(database);
             var engine = engine("after")) {
            var tasks = new HumanTaskService(store, CLOCK);
            var continuation = new PinnedGraphHumanTaskContinuationExecutor(definitions, store, tasks,
                    engine, standard(tasks), new ExecutionMonitor(), ExecutionIdentitySource.randomUuids(),
                    "pekko-after", TTL);
            var recovery = new ExecutionRecoveryService(store, List.of(TENANT), "pekko-after", 10, TTL,
                    RepeatabilityDeclarations.NONE_DECLARED,
                    new HumanTaskHandlerDispatcher(store, tasks, continuation));
            List<RecoveryOutcome> outcomes = recovery.sweepOnce();
            assertTrue(outcomes.stream().anyMatch(RecoveryOutcome.HandlerDispatched.class::isInstance),
                    outcomes::toString);
            assertEquals(ProcessInstanceStatus.COMPLETED,
                    store.load(key).toCompletableFuture().join().state().status());
            assertEquals(1, tasks.inbox(requester(), HumanTaskQuery.everything(10)).items().size(),
                    "re-entry must not register the human task again");
        }

        try (var store = new SqliteExecutionStore(database, CLOCK)) {
            assertTrue(store.claimPendingWork(TENANT, "third-process", 10, TTL)
                    .toCompletableFuture().join().stream()
                    .noneMatch(PendingWork.HandlerTrigger.class::isInstance));
            assertEquals(HumanTaskStatus.RESOLVED,
                    store.loadHumanTask(TENANT, taskId).toCompletableFuture().join()
                            .orElseThrow().status());
        }
    }

    private static PekkoExecutionEngine engine(String phase) {
        return new PekkoExecutionEngine("human-task-pekko-" + phase + "-" + UUID.randomUUID());
    }

    private static SqliteGraphDefinitionStore definitions(Path database) {
        return new SqliteGraphDefinitionStore(database, CLOCK, GraphDefinitionReferences.NONE);
    }

    private static BehaviorRegistry standard(HumanTaskService tasks) {
        return BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults(),
                ai.ravenroot.api.publication.PublicationPolicyResolver.none(),
                ai.ravenroot.api.publication.PublicationAuditSink.noop(), tasks);
    }

    private static GraphVersionSnapshot snapshot(GraphDefinitionIdentity identity, GraphManager manager) {
        return GraphVersionSnapshot.create(new GraphVersionKey(identity.graphId(), identity.versionId()),
                manager.definition());
    }

    private static long createRunning(SqliteExecutionStore store, ExecutionKey key, UUID traversalId,
                                      String pin) {
        var traversal = new Traversal(traversalId, "start", TraversalStatus.ACCEPTED, Map.of());
        var created = store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(),
                        ProcessInstanceStatus.ACCEPTED, Map.of(traversalId, traversal)),
                        new GraphVersionPin(pin))).build()).toCompletableFuture().join();
        return store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .build()).toCompletableFuture().join().revision();
    }

    private static OpaquePayload response() {
        return OpaquePayload.of(PayloadEnvelope.of("release.decision", "1",
                        PayloadValue.map(Map.of("decision", PayloadValue.of("approved"))))
                .toJson().getBytes(StandardCharsets.UTF_8),
                "application/vnd.ravenroot.payload+json");
    }

    private static RequestContext requester() {
        return new RequestContext("requester-call", "requester", PrincipalType.USER, "issuer", TENANT,
                Set.of(), Set.of());
    }

    private static SecurityContext requesterIdentity() {
        return SecurityContext.of(requester());
    }

    private static RequestContext approver() {
        return new RequestContext("approver-call", "approver", PrincipalType.USER, "issuer", TENANT,
                Set.of(Role.APPROVER), Set.of());
    }
}
