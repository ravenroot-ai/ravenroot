package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.payload.PayloadEnvelope;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.persistence.CanonicalGraphMl;
import ai.ravenroot.api.persistence.DurableHumanTask;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.GraphDefinitionIdentity;
import ai.ravenroot.api.persistence.GraphDefinitionReferences;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.HumanTaskMetadata;
import ai.ravenroot.api.persistence.HumanTaskQuery;
import ai.ravenroot.api.persistence.HumanTaskReentryMapping;
import ai.ravenroot.api.persistence.HumanTaskResponseSchema;
import ai.ravenroot.api.persistence.HumanTaskStatus;
import ai.ravenroot.api.persistence.HandlerAuthorization;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.api.security.ToolCallAuditSink;
import ai.ravenroot.api.security.ToolDecision;
import ai.ravenroot.core.approval.ToolApprovalService;
import ai.ravenroot.core.approval.ToolApprovalSettings;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphVersionKey;
import ai.ravenroot.core.graph.GraphVersionSnapshot;
import ai.ravenroot.core.humantask.DurableHumanTaskSuspension;
import ai.ravenroot.core.humantask.HumanTaskDefinition;
import ai.ravenroot.core.humantask.HumanTaskHandlerDispatcher;
import ai.ravenroot.core.humantask.HumanTaskResult;
import ai.ravenroot.core.humantask.HumanTaskService;
import ai.ravenroot.core.humantask.PinnedGraphHumanTaskContinuationExecutor;
import ai.ravenroot.core.security.nodepackage.ManagedNodePackageServices;
import ai.ravenroot.core.security.nodepackage.NodePackageEgressPolicy;
import ai.ravenroot.core.recovery.ExecutionRecoveryService;
import ai.ravenroot.core.recovery.RecoveryOutcome;
import ai.ravenroot.core.recovery.RepeatabilityDeclarations;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-store restart proof for registration, decision, pinned re-entry, and one-time acknowledgement. */
class HumanTaskRestartIntegrationTest {
    private static final String TENANT = "acme";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
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
              <graph id="human-restart" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="review">
                  <data key="kind">BEHAVIOR</data>
                  <data key="behavior">human-task</data>
                  <data key="title">Approve bounded release</data>
                  <data key="responseSchema">release.decision</data>
                  <data key="responseSchemaVersion">1</data>
                  <data key="authorizedRoles">APPROVER</data>
                </node>
                <node id="capture"><data key="kind">BEHAVIOR</data><data key="behavior">capture</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="start-review" source="start" target="review"/>
                <edge id="review-capture" source="review" target="capture">
                  <data key="edge-outcome">resolved</data>
                </edge>
                <edge id="capture-end" source="capture" target="end"/>
              </graph>
            </graphml>
            """.getBytes(StandardCharsets.UTF_8);
    private static final byte[] CHAINED_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="title" for="node" attr.name="title" attr.type="string"/>
              <key id="responseSchema" for="node" attr.name="responseSchema" attr.type="string"/>
              <key id="responseSchemaVersion" for="node" attr.name="responseSchemaVersion" attr.type="string"/>
              <key id="authorizedRoles" for="node" attr.name="authorizedRoles" attr.type="string"/>
              <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="human-chain" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="first">
                  <data key="kind">BEHAVIOR</data>
                  <data key="behavior">human-task</data>
                  <data key="title">First decision</data>
                  <data key="responseSchema">release.decision</data>
                  <data key="responseSchemaVersion">1</data>
                  <data key="authorizedRoles">APPROVER</data>
                </node>
                <node id="second">
                  <data key="kind">BEHAVIOR</data>
                  <data key="behavior">human-task</data>
                  <data key="title">Second decision</data>
                  <data key="responseSchema">release.decision</data>
                  <data key="responseSchemaVersion">1</data>
                  <data key="authorizedRoles">APPROVER</data>
                </node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="start-first" source="start" target="first"/>
                <edge id="first-second" source="first" target="second">
                  <data key="edge-outcome">resolved</data>
                </edge>
                <edge id="second-end" source="second" target="end">
                  <data key="edge-outcome">resolved</data>
                </edge>
              </graph>
            </graphml>
            """.getBytes(StandardCharsets.UTF_8);
    private static final byte[] HUMAN_TO_TOOL_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="title" for="node" attr.name="title" attr.type="string"/>
              <key id="responseSchema" for="node" attr.name="responseSchema" attr.type="string"/>
              <key id="responseSchemaVersion" for="node" attr.name="responseSchemaVersion" attr.type="string"/>
              <key id="authorizedRoles" for="node" attr.name="authorizedRoles" attr.type="string"/>
              <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="human-to-tool" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="review">
                  <data key="kind">BEHAVIOR</data><data key="behavior">human-task</data>
                  <data key="title">Approve tool request</data>
                  <data key="responseSchema">release.decision</data>
                  <data key="responseSchemaVersion">1</data>
                  <data key="authorizedRoles">APPROVER</data>
                </node>
                <node id="tool"><data key="kind">BEHAVIOR</data>
                  <data key="behavior">approval-request</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="start-review" source="start" target="review"/>
                <edge id="review-tool" source="review" target="tool">
                  <data key="edge-outcome">resolved</data>
                </edge>
                <edge id="tool-end" source="tool" target="end"/>
              </graph>
            </graphml>
            """.getBytes(StandardCharsets.UTF_8);
    private static final byte[] TERMINAL_RACE_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <graph id="human-terminal-race" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="race"><data key="kind">BEHAVIOR</data>
                  <data key="behavior">terminal-race-human-task</data></node>
                <node id="capture"><data key="kind">BEHAVIOR</data>
                  <data key="behavior">capture</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="start-race" source="start" target="race"/>
                <edge id="race-capture" source="race" target="capture"/>
                <edge id="capture-end" source="capture" target="end"/>
              </graph>
            </graphml>
            """.getBytes(StandardCharsets.UTF_8);
    private static final byte[] CHAINED_TERMINAL_RACE_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="title" for="node" attr.name="title" attr.type="string"/>
              <key id="responseSchema" for="node" attr.name="responseSchema" attr.type="string"/>
              <key id="responseSchemaVersion" for="node" attr.name="responseSchemaVersion" attr.type="string"/>
              <key id="authorizedRoles" for="node" attr.name="authorizedRoles" attr.type="string"/>
              <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="human-chained-terminal-race" edgedefault="directed">
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="start"><data key="kind">START</data></node>
                <node id="first">
                  <data key="kind">BEHAVIOR</data>
                  <data key="behavior">human-task</data>
                  <data key="title">First decision</data>
                  <data key="responseSchema">release.decision</data>
                  <data key="responseSchemaVersion">1</data>
                  <data key="authorizedRoles">APPROVER</data>
                </node>
                <node id="race"><data key="kind">BEHAVIOR</data>
                  <data key="behavior">terminal-race-human-task</data></node>
                <node id="capture"><data key="kind">BEHAVIOR</data>
                  <data key="behavior">capture</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="start-first" source="start" target="first"/>
                <edge id="first-race" source="first" target="race">
                  <data key="edge-outcome">resolved</data>
                </edge>
                <edge id="race-capture" source="race" target="capture"/>
                <edge id="capture-end" source="capture" target="end"/>
              </graph>
            </graphml>
            """.getBytes(StandardCharsets.UTF_8);

    @Test
    void terminalDecisionBetweenInitialCommitAndVerificationRemainsASuspension(
            @TempDir Path directory) throws Exception {
        var clock = new MutableClock(NOW);
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID originalTraversal = UUID.randomUUID();
        var terminalized = new CountDownLatch(1);
        var releaseSignal = new CountDownLatch(1);
        var raceExecutions = new AtomicInteger();
        var captures = new AtomicInteger();
        try (var store = new SqliteExecutionStore(directory.resolve("initial-terminal-race.db"), clock);
             var definitions = new SqliteGraphDefinitionStore(directory.resolve("initial-terminal-race.db"),
                     clock, GraphDefinitionReferences.NONE);
             var engine = new SameThreadExecutionEngine()) {
            CanonicalGraphMl canonical = CanonicalGraphMl.of(TERMINAL_RACE_GRAPH);
            var storedDefinition = definitions.put(TENANT,
                    GraphDefinitionIdentity.forSubmission(canonical.contentId()), canonical)
                    .toCompletableFuture().join();
            String pin = storedDefinition.key().contentId().value();
            long revision = createRunning(store, key, originalTraversal, pin);
            var tasks = new HumanTaskService(store, clock);
            BehaviorRegistry behaviors = terminalRaceBehaviors(
                    tasks, terminalized, releaseSignal, raceExecutions, captures);

            try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(TERMINAL_RACE_GRAPH));
                 var runner = new GraphRunner(manager, snapshot(storedDefinition.identity(), manager),
                         engine, behaviors, new ExecutionMonitor(), ExecutionIdentitySource.randomUuids(),
                         GraphRunner.DEFAULT_SHUTDOWN_BOUND);
                 var recorder = ExecutionRecorder.open(store, key, "live-race", TTL, revision);
                 var binding = tasks.bindLive(key, recorder, runner::continuationBudget)) {
                CompletableFuture<Throwable> execution = CompletableFuture.supplyAsync(() -> {
                    try {
                        runner.execute(requesterIdentity(), key.processInstanceId(), originalTraversal,
                                null, pin, null, null, recorder).toCompletableFuture().join();
                        return null;
                    } catch (CompletionException suspended) {
                        return suspended.getCause();
                    }
                });
                try {
                    assertTrue(terminalized.await(10, TimeUnit.SECONDS),
                            "the test behavior must settle after committing its suspension");
                    var request = taskAt(tasks, "race").request();
                    var registeredMessage = new ai.ravenroot.api.execution.NodeMessage(
                            request.requester(), key.processInstanceId(), request.traversalId(),
                            request.invocationId(), request.attemptId(), request.nodeId(), null, Map.of());
                    assertTrue(recorder.confirmsHumanTask(request.taskId(), registeredMessage),
                            "terminal lifecycle state must preserve the immutable suspension proof");
                    var mismatchedMessage = new ai.ravenroot.api.execution.NodeMessage(
                            request.requester(), key.processInstanceId(), request.traversalId(),
                            request.invocationId(), UUID.randomUUID(), request.nodeId(), null, Map.of());
                    assertFalse(recorder.confirmsHumanTask(request.taskId(), mismatchedMessage));
                    assertFalse(recorder.confirmsHumanTask(UUID.randomUUID(), registeredMessage));
                    ExecutionStoreException staleOwner = assertThrows(ExecutionStoreException.class,
                            () -> recorder.record(List.of(new ExecutionTransition.ProcessTransitioned(
                                    ProcessInstanceStatus.FAILED)), List.of()));
                    assertInstanceOf(ExecutionStoreFailure.ConcurrencyConflict.class,
                            staleOwner.failure(), "the obsolete invocation revision must not commit");
                } finally {
                    releaseSignal.countDown();
                }
                assertInstanceOf(DurableHumanTaskSuspension.class,
                        execution.get(10, TimeUnit.SECONDS));
            }

            assertEquals(HumanTaskStatus.RESOLVED, taskAt(tasks, "race").status());
            assertEquals(ProcessInstanceStatus.RUNNING,
                    store.load(key).toCompletableFuture().join().state().status(),
                    "a verified durable suspension must not fail the process");
            var continuation = new PinnedGraphHumanTaskContinuationExecutor(definitions, store, tasks,
                    engine, behaviors, new ExecutionMonitor(), ExecutionIdentitySource.randomUuids(),
                    "recovery-race", TTL);
            var recovery = new ExecutionRecoveryService(store, List.of(TENANT), "recovery-race",
                    10, TTL, RepeatabilityDeclarations.NONE_DECLARED,
                    new HumanTaskHandlerDispatcher(store, tasks, continuation));

            List<RecoveryOutcome> firstSweep = recovery.sweepOnce();
            assertEquals(1, dispatched(firstSweep));
            assertEquals(1, raceExecutions.get());
            assertEquals(1, captures.get(), "the fresh pinned continuation must proceed once");
            assertEquals(ProcessInstanceStatus.COMPLETED,
                    store.load(key).toCompletableFuture().join().state().status());
            assertTrue(store.leases(TENANT).toCompletableFuture().join().isEmpty());

            clock.now = NOW.plus(TTL).plusSeconds(1);
            assertTrue(recovery.sweepOnce().isEmpty());
            assertEquals(1, raceExecutions.get());
            assertEquals(1, captures.get());
        }
    }

    @Test
    void terminalDecisionDuringChainedReentryAcknowledgesTheEarlierTriggerOnce(
            @TempDir Path directory) throws Exception {
        var clock = new MutableClock(NOW);
        Path database = directory.resolve("chained-terminal-race.db");
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID originalTraversal = UUID.randomUUID();
        var terminalized = new CountDownLatch(1);
        var releaseSignal = new CountDownLatch(1);
        var raceExecutions = new AtomicInteger();
        var captures = new AtomicInteger();
        try (var store = new SqliteExecutionStore(database, clock);
             var definitions = new SqliteGraphDefinitionStore(database, clock,
                     GraphDefinitionReferences.NONE);
             var engine = new SameThreadExecutionEngine()) {
            CanonicalGraphMl canonical = CanonicalGraphMl.of(CHAINED_TERMINAL_RACE_GRAPH);
            var storedDefinition = definitions.put(TENANT,
                    GraphDefinitionIdentity.forSubmission(canonical.contentId()), canonical)
                    .toCompletableFuture().join();
            String pin = storedDefinition.key().contentId().value();
            long revision = createRunning(store, key, originalTraversal, pin);
            var tasks = new HumanTaskService(store, clock);
            BehaviorRegistry behaviors = terminalRaceBehaviors(
                    tasks, terminalized, releaseSignal, raceExecutions, captures);

            try (var manager = GraphManager.readGraphMl(
                    new ByteArrayInputStream(CHAINED_TERMINAL_RACE_GRAPH));
                 var runner = new GraphRunner(manager, snapshot(storedDefinition.identity(), manager),
                         engine, behaviors, new ExecutionMonitor(), ExecutionIdentitySource.randomUuids(),
                         GraphRunner.DEFAULT_SHUTDOWN_BOUND);
                 var recorder = ExecutionRecorder.open(store, key, "live-chain-race", TTL, revision);
                 var binding = tasks.bindLive(key, recorder, runner::continuationBudget)) {
                assertThrows(ExecutionException.class,
                        () -> runner.execute(requesterIdentity(), key.processInstanceId(), originalTraversal,
                                null, pin, null, null, recorder).toCompletableFuture()
                                .get(10, TimeUnit.SECONDS));
            }
            DurableHumanTask first = taskAt(tasks, "first");
            tasks.resolve(approver(), first.request().taskId(), first.generation(), response());
            var continuation = new PinnedGraphHumanTaskContinuationExecutor(definitions, store, tasks,
                    engine, behaviors, new ExecutionMonitor(), ExecutionIdentitySource.randomUuids(),
                    "recovery-chain-race", TTL);
            var recovery = new ExecutionRecoveryService(store, List.of(TENANT), "recovery-chain-race",
                    10, TTL, RepeatabilityDeclarations.NONE_DECLARED,
                    new HumanTaskHandlerDispatcher(store, tasks, continuation));

            CompletableFuture<List<RecoveryOutcome>> firstRecovery = CompletableFuture.supplyAsync(
                    recovery::sweepOnce);
            try {
                assertTrue(terminalized.await(10, TimeUnit.SECONDS),
                        "the chained task must settle before its suspension is verified");
                assertEquals(HumanTaskStatus.RESOLVED, taskAt(tasks, "race").status());
                assertEquals(ProcessInstanceStatus.RUNNING,
                        store.load(key).toCompletableFuture().join().state().status());
            } finally {
                releaseSignal.countDown();
            }
            List<RecoveryOutcome> firstOutcomes = firstRecovery.get(10, TimeUnit.SECONDS);
            assertEquals(1, dispatched(firstOutcomes));
            assertTrue(firstOutcomes.stream().filter(RecoveryOutcome.HandlerDispatched.class::isInstance)
                    .map(RecoveryOutcome.HandlerDispatched.class::cast)
                    .allMatch(outcome -> outcome.workItemId().equals(first.request().taskId())));
            assertTrue(store.leases(TENANT).toCompletableFuture().join().isEmpty(),
                    "acknowledging the first trigger must release its continuation lease");

            DurableHumanTask second = taskAt(tasks, "race");
            List<RecoveryOutcome> secondOutcomes = recovery.sweepOnce();
            assertEquals(1, dispatched(secondOutcomes));
            assertTrue(secondOutcomes.stream().filter(RecoveryOutcome.HandlerDispatched.class::isInstance)
                    .map(RecoveryOutcome.HandlerDispatched.class::cast)
                    .allMatch(outcome -> outcome.workItemId().equals(second.request().taskId())));
            assertEquals(1, captures.get());
            assertEquals(ProcessInstanceStatus.COMPLETED,
                    store.load(key).toCompletableFuture().join().state().status());

            clock.now = NOW.plus(TTL).plusSeconds(1);
            assertTrue(recovery.sweepOnce().isEmpty(),
                    "neither acknowledged trigger may replay after its former lease expires");
            assertEquals(1, raceExecutions.get());
            assertEquals(1, captures.get());
            assertEquals(2, tasks.inbox(requester(), HumanTaskQuery.everything(10)).items().size());
        }
    }

    @Test
    void restartResumesPinnedGraphExactlyOnceWithoutRegisteringAnotherTask(@TempDir Path directory)
            throws Exception {
        Path database = directory.resolve("human-task-restart.db");
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID originalTraversal = UUID.randomUUID();
        String pin;

        try (var store = new SqliteExecutionStore(database, CLOCK);
             var definitions = new SqliteGraphDefinitionStore(database, CLOCK,
                     GraphDefinitionReferences.NONE);
             var engine = new SameThreadExecutionEngine()) {
            CanonicalGraphMl canonical = CanonicalGraphMl.of(GRAPH);
            var storedDefinition = definitions.put(TENANT,
                    GraphDefinitionIdentity.forSubmission(canonical.contentId()), canonical)
                    .toCompletableFuture().join();
            pin = storedDefinition.key().contentId().value();
            long revision = createRunning(store, key, originalTraversal, pin);
            var tasks = new HumanTaskService(store, CLOCK);
            BehaviorRegistry behaviors = standard(tasks);

            try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(GRAPH));
                 var runner = new GraphRunner(manager, snapshot(storedDefinition.identity(), manager),
                         engine, behaviors, new ExecutionMonitor(),
                         ExecutionIdentitySource.randomUuids(), GraphRunner.DEFAULT_SHUTDOWN_BOUND);
                 var recorder = ExecutionRecorder.open(store, key, "live-worker", TTL, revision);
                 var binding = tasks.bindLive(key, recorder, runner::continuationBudget)) {
                ExecutionException suspended = assertThrows(ExecutionException.class,
                        () -> runner.execute(requesterIdentity(), key.processInstanceId(), originalTraversal,
                                Map.of("private", "not materialized"), pin, null, null, recorder)
                                .toCompletableFuture().get(10, TimeUnit.SECONDS));
                assertInstanceOf(DurableHumanTaskSuspension.class, suspended.getCause());
            }
            DurableHumanTask task = onlyTask(tasks);
            assertEquals(HumanTaskStatus.WAITING, task.status());
            assertEquals(TraversalStatus.WAITING,
                    store.load(key).toCompletableFuture().join().state().traversals()
                            .get(originalTraversal).status());
        }

        UUID taskId;
        try (var store = new SqliteExecutionStore(database, CLOCK)) {
            var tasks = new HumanTaskService(store, CLOCK);
            DurableHumanTask task = onlyTask(tasks);
            taskId = task.request().taskId();
            assertTrue(store.claimPendingWork(TENANT, "pre-decision", 10, TTL)
                    .toCompletableFuture().join().stream().noneMatch(
                            ai.ravenroot.api.persistence.PendingWork.HandlerTrigger.class::isInstance));
            assertEquals(HumanTaskStatus.RESOLVED,
                    tasks.resolve(approver(), taskId, task.generation(), response()).task().status());
        }

        var captures = new AtomicInteger();
        var observed = new AtomicReference<Object>();
        try (var store = new SqliteExecutionStore(database, CLOCK);
             var definitions = new SqliteGraphDefinitionStore(database, CLOCK,
                     GraphDefinitionReferences.NONE);
             var engine = new SameThreadExecutionEngine()) {
            var tasks = new HumanTaskService(store, CLOCK);
            BehaviorRegistry behaviors = standard(tasks).register("capture", message -> {
                captures.incrementAndGet();
                observed.set(message.payload());
                return java.util.concurrent.CompletableFuture.completedFuture(
                        NodeResult.continueWith(message.payload()));
            });
            var continuation = new PinnedGraphHumanTaskContinuationExecutor(definitions, store, tasks,
                    engine, behaviors, new ExecutionMonitor(), ExecutionIdentitySource.randomUuids(),
                    "recovery-worker", TTL);
            var recovery = new ExecutionRecoveryService(store, List.of(TENANT), "recovery-worker",
                    10, TTL, RepeatabilityDeclarations.NONE_DECLARED,
                    new HumanTaskHandlerDispatcher(store, tasks, continuation));

            assertTrue(recovery.sweepOnce().stream()
                    .anyMatch(RecoveryOutcome.HandlerDispatched.class::isInstance));
            assertEquals(1, captures.get());
            assertInstanceOf(Map.class, observed.get());
            assertEquals(ProcessInstanceStatus.COMPLETED,
                    store.load(key).toCompletableFuture().join().state().status());
            assertEquals(1, tasks.inbox(requester(), HumanTaskQuery.everything(10)).items().size(),
                    "re-entry must route past the task node instead of registering it again");
            recovery.sweepOnce();
            assertEquals(1, captures.get(), "the acknowledged continuation must not replay");
        }

        try (var store = new SqliteExecutionStore(database, CLOCK)) {
            assertTrue(store.claimPendingWork(TENANT, "after-second-restart", 10, TTL)
                    .toCompletableFuture().join().isEmpty());
            assertEquals(HumanTaskStatus.RESOLVED,
                    store.loadHumanTask(TENANT, taskId).toCompletableFuture().join()
                            .orElseThrow().status());
        }
    }

    @Test
    void humanTaskRestartRestoresCumulativeGraphBudgetInsteadOfResettingIt(
            @TempDir Path directory) throws Exception {
        Path database = directory.resolve("human-task-budget-restart.db");
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID originalTraversal = UUID.randomUUID();
        String pin;
        GraphExecutionLimits tight = limitsWithTraversalSteps(3);

        try (var store = new SqliteExecutionStore(database, CLOCK);
             var definitions = new SqliteGraphDefinitionStore(database, CLOCK,
                     GraphDefinitionReferences.NONE);
             var engine = new SameThreadExecutionEngine()) {
            CanonicalGraphMl canonical = CanonicalGraphMl.of(GRAPH);
            var storedDefinition = definitions.put(TENANT,
                    GraphDefinitionIdentity.forSubmission(canonical.contentId()), canonical)
                    .toCompletableFuture().join();
            pin = storedDefinition.key().contentId().value();
            long revision = createRunning(store, key, originalTraversal, pin);
            var tasks = new HumanTaskService(store, CLOCK);
            try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(GRAPH));
                 var runner = new GraphRunner(manager, snapshot(storedDefinition.identity(), manager),
                         engine, standard(tasks), new ExecutionMonitor(),
                         ExecutionIdentitySource.randomUuids(), GraphRunner.DEFAULT_SHUTDOWN_BOUND, tight);
                 var recorder = ExecutionRecorder.open(store, key, "budget-before", TTL, revision);
                 var binding = tasks.bindLive(key, recorder, runner::continuationBudget)) {
                assertThrows(ExecutionException.class,
                        () -> runner.execute(requesterIdentity(), key.processInstanceId(), originalTraversal,
                                null, pin, null, null, recorder).toCompletableFuture()
                                .get(10, TimeUnit.SECONDS));
            }
            DurableHumanTask task = onlyTask(tasks);
            assertEquals(GraphExecutionContinuationCheckpoint.VERSION,
                    task.request().continuationVersion());
            var checkpoint = GraphExecutionContinuationCheckpoint.read(
                    task.request().continuationVersion(), task.request().continuation());
            assertEquals(2, checkpoint.budget().traversalSteps());
            assertEquals(1, checkpoint.budget().inFlightHops());
        }

        try (var store = new SqliteExecutionStore(database, CLOCK)) {
            var tasks = new HumanTaskService(store, CLOCK);
            DurableHumanTask task = onlyTask(tasks);
            tasks.resolve(approver(), task.request().taskId(), task.generation(), response());
        }

        var captures = new AtomicInteger();
        try (var store = new SqliteExecutionStore(database, CLOCK);
             var definitions = new SqliteGraphDefinitionStore(database, CLOCK,
                     GraphDefinitionReferences.NONE);
             var engine = new SameThreadExecutionEngine()) {
            var tasks = new HumanTaskService(store, CLOCK);
            BehaviorRegistry behaviors = standard(tasks).register("capture", message -> {
                captures.incrementAndGet();
                return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
            });
            var continuation = new PinnedGraphHumanTaskContinuationExecutor(definitions, store, tasks,
                    null, engine, behaviors, new ExecutionMonitor(), ExecutionIdentitySource.randomUuids(),
                    "budget-after", TTL, tight);
            var recovery = new ExecutionRecoveryService(store, List.of(TENANT), "budget-after",
                    10, TTL, RepeatabilityDeclarations.NONE_DECLARED,
                    new HumanTaskHandlerDispatcher(store, tasks, continuation));

            List<RecoveryOutcome> outcomes = recovery.sweepOnce();

            assertTrue(outcomes.stream().anyMatch(RecoveryOutcome.Deferred.class::isInstance),
                    outcomes::toString);
            assertEquals(1, captures.get(),
                    "only the exact remaining step may execute after restart");
            assertEquals(ProcessInstanceStatus.FAILED,
                    store.load(key).toCompletableFuture().join().state().status(),
                    "routing the end node must exceed the cumulative pre-restart step budget");
        }
    }

    @Test
    void chainedHumanTaskAcknowledgesFirstTriggerAndDoesNotReplayAfterLeaseExpiry(
            @TempDir Path directory) throws Exception {
        var clock = new MutableClock(NOW);
        Path database = directory.resolve("human-task-chain.db");
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID originalTraversal = UUID.randomUUID();

        try (var store = new SqliteExecutionStore(database, clock);
             var definitions = new SqliteGraphDefinitionStore(database, clock,
                     GraphDefinitionReferences.NONE);
             var engine = new SameThreadExecutionEngine()) {
            CanonicalGraphMl canonical = CanonicalGraphMl.of(CHAINED_GRAPH);
            var storedDefinition = definitions.put(TENANT,
                    GraphDefinitionIdentity.forSubmission(canonical.contentId()), canonical)
                    .toCompletableFuture().join();
            String pin = storedDefinition.key().contentId().value();
            long revision = createRunning(store, key, originalTraversal, pin);
            var tasks = new HumanTaskService(store, clock);
            BehaviorRegistry behaviors = standard(tasks);

            try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(CHAINED_GRAPH));
                 var runner = new GraphRunner(manager, snapshot(storedDefinition.identity(), manager),
                         engine, behaviors, new ExecutionMonitor(),
                         ExecutionIdentitySource.randomUuids(), GraphRunner.DEFAULT_SHUTDOWN_BOUND);
                 var recorder = ExecutionRecorder.open(store, key, "live-worker", TTL, revision);
                 var binding = tasks.bindLive(key, recorder, runner::continuationBudget)) {
                assertThrows(ExecutionException.class,
                        () -> runner.execute(requesterIdentity(), key.processInstanceId(), originalTraversal,
                                null, pin, null, null, recorder).toCompletableFuture()
                                .get(10, TimeUnit.SECONDS));
            }

            DurableHumanTask first = taskAt(tasks, "first");
            tasks.resolve(approver(), first.request().taskId(), first.generation(), response());
            var continuation = new PinnedGraphHumanTaskContinuationExecutor(definitions, store, tasks,
                    engine, behaviors, new ExecutionMonitor(), ExecutionIdentitySource.randomUuids(),
                    "recovery-worker", TTL);
            var recovery = new ExecutionRecoveryService(store, List.of(TENANT), "recovery-worker",
                    10, TTL, RepeatabilityDeclarations.NONE_DECLARED,
                    new HumanTaskHandlerDispatcher(store, tasks, continuation));

            List<RecoveryOutcome> firstSweep = recovery.sweepOnce();
            assertTrue(firstSweep.stream().anyMatch(RecoveryOutcome.HandlerDispatched.class::isInstance),
                    firstSweep::toString);
            assertEquals(2, tasks.inbox(requester(), HumanTaskQuery.everything(10)).items().size());
            assertEquals(HumanTaskStatus.WAITING, taskAt(tasks, "second").status());
            assertEquals(ProcessInstanceStatus.WAITING,
                    store.load(key).toCompletableFuture().join().state().status());
            assertTrue(store.leases(TENANT).toCompletableFuture().join().isEmpty(),
                    "the first trigger acknowledgement must release its continuation lease");

            clock.now = NOW.plus(TTL).plusSeconds(1);
            assertTrue(recovery.sweepOnce().isEmpty(),
                    "the acknowledged first trigger must not replay after its former lease expires");
            assertEquals(2, tasks.inbox(requester(), HumanTaskQuery.everything(10)).items().size(),
                    "replaying the first continuation would register a third task");

            DurableHumanTask second = taskAt(tasks, "second");
            tasks.resolve(approver(), second.request().taskId(), second.generation(), response());
            assertTrue(recovery.sweepOnce().stream()
                    .anyMatch(RecoveryOutcome.HandlerDispatched.class::isInstance));
            assertEquals(ProcessInstanceStatus.COMPLETED,
                    store.load(key).toCompletableFuture().join().state().status());
        }
    }

    @Test
    void humanTaskContinuationCanHandOffToToolApprovalAndAcknowledgeItsTrigger(
            @TempDir Path directory) throws Exception {
        Path database = directory.resolve("human-to-tool.db");
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID originalTraversal = UUID.randomUUID();
        try (var store = new SqliteExecutionStore(database, CLOCK);
             var definitions = new SqliteGraphDefinitionStore(database, CLOCK,
                     GraphDefinitionReferences.NONE);
             var engine = new SameThreadExecutionEngine()) {
            CanonicalGraphMl canonical = CanonicalGraphMl.of(HUMAN_TO_TOOL_GRAPH);
            var storedDefinition = definitions.put(TENANT,
                    GraphDefinitionIdentity.forSubmission(canonical.contentId()), canonical)
                    .toCompletableFuture().join();
            String pin = storedDefinition.key().contentId().value();
            long revision = createRunning(store, key, originalTraversal, pin);
            var tasks = new HumanTaskService(store, CLOCK);
            var approvals = new ToolApprovalService(store, CLOCK);
            var services = ManagedNodePackageServices.builder("test.human-to-tool",
                            NodePackageEgressPolicy.builder().build(),
                            (packageId, tenantId, reference) -> java.util.Optional.empty())
                    .grant(NodePackageCapability.TOOL_AUTHORIZATION)
                    .toolAuthorization(ignored -> new ToolDecision(
                                    ToolDecision.Disposition.REQUIRE_APPROVAL,
                                    "approval required", "policy-v1"),
                            ToolCallAuditSink.discarding())
                    .durableToolApprovals(approvals, new ToolApprovalSettings("policy-v1",
                            Duration.ofMinutes(5), HandlerAuthorization.ofRoles(Role.APPROVER.name()), false))
                    .build();
            BehaviorRegistry behaviors = NodePackages.register(standard(tasks), new HumanToToolPackage(),
                    NodePackageServiceRegistry.builder().grant("test.human-to-tool", services).build());

            try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(HUMAN_TO_TOOL_GRAPH));
                 var runner = new GraphRunner(manager, snapshot(storedDefinition.identity(), manager),
                         engine, behaviors, new ExecutionMonitor(), ExecutionIdentitySource.randomUuids(),
                         GraphRunner.DEFAULT_SHUTDOWN_BOUND);
                 var recorder = ExecutionRecorder.open(store, key, "live-worker", TTL, revision);
                 var binding = tasks.bindLive(key, recorder, runner::continuationBudget)) {
                assertThrows(ExecutionException.class,
                        () -> runner.execute(requesterIdentity(), key.processInstanceId(), originalTraversal,
                                null, pin, null, null, recorder).toCompletableFuture()
                                .get(10, TimeUnit.SECONDS));
            }

            DurableHumanTask task = onlyTask(tasks);
            tasks.resolve(approver(), task.request().taskId(), task.generation(), response());
            var continuation = new PinnedGraphHumanTaskContinuationExecutor(definitions, store, tasks,
                    approvals, engine, behaviors, new ExecutionMonitor(),
                    ExecutionIdentitySource.randomUuids(), "recovery-worker", TTL);
            var recovery = new ExecutionRecoveryService(store, List.of(TENANT), "recovery-worker",
                    10, TTL, RepeatabilityDeclarations.NONE_DECLARED,
                    new HumanTaskHandlerDispatcher(store, tasks, continuation));

            List<RecoveryOutcome> outcomes = recovery.sweepOnce();
            assertTrue(outcomes.stream().anyMatch(RecoveryOutcome.HandlerDispatched.class::isInstance),
                    outcomes::toString);
            assertEquals(1, store.toolApprovals(key).toCompletableFuture().join().size());
            assertEquals(ai.ravenroot.api.persistence.ToolApprovalStatus.PENDING,
                    store.toolApprovals(key).toCompletableFuture().join().getFirst().status());
            assertTrue(store.claimPendingWork(TENANT, "probe", 10, TTL)
                    .toCompletableFuture().join().isEmpty(),
                    "the acknowledged human-task trigger must not replay while the tool approval waits");
            assertTrue(store.leases(TENANT).toCompletableFuture().join().isEmpty());
        }
    }

    private static BehaviorRegistry terminalRaceBehaviors(HumanTaskService tasks,
                                                           CountDownLatch terminalized,
                                                           CountDownLatch releaseSignal,
                                                           AtomicInteger raceExecutions,
                                                           AtomicInteger captures) {
        return standard(tasks)
                .register("terminal-race-human-task", message -> {
                    raceExecutions.incrementAndGet();
                    HumanTaskResult created = tasks.suspend(message, terminalRaceDefinition());
                    if (created.code() != HumanTaskResult.Code.CREATED) {
                        return CompletableFuture.failedFuture(new IllegalStateException(
                                "race task was not created: " + created.code()));
                    }
                    HumanTaskResult resolved = tasks.resolve(approver(),
                            created.task().request().taskId(), created.task().generation(), response());
                    if (resolved.code() != HumanTaskResult.Code.RESOLVED) {
                        return CompletableFuture.failedFuture(new IllegalStateException(
                                "race task was not resolved: " + resolved.code()));
                    }
                    terminalized.countDown();
                    try {
                        if (!releaseSignal.await(10, TimeUnit.SECONDS)) {
                            return CompletableFuture.failedFuture(new IllegalStateException(
                                    "test did not release terminal suspension signal"));
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return CompletableFuture.failedFuture(interrupted);
                    }
                    return CompletableFuture.failedFuture(new DurableHumanTaskSuspension(
                            created.task().request().taskId()));
                })
                .register("capture", message -> {
                    captures.incrementAndGet();
                    return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
                });
    }

    private static HumanTaskDefinition terminalRaceDefinition() {
        return new HumanTaskDefinition(
                new HumanTaskMetadata("Terminal suspension race", "Bounded test metadata."),
                new HumanTaskResponseSchema("application/vnd.ravenroot.payload+json",
                        "release.decision", "1", ai.ravenroot.api.payload.PayloadKind.MAP, 4096),
                HandlerAuthorization.ofRoles(Role.APPROVER.name()), java.util.Optional.empty(),
                Duration.ofHours(1),
                new HumanTaskReentryMapping("resolved", "denied", "expired", "cancelled"));
    }

    private static long dispatched(List<RecoveryOutcome> outcomes) {
        return outcomes.stream().filter(RecoveryOutcome.HandlerDispatched.class::isInstance).count();
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

    private static GraphExecutionLimits limitsWithTraversalSteps(long maximum) {
        GraphExecutionLimits defaults = GraphExecutionLimits.DEFAULTS;
        return new GraphExecutionLimits(defaults.graphMl(), defaults.payload(), defaults.maxFanOut(),
                defaults.maxResidentActors(), defaults.maxLiveActorsPerTraversal(),
                defaults.maxInFlightHopsPerTraversal(), defaults.maxQueuedAdmissionsPerNode(), maximum,
                defaults.maxAmplifiedDeliveries(), defaults.maxCumulativePayloadBytes(),
                defaults.maxRecoveryDeliveriesPerAttempt());
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
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId,
                        TraversalStatus.RUNNING)).build()).toCompletableFuture().join().revision();
    }

    private static DurableHumanTask onlyTask(HumanTaskService tasks) {
        return tasks.inbox(requester(), HumanTaskQuery.everything(10)).items().getFirst();
    }

    private static DurableHumanTask taskAt(HumanTaskService tasks, String nodeId) {
        return tasks.inbox(requester(), HumanTaskQuery.everything(10)).items().stream()
                .filter(task -> task.request().nodeId().equals(nodeId))
                .findFirst().orElseThrow();
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

    private static final class HumanToToolPackage implements NodePackage {
        @Override public String id() { return "test.human-to-tool"; }
        @Override public String version() { return "1"; }
        @Override public String sdkContract() { return NodeSdk.CONTRACT; }
        @Override public List<NodeBehavior> behaviors() { return List.of(new HumanToToolBehavior()); }
    }

    private static final class HumanToToolBehavior implements NodeBehavior {
        @Override public Set<NodePackageCapability> requiredServices() {
            return Set.of(NodePackageCapability.TOOL_AUTHORIZATION);
        }
        @Override public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("approval-request", "Approval request", "Test", "",
                    "actor", false, List.of(), Set.of());
        }
        @Override public NodeAction create(NodeConfiguration configuration) {
            return message -> java.util.concurrent.CompletableFuture.completedFuture(
                    NodeResult.continueWith(message.payload()));
        }
        @Override public NodeAction create(NodeConfiguration configuration, NodePackageServices services) {
            return message -> java.util.concurrent.CompletableFuture.failedFuture(
                    services.toolAuthorization().authorize(message, "filesystem.read",
                                    "{}".getBytes(StandardCharsets.UTF_8))
                            .suspend(1, "checkpoint".getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
