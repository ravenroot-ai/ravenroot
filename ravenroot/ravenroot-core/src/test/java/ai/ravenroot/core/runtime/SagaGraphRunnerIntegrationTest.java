package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.SagaDisposition;
import ai.ravenroot.api.persistence.SagaRecoveryEnvelope;
import ai.ravenroot.api.persistence.SagaStepStatus;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.node.service.SagaCommandTransport;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.RetryClassified;
import ai.ravenroot.api.persistence.Retryability;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SagaGraphRunnerIntegrationTest {
    @Test
    void atomicOutboxCapacityRefusalTerminatesWithoutPartialSagaOrInlinePublish(@TempDir Path directory) {
        String previous = System.getProperty(
                ai.ravenroot.api.persistence.SagaOutboxCapacity.COMMANDS_PROPERTY);
        System.setProperty(ai.ravenroot.api.persistence.SagaOutboxCapacity.COMMANDS_PROPERTY, "1");
        try {
            var inlinePublishes = new java.util.concurrent.atomic.AtomicInteger();
            var publish = new GraphNode("publish", NodeKind.BEHAVIOR, "amqp.publish", Map.of(
                    "saga.scope", "order", "saga.step", "created", "saga.participant", "amqp-inbox-v1",
                    "saga.adapter", "ravenroot.amqp-inbox.v1", "saga.inboxBinding", "orders-v1",
                    "persistent", true, "saga.businessCompletionRequired", true,
                    "saga.commandType", "order.created", "saga.irreversible", true));
            var graph = new GraphDefinition(List.of(GraphNode.start("start"), publish, GraphNode.end("end")),
                    List.of(GraphEdge.to("start", "publish"), GraphEdge.to("publish", "end")));
            var registry = new BehaviorRegistry().registerFactory(new NodeBehaviorFactory() {
                @Override public NodeTypeDescriptor descriptor() {
                    return new NodeTypeDescriptor("amqp.publish", "AMQP", "Test", "Trusted AMQP probe",
                            "actor", false, List.of(), java.util.Set.of(
                            "side-effect", "saga-adapter:ravenroot.amqp-inbox.v1"));
                }
                @Override public NodeHandler create(GraphNode ignored) {
                    return message -> {
                        inlinePublishes.incrementAndGet();
                        return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
                    };
                }
            });
            try (var store = new SqliteExecutionStore(directory.resolve("capacity.db"), Clock.systemUTC());
                 var engine = new JoinTestEngine(); var manager = GraphManager.from(graph);
                 var runner = new GraphRunner(manager, engine, registry, new ExecutionMonitor())) {
                UUID firstProcess = UUID.randomUUID(), firstTraversal = UUID.randomUUID();
                var firstKey = new ExecutionKey("tenant-a", firstProcess);
                long firstRevision = createRunning(store, firstKey, firstTraversal);
                try (var recorder = ExecutionRecorder.open(store, firstKey, "runner-1",
                        Duration.ofSeconds(30), firstRevision)) {
                    runner.execute(TestIdentities.of("tenant-a", "alice"), firstProcess, firstTraversal,
                            Map.of("order", "A-1"), "graph-v1", null, null, recorder)
                            .toCompletableFuture().join();
                }
                assertEquals(ProcessInstanceStatus.WAITING,
                        store.load(firstKey).toCompletableFuture().join().state().status());
                assertEquals(1, store.listSagaCommands(firstKey).toCompletableFuture().join().size());

                UUID refusedProcess = UUID.randomUUID(), refusedTraversal = UUID.randomUUID();
                var refusedKey = new ExecutionKey("tenant-a", refusedProcess);
                long refusedRevision = createRunning(store, refusedKey, refusedTraversal);
                try (var recorder = ExecutionRecorder.open(store, refusedKey, "runner-2",
                        Duration.ofSeconds(30), refusedRevision)) {
                    var failure = assertThrows(CompletionException.class, () -> runner.execute(
                            TestIdentities.of("tenant-a", "alice"), refusedProcess, refusedTraversal,
                            Map.of("order", "A-2"), "graph-v1", null, null, recorder)
                            .toCompletableFuture().join());
                    org.junit.jupiter.api.Assertions.assertTrue(
                            String.valueOf(failure.getCause().getMessage()).contains(
                                    "saga outbox tenant capacity exceeded"));
                }
                assertEquals(ProcessInstanceStatus.FAILED,
                        store.load(refusedKey).toCompletableFuture().join().state().status());
                assertEquals(List.of(), store.listSagas(refusedKey).toCompletableFuture().join());
                assertEquals(List.of(), store.listSagaCommands(refusedKey).toCompletableFuture().join());
                assertEquals(0, inlinePublishes.get());
            }
        } finally {
            if (previous == null) {
                System.clearProperty(ai.ravenroot.api.persistence.SagaOutboxCapacity.COMMANDS_PROPERTY);
            } else {
                System.setProperty(ai.ravenroot.api.persistence.SagaOutboxCapacity.COMMANDS_PROPERTY, previous);
            }
        }
    }

    @Test
    void runnerPersistsIntentBeforeRealHandlerAndRecordsItsObservedOutcome(@TempDir Path directory) {
        var graph = new GraphDefinition(List.of(GraphNode.start("start"),
                new GraphNode("effect", NodeKind.BEHAVIOR, "effect", Map.of(
                        "saga.scope", "order", "saga.step", "reserve",
                        "saga.participant", "pure")), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "effect"), GraphEdge.to("effect", "end")));
        var seenOperation = new AtomicReference<String>();
        NodeHandler handler = message -> {
            // The handler is the real registered behavior. These values can only be present if the
            // runner persisted and enriched the delivery before invoking it.
            seenOperation.set(String.valueOf(message.attributes().get("sagaOperationId")));
            assertNotNull(message.attributes().get("sagaPayloadFingerprint"));
            return CompletableFuture.completedFuture(ai.ravenroot.api.execution.NodeResult.continueWith(
                    message.payload()));
        };
        var registry = new BehaviorRegistry().registerFactory(new NodeBehaviorFactory() {
            @Override public NodeTypeDescriptor descriptor() {
                return new NodeTypeDescriptor("effect", "Pure effect probe", "Test", "Test probe",
                        "actor", false, List.of(), java.util.Set.of("saga-pure"));
            }
            @Override public NodeHandler create(GraphNode ignored) { return handler; }
        });
        UUID process = UUID.randomUUID(), traversal = UUID.randomUUID();
        var key = new ExecutionKey("tenant-a", process);
        try (var store = new SqliteExecutionStore(directory.resolve("runtime.db"), Clock.systemUTC());
             var engine = new JoinTestEngine(); var manager = GraphManager.from(graph)) {
        long revision = createRunning(store, key, traversal);
        try (
             var runner = new GraphRunner(manager, engine, registry, new ExecutionMonitor());
             var recorder = ExecutionRecorder.open(store, key, "worker", Duration.ofSeconds(30), revision)) {
            runner.execute(TestIdentities.of("tenant-a", "alice"), process, traversal,
                    Map.of("order", "A-1"), "graph-v1", null, null, recorder)
                    .toCompletableFuture().join();
        }
        assertNotNull(seenOperation.get());
        var saga = store.listSagas(key).toCompletableFuture().join().getFirst();
        assertEquals(SagaDisposition.SUCCEEDED, saga.disposition());
        assertEquals(SagaStepStatus.CONFIRMED_SUCCESS,
                saga.occurrences().values().iterator().next().status());
        }
    }

    @Test
    void authoredPureStringCannotUpgradeAnUntrustedEffectHandler() {
        var graph = new GraphDefinition(List.of(GraphNode.start("start"),
                new GraphNode("effect", NodeKind.BEHAVIOR, "effect", Map.of(
                        "saga.scope", "order", "saga.step", "reserve",
                        "saga.participant", "pure")), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "effect"), GraphEdge.to("effect", "end")));
        var registry = new BehaviorRegistry().register("effect", message ->
                CompletableFuture.completedFuture(ai.ravenroot.api.execution.NodeResult.continueWith(
                        message.payload())));
        try (var engine = new JoinTestEngine(); var manager = GraphManager.from(graph)) {
            assertThrows(IllegalArgumentException.class,
                    () -> new GraphRunner(manager, engine, registry, new ExecutionMonitor()));
        }
    }

    @Test
    void authoredParticipantStringsCannotUpgradeOrdinaryJdbcOrAmqpAdapters() {
        assertUntrustedParticipantRejected("jdbc.insert", "jdbc-receipt-v1", Map.of(
                "saga.adapter", "ravenroot.jdbc-receipt.v1",
                "saga.receiptStatement", "lookup-effect"));
        assertUntrustedParticipantRejected("amqp.publish", "amqp-inbox-v1", Map.of(
                "saga.adapter", "ravenroot.amqp-inbox.v1", "saga.inboxBinding", "orders-v1",
                "saga.businessCompletionRequired", true, "persistent", true));
    }

    @Test
    void parallelEffectsMustBothFinishBeforeJoinCanCompleteTheSaga(@TempDir Path directory) {
        var graph = new GraphDefinition(List.of(GraphNode.start("start"),
                sagaNode("left", "left"), sagaNode("right", "right"),
                new GraphNode("join", NodeKind.BEHAVIOR, "join", Map.of()), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "left"), GraphEdge.to("start", "right"),
                        GraphEdge.to("left", "join"), GraphEdge.to("right", "join"),
                        GraphEdge.to("join", "end")));
        var registry = pureRegistry("effect", message -> CompletableFuture.completedFuture(
                ai.ravenroot.api.execution.NodeResult.continueWith(message.payload())))
                .registerFactory(pureFactory("join", message -> CompletableFuture.completedFuture(
                        ai.ravenroot.api.execution.NodeResult.continueWith(message.payload()))));
        UUID process = UUID.randomUUID(), traversal = UUID.randomUUID();
        var key = new ExecutionKey("tenant-a", process);
        try (var store = new SqliteExecutionStore(directory.resolve("parallel.db"), Clock.systemUTC());
             var engine = new JoinTestEngine(); var manager = GraphManager.from(graph)) {
        long revision = createRunning(store, key, traversal);
        try (
             var runner = new GraphRunner(manager, engine, registry, new ExecutionMonitor());
             var recorder = ExecutionRecorder.open(store, key, "worker", Duration.ofSeconds(30), revision)) {
            runner.execute(TestIdentities.of("tenant-a", "alice"), process, traversal,
                    Map.of("order", "A-1"), "graph-v1", null, null, recorder)
                    .toCompletableFuture().join();
        }
        var saga = store.listSagas(key).toCompletableFuture().join().getFirst();
        assertEquals(SagaDisposition.SUCCEEDED, saga.disposition());
        assertEquals(2, saga.occurrences().size());
        }
    }

    @Test
    void asynchronousBusinessCompletionGatesSuccessWithoutTurningTheWaitIntoFailure(@TempDir Path directory)
            throws Exception {
        var inlinePublishes = new java.util.concurrent.atomic.AtomicInteger();
        var publish = new GraphNode("publish", NodeKind.BEHAVIOR, "amqp.publish", Map.of(
                "saga.scope", "order", "saga.step", "created", "saga.participant", "amqp-inbox-v1",
                "saga.adapter", "ravenroot.amqp-inbox.v1", "saga.inboxBinding", "orders-v1",
                "persistent", true, "saga.businessCompletionRequired", true, "saga.commandType", "order.created",
                "saga.irreversible", true));
        var graph = new GraphDefinition(List.of(GraphNode.start("start"), publish, GraphNode.end("end")),
                List.of(GraphEdge.to("start", "publish"), GraphEdge.to("publish", "end")));
        var registry = new BehaviorRegistry().registerFactory(new NodeBehaviorFactory() {
            @Override public NodeTypeDescriptor descriptor() {
                return new NodeTypeDescriptor("amqp.publish", "AMQP", "Test", "Test AMQP",
                        "actor", false, List.of(), java.util.Set.of("side-effect", "saga-adapter:ravenroot.amqp-inbox.v1"));
            }
            @Override public NodeHandler create(GraphNode ignored) {
                return message -> {
                    inlinePublishes.incrementAndGet();
                    return CompletableFuture.completedFuture(new NodeResult("continue",
                            Map.of("status", "CONFIRMED"), message.attributes()));
                };
            }
        });
        UUID process = UUID.randomUUID(), traversal = UUID.randomUUID();
        var key = new ExecutionKey("tenant-a", process);
        try (var store = new SqliteExecutionStore(directory.resolve("async.db"), Clock.systemUTC());
             var engine = new JoinTestEngine(); var manager = GraphManager.from(graph)) {
            long revision = createRunning(store, key, traversal);
            try (var runner = new GraphRunner(manager, engine, registry, new ExecutionMonitor());
                 var recorder = ExecutionRecorder.open(store, key, "runner", Duration.ofSeconds(30), revision)) {
                var execution = runner.execute(TestIdentities.of("tenant-a", "alice"), process, traversal,
                        Map.of("order", "A-1"), "graph-v1", null, null, recorder).toCompletableFuture();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (store.listSagaCommands(key).toCompletableFuture().join().isEmpty()
                        && System.nanoTime() < until) Thread.sleep(10);
                var transport = new SagaCommandTransport() {
                    @Override public CompletableFuture<BrokerResult> publish(
                            ai.ravenroot.api.persistence.SagaCommandIntent ignored) {
                        return CompletableFuture.completedFuture(new BrokerResult(true, "accepted"));
                    }
                    @Override public CompletableFuture<Boolean> businessCompleted(
                            ai.ravenroot.api.persistence.SagaCommandIntent ignored) {
                        return CompletableFuture.completedFuture(true);
                    }
                };
                var publisher = new SagaOutboxPublisher(store, transport,
                        (ignoredKey, ignoredIntent) -> { }, "publisher", 4,
                        Duration.ofSeconds(5), Duration.ofMillis(10));
                publisher.runOnce("tenant-a");
                Thread.sleep(1100);
                publisher.runOnce("tenant-a");
                execution.get(5, TimeUnit.SECONDS);
            }
            var stored = store.load(key).toCompletableFuture().join();
            assertEquals(ProcessInstanceStatus.COMPLETED, stored.state().status());
            var saga = store.listSagas(key).toCompletableFuture().join().getFirst();
            assertEquals(true, saga.graphCompleted());
            assertEquals(SagaDisposition.SUCCEEDED, saga.disposition());
            assertEquals(0, inlinePublishes.get(), "the durable outbox is the AMQP delivery authority");
        }
    }

    @Test
    void businessCompletionPastInlineBoundBecomesRestartSafeWaitingInsteadOfFailure(@TempDir Path directory) {
        var publish = new GraphNode("publish", NodeKind.BEHAVIOR, "amqp.publish", Map.of(
                "saga.scope", "order", "saga.step", "created", "saga.participant", "amqp-inbox-v1",
                "saga.adapter", "ravenroot.amqp-inbox.v1", "saga.inboxBinding", "orders-v1",
                "persistent", true, "saga.businessCompletionRequired", true, "saga.commandType", "order.created",
                "saga.irreversible", true));
        var graph = new GraphDefinition(List.of(GraphNode.start("start"), publish, GraphNode.end("end")),
                List.of(GraphEdge.to("start", "publish"), GraphEdge.to("publish", "end")));
        var registry = new BehaviorRegistry().registerFactory(new NodeBehaviorFactory() {
            @Override public NodeTypeDescriptor descriptor() {
                return new NodeTypeDescriptor("amqp.publish", "AMQP", "Test", "Test AMQP",
                        "actor", false, List.of(), java.util.Set.of("side-effect", "saga-adapter:ravenroot.amqp-inbox.v1"));
            }
            @Override public NodeHandler create(GraphNode ignored) {
                return message -> CompletableFuture.completedFuture(new NodeResult("continue",
                        Map.of("status", "CONFIRMED"), message.attributes()));
            }
        });
        UUID process = UUID.randomUUID(), traversal = UUID.randomUUID();
        var key = new ExecutionKey("tenant-a", process);
        try (var store = new SqliteExecutionStore(directory.resolve("waiting.db"), Clock.systemUTC());
             var engine = new JoinTestEngine(); var manager = GraphManager.from(graph)) {
            long revision = createRunning(store, key, traversal);
            try (var runner = new GraphRunner(manager, engine, registry, new ExecutionMonitor());
                 var recorder = ExecutionRecorder.open(store, key, "runner", Duration.ofSeconds(30), revision)) {
                runner.execute(TestIdentities.of("tenant-a", "alice"), process, traversal,
                        Map.of("order", "A-1"), "graph-v1", null, null, recorder)
                        .toCompletableFuture().join();
            }
            var stored = store.load(key).toCompletableFuture().join();
            assertEquals(ProcessInstanceStatus.WAITING, stored.state().status());
            assertEquals(TraversalStatus.WAITING, stored.state().traversals().get(traversal).status());
            var saga = store.listSagas(key).toCompletableFuture().join().getFirst();
            assertEquals(true, saga.graphCompleted());
            assertEquals(SagaDisposition.RUNNING, saga.disposition());
        }
    }

    @Test
    void traversalCannotReportSuccessWhenADeclaredSagaBranchNeverRan(@TempDir Path directory) {
        var graph = new GraphDefinition(List.of(GraphNode.start("start"), sagaNode("left", "left"),
                sagaNode("unreached", "right"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "left"), GraphEdge.to("left", "end")));
        var registry = pureRegistry("effect", message -> CompletableFuture.completedFuture(
                ai.ravenroot.api.execution.NodeResult.continueWith(message.payload())));
        UUID process = UUID.randomUUID(), traversal = UUID.randomUUID();
        var key = new ExecutionKey("tenant-a", process);
        try (var store = new SqliteExecutionStore(directory.resolve("incomplete.db"), Clock.systemUTC());
             var engine = new JoinTestEngine(); var manager = GraphManager.from(graph)) {
        long revision = createRunning(store, key, traversal);
        try (
             var runner = new GraphRunner(manager, engine, registry, new ExecutionMonitor());
             var recorder = ExecutionRecorder.open(store, key, "worker", Duration.ofSeconds(30), revision)) {
            assertThrows(CompletionException.class, () -> runner.execute(TestIdentities.of("tenant-a", "alice"),
                    process, traversal, Map.of("order", "A-1"), "graph-v1", null, null, recorder)
                    .toCompletableFuture().join());
        }
        assertEquals(SagaDisposition.COMPENSATED,
                store.listSagas(key).toCompletableFuture().join().getFirst().disposition());
        }
    }

    @Test
    void participantCompletingAfterCancellationRemainsCompensationPending(@TempDir Path directory)
            throws Exception {
        var graph = new GraphDefinition(List.of(GraphNode.start("start"), sagaNode("effect", "reserve"),
                GraphNode.end("end")), List.of(GraphEdge.to("start", "effect"), GraphEdge.to("effect", "end")));
        var invoked = new CountDownLatch(1);
        var participant = new CompletableFuture<ai.ravenroot.api.execution.NodeResult>();
        var registry = pureRegistry("effect", message -> {
            invoked.countDown();
            return participant;
        });
        UUID process = UUID.randomUUID(), traversal = UUID.randomUUID();
        var key = new ExecutionKey("tenant-a", process);
        try (var store = new SqliteExecutionStore(directory.resolve("late.db"), Clock.systemUTC());
             var engine = new JoinTestEngine(); var manager = GraphManager.from(graph)) {
            long revision = createRunning(store, key, traversal);
            try (var runner = new GraphRunner(manager, engine, registry, new ExecutionMonitor());
                 var recorder = ExecutionRecorder.open(store, key, "worker", Duration.ofSeconds(30), revision)) {
                var execution = runner.execute(TestIdentities.of("tenant-a", "alice"), process, traversal,
                        Map.of("order", "A-1"), "graph-v1", null, null, recorder).toCompletableFuture();
                assertEquals(true, invoked.await(5, TimeUnit.SECONDS));
                assertEquals(true, runner.cancelTraversal(traversal));
                participant.complete(ai.ravenroot.api.execution.NodeResult.continueWith(Map.of("reserved", true)));
                assertThrows(CompletionException.class, execution::join);
            }
            var saga = store.listSagas(key).toCompletableFuture().join().getFirst();
            assertEquals(true, saga.cancellationRequested());
            assertEquals(SagaDisposition.COMPENSATION_PENDING, saga.disposition());
            assertEquals(SagaStepStatus.CONFIRMED_SUCCESS,
                    saga.occurrences().values().iterator().next().status());
        }
    }

    @Test
    void successfulParticipantIsCompensatedWhenAParallelSiblingFailsLater(@TempDir Path directory) {
        var node = new GraphNode("effect", NodeKind.BEHAVIOR, "http-request", Map.of(
                "saga.scope", "order", "saga.step", "reserve",
                "saga.participant", "http-idempotency-v1",
                "saga.adapter", "ravenroot.http-idempotency.v1",
                "saga.outcomeLookupUrl", "https://participant.test/operations/{{attributes.sagaOperationId}}",
                "saga.compensation", "undo"));
        var undo = new GraphNode("undo", NodeKind.BEHAVIOR, "http-request", Map.of(
                "saga.scope", "order", "saga.role", "compensation",
                "saga.participant", "http-idempotency-v1",
                "saga.adapter", "ravenroot.http-idempotency.v1",
                "saga.outcomeLookupUrl", "https://participant.test/operations/{{attributes.sagaOperationId}}"));
        var graph = new GraphDefinition(List.of(GraphNode.start("start"), node, undo, GraphNode.end("end")),
                List.of(GraphEdge.to("start", "effect"), GraphEdge.to("effect", "end")));
        var registry = new BehaviorRegistry().registerFactory(new NodeBehaviorFactory() {
            @Override public NodeTypeDescriptor descriptor() {
                return new NodeTypeDescriptor("http-request", "HTTP", "Test", "Trusted HTTP probe",
                        "actor", false, List.of(), java.util.Set.of(
                        "side-effect", "saga-adapter:ravenroot.http-idempotency.v1"));
            }
            @Override public NodeHandler create(GraphNode ignored) {
                return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
            }
        });
        UUID process = UUID.randomUUID(), traversal = UUID.randomUUID();
        var key = new ExecutionKey("tenant-a", process);
        try (var store = new SqliteExecutionStore(directory.resolve("late-sibling.db"), Clock.systemUTC())) {
            long revision = createRunning(store, key, traversal);
            try (var recorder = ExecutionRecorder.open(store, key, "worker", Duration.ofSeconds(30), revision)) {
                var coordinator = new SagaCoordinator(graph, registry, Clock.systemUTC());
                var before = coordinator.before(node, message(process, traversal, UUID.randomUUID()), recorder);
                coordinator.succeeded(before, new NodeResult("continue", before.message().payload(),
                        Map.of("http.status", 200)), recorder);
                assertEquals(SagaDisposition.RUNNING,
                        store.listSagas(key).toCompletableFuture().join().getFirst().disposition());

                coordinator.prepareFailure(traversal, recorder);
            }
            var saga = store.listSagas(key).toCompletableFuture().join().getFirst();
            assertEquals(true, saga.cancellationRequested());
            assertEquals(true, saga.graphCompleted());
            assertEquals(SagaDisposition.COMPENSATION_PENDING, saga.disposition());
            assertEquals(SagaStepStatus.CONFIRMED_SUCCESS,
                    saga.occurrences().values().iterator().next().status());
        }
    }

    @Test
    void unknownOutcomeIsRedeliveredAfterRestartWithTheOriginalBusinessIdentity(@TempDir Path directory) {
        var node = sagaNode("effect", "reserve");
        var graph = new GraphDefinition(List.of(GraphNode.start("start"), node, GraphNode.end("end")),
                List.of(GraphEdge.to("start", "effect"), GraphEdge.to("effect", "end")));
        var registry = pureRegistry("effect", message -> CompletableFuture.completedFuture(
                NodeResult.continueWith(message.payload())));
        UUID process = UUID.randomUUID(), traversal = UUID.randomUUID();
        var key = new ExecutionKey("tenant-a", process);
        String firstOperation;
        try (var store = new SqliteExecutionStore(directory.resolve("restart.db"), Clock.systemUTC())) {
            long revision = createRunning(store, key, traversal);
            try (var recorder = ExecutionRecorder.open(store, key, "worker-before-crash",
                    Duration.ofSeconds(30), revision)) {
                var firstCoordinator = new SagaCoordinator(graph, registry, Clock.systemUTC());
                var first = firstCoordinator.before(node, message(process, traversal, UUID.randomUUID()), recorder);
                firstOperation = String.valueOf(first.message().attributes().get("sagaOperationId"));
                firstCoordinator.failed(first, new java.io.IOException("response lost"), recorder);
            }
            long resumedRevision = store.load(key).toCompletableFuture().join().revision();
            try (var recorder = ExecutionRecorder.open(store, key, "worker-after-restart",
                    Duration.ofSeconds(30), resumedRevision)) {
                var recoveredCoordinator = new SagaCoordinator(graph, registry, Clock.systemUTC());
                var recovered = recoveredCoordinator.before(node,
                        message(process, traversal, UUID.randomUUID()), recorder);
                assertEquals(firstOperation, recovered.message().attributes().get("sagaOperationId"));
                recoveredCoordinator.succeeded(recovered,
                        NodeResult.continueWith(recovered.message().payload()), recorder);
                recoveredCoordinator.prepareCompletion(traversal, recorder);
            }
            var saga = store.listSagas(key).toCompletableFuture().join().getFirst();
            assertEquals(SagaDisposition.SUCCEEDED, saga.disposition());
            assertEquals(1, saga.occurrences().size());
        }
    }

    @Test
    void restartAfterDurableDispatchIntentBeforeParticipantInvocationReusesTheFrozenOperation(
            @TempDir Path directory) {
        var node = sagaNode("effect", "reserve");
        var graph = new GraphDefinition(List.of(GraphNode.start("start"), node, GraphNode.end("end")),
                List.of(GraphEdge.to("start", "effect"), GraphEdge.to("effect", "end")));
        var registry = pureRegistry("effect", message -> CompletableFuture.completedFuture(
                NodeResult.continueWith(message.payload())));
        UUID process = UUID.randomUUID(), traversal = UUID.randomUUID();
        var key = new ExecutionKey("tenant-a", process);
        String frozenOperation;
        try (var store = new SqliteExecutionStore(directory.resolve("intent-before-dispatch.db"), Clock.systemUTC())) {
            long revision = createRunning(store, key, traversal);
            try (var recorder = ExecutionRecorder.open(store, key, "worker-before-crash",
                    Duration.ofSeconds(30), revision)) {
                var coordinator = new SagaCoordinator(graph, registry, Clock.systemUTC());
                var persisted = coordinator.before(node,
                        message(process, traversal, UUID.randomUUID()), recorder);
                frozenOperation = String.valueOf(persisted.message().attributes().get("sagaOperationId"));
                // Simulate process death here: the real participant handler has not been invoked.
            }
            var afterCrash = store.listSagas(key).toCompletableFuture().join().getFirst();
            assertEquals(SagaStepStatus.DISPATCHED,
                    afterCrash.occurrences().values().iterator().next().status());
            long resumedRevision = store.load(key).toCompletableFuture().join().revision();
            try (var recorder = ExecutionRecorder.open(store, key, "worker-after-restart",
                    Duration.ofSeconds(30), resumedRevision)) {
                var coordinator = new SagaCoordinator(graph, registry, Clock.systemUTC());
                var recovered = coordinator.before(node,
                        message(process, traversal, UUID.randomUUID()), recorder);
                assertEquals(frozenOperation, recovered.message().attributes().get("sagaOperationId"));
                coordinator.succeeded(recovered, NodeResult.continueWith(recovered.message().payload()), recorder);
                coordinator.prepareCompletion(traversal, recorder);
            }
            var completed = store.listSagas(key).toCompletableFuture().join().getFirst();
            assertEquals(SagaDisposition.SUCCEEDED, completed.disposition());
            assertEquals(1, completed.occurrences().size());
        }
    }

    @Test
    void frozenJdbcCompensationReplacesForwardOrAuthoredReceiptIdentity(@TempDir Path directory) {
        var forward = new GraphNode("write", NodeKind.BEHAVIOR, "jdbc.insert", Map.of(
                "saga.scope", "order", "saga.step", "order-write",
                "saga.participant", "jdbc-receipt-v1",
                "saga.adapter", "ravenroot.jdbc-receipt.v1",
                "saga.receiptStatement", "lookup-order-effect",
                "saga.compensation", "cancel"));
        var compensation = new GraphNode("cancel", NodeKind.BEHAVIOR, "jdbc.insert", Map.of(
                "saga.scope", "order", "saga.role", "compensation",
                "saga.participant", "jdbc-receipt-v1",
                "saga.adapter", "ravenroot.jdbc-receipt.v1",
                "saga.receiptStatement", "lookup-order-reversal"));
        var graph = new GraphDefinition(List.of(GraphNode.start("start"), forward, compensation,
                GraphNode.end("end")), List.of(GraphEdge.to("start", "write"),
                GraphEdge.to("write", "end")));
        var registry = new BehaviorRegistry().registerFactory(new NodeBehaviorFactory() {
            @Override public NodeTypeDescriptor descriptor() {
                return new NodeTypeDescriptor("jdbc.insert", "JDBC", "Test", "Trusted JDBC probe",
                        "actor", false, List.of(), java.util.Set.of(
                        "side-effect", "saga-adapter:ravenroot.jdbc-receipt.v1"));
            }
            @Override public NodeHandler create(GraphNode ignored) {
                return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
            }
        });
        UUID process = UUID.randomUUID(), traversal = UUID.randomUUID();
        var key = new ExecutionKey("tenant-a", process);
        var payload = Map.<String, Object>of("contract", "jdbc.parameters.v1", "parameters", Map.of(
                "orderId", "A-1", "sagaOperationId", "authored-operation",
                "sagaPayloadFingerprint", "0".repeat(64)));
        var original = new NodeMessage(TestIdentities.of("tenant-a", "alice"), process, traversal,
                UUID.randomUUID(), UUID.randomUUID(), "write", payload, Map.of());
        try (var store = new SqliteExecutionStore(directory.resolve("frozen-identities.db"), Clock.systemUTC())) {
            long revision = createRunning(store, key, traversal);
            try (var recorder = ExecutionRecorder.open(store, key, "worker", Duration.ofSeconds(30), revision)) {
                var coordinator = new SagaCoordinator(graph, registry, Clock.systemUTC());
                var before = coordinator.before(forward, original, recorder);
                var saga = store.listSagas(key).toCompletableFuture().join().getFirst();
                var step = saga.occurrences().values().iterator().next();
                var envelope = SagaRecoveryEnvelope.decode(step.receipt());
                Map<?, ?> forwardEnvelope = jsonMap(envelope.forward().payload().bytes());
                Map<?, ?> compensationEnvelope = jsonMap(envelope.compensation().payload().bytes());
                Map<?, ?> forwardParameters = jsonMap(jsonMap(forwardEnvelope.get("payload")).get("parameters"));
                Map<?, ?> compensationParameters = jsonMap(
                        jsonMap(compensationEnvelope.get("payload")).get("parameters"));

                assertEquals(envelope.forward().operationId(), forwardParameters.get("sagaOperationId"));
                assertEquals(step.payloadFingerprint(), forwardParameters.get("sagaPayloadFingerprint"));
                assertEquals(envelope.compensation().operationId(),
                        compensationParameters.get("sagaOperationId"));
                assertEquals(step.payloadFingerprint(),
                        compensationParameters.get("sagaPayloadFingerprint"));
                assertEquals(envelope.compensation().operationId(),
                        before.message().attributes().get("sagaCompensationOperationId"));
            }
        }
    }

    @Test
    void frozenAmqpOperationsReplaceAuthoredMessageIdentityAndKeepCausality(@TempDir Path directory) {
        var inlinePublishes = new java.util.concurrent.atomic.AtomicInteger();
        Map<String, Object> forwardProperties = new java.util.LinkedHashMap<>(Map.of(
                "saga.scope", "order", "saga.step", "publish",
                "saga.participant", "amqp-inbox-v1",
                "saga.adapter", "ravenroot.amqp-inbox.v1",
                "saga.inboxBinding", "orders-v1", "saga.businessCompletionRequired", true,
                "persistent", true, "saga.compensation", "cancel-publish"));
        var forward = new GraphNode("publish", NodeKind.BEHAVIOR, "amqp.publish", forwardProperties);
        var compensation = new GraphNode("cancel-publish", NodeKind.BEHAVIOR, "amqp.publish", Map.of(
                "saga.scope", "order", "saga.role", "compensation",
                "saga.participant", "amqp-inbox-v1",
                "saga.adapter", "ravenroot.amqp-inbox.v1",
                "saga.inboxBinding", "orders-v1", "saga.businessCompletionRequired", true,
                "persistent", true));
        var graph = new GraphDefinition(List.of(GraphNode.start("start"), forward, compensation,
                GraphNode.end("end")), List.of(GraphEdge.to("start", "publish"),
                GraphEdge.to("publish", "end")));
        var registry = new BehaviorRegistry().registerFactory(new NodeBehaviorFactory() {
            @Override public NodeTypeDescriptor descriptor() {
                return new NodeTypeDescriptor("amqp.publish", "AMQP", "Test", "Trusted AMQP probe",
                        "actor", false, List.of(), java.util.Set.of(
                        "side-effect", "saga-adapter:ravenroot.amqp-inbox.v1"));
            }
            @Override public NodeHandler create(GraphNode ignored) {
                return message -> {
                    inlinePublishes.incrementAndGet();
                    return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
                };
            }
        });
        UUID process = UUID.randomUUID(), traversal = UUID.randomUUID();
        var key = new ExecutionKey("tenant-a", process);
        var body = Map.<String, Object>of("orderId", "A-1", "operationId", "authored-operation",
                "payloadFingerprint", "0".repeat(64));
        var payload = Map.<String, Object>of("messageId", "authored-message", "bodyJson", body);
        var original = new NodeMessage(TestIdentities.of("tenant-a", "alice"), process, traversal,
                UUID.randomUUID(), UUID.randomUUID(), "publish", payload, Map.of());
        try (var store = new SqliteExecutionStore(directory.resolve("frozen-messages.db"), Clock.systemUTC())) {
            long revision = createRunning(store, key, traversal);
            try (var recorder = ExecutionRecorder.open(store, key, "worker", Duration.ofSeconds(30), revision)) {
                var coordinator = new SagaCoordinator(graph, registry, Clock.systemUTC());
                var queuedForward = coordinator.before(forward, original, recorder);
                assertNotNull(queuedForward.replay());
                var current = store.listSagas(key).toCompletableFuture().join().getFirst();
                var step = current
                        .occurrences().values().iterator().next();
                var envelope = SagaRecoveryEnvelope.decode(step.receipt());
                Map<?, ?> frozenForward = jsonMap(envelope.forward().payload().bytes());
                Map<?, ?> frozenCompensation = jsonMap(envelope.compensation().payload().bytes());
                Map<?, ?> forwardPayload = jsonMap(frozenForward.get("payload"));
                Map<?, ?> compensationPayload = jsonMap(frozenCompensation.get("payload"));
                Map<?, ?> forwardBody = jsonMap(forwardPayload.get("bodyJson"));
                Map<?, ?> compensationBody = jsonMap(compensationPayload.get("bodyJson"));

                assertEquals(envelope.forward().messageId().toString(), forwardPayload.get("messageId"));
                assertEquals(envelope.compensation().messageId().toString(),
                        compensationPayload.get("messageId"));
                assertEquals(envelope.forward().operationId(), forwardBody.get("operationId"));
                assertEquals(envelope.compensation().operationId(), compensationBody.get("operationId"));
                assertEquals(step.payloadFingerprint(), forwardBody.get("payloadFingerprint"));
                assertEquals(step.payloadFingerprint(), compensationBody.get("payloadFingerprint"));
                assertEquals(envelope.forward().messageId(), envelope.compensation().causalMessageId());

                var confirmed = new ai.ravenroot.api.persistence.SagaStepSnapshot(step.occurrenceId(),
                        step.stepId(), step.invocationId(), step.forwardOperationId(),
                        step.compensationOperationId(), step.payloadFingerprint(),
                        SagaStepStatus.CONFIRMED_SUCCESS, step.receipt(), "business receipt", Instant.now());
                var occurrences = new java.util.LinkedHashMap<>(current.occurrences());
                occurrences.put(confirmed.occurrenceId(), confirmed);
                var confirmedSnapshot = new ai.ravenroot.api.persistence.SagaSnapshot(current.key(),
                        current.sagaId(), current.traversalId(), current.definition(), current.revision() + 1,
                        SagaDisposition.RUNNING, false, occurrences, current.deadline(), current.createdAt(),
                        Instant.now(), "", false);
                recorder.recordSaga(List.of(new ai.ravenroot.api.persistence.SagaWrite(UUID.randomUUID(),
                        current.revision(), confirmedSnapshot)), List.of());
                var compensationMessage = new NodeMessage(original.security(), process, traversal,
                        UUID.randomUUID(), UUID.randomUUID(), "cancel-publish", payload, Map.of());
                var queuedCompensation = coordinator.before(compensation, compensationMessage, recorder);
                assertNotNull(queuedCompensation.replay());
                assertEquals(2, store.listSagaCommands(key).toCompletableFuture().join().size());
                assertEquals(0, inlinePublishes.get(), "forward and compensation use only the durable outbox");
            }
        }
    }

    @Test
    void liveCompensationUsesTheFrozenParticipantRequestAfterGraphPayloadMutation(@TempDir Path directory) {
        var forward = new GraphNode("write", NodeKind.BEHAVIOR, "jdbc.insert", Map.of(
                "saga.scope", "order", "saga.step", "order-write",
                "saga.participant", "jdbc-receipt-v1",
                "saga.adapter", "ravenroot.jdbc-receipt.v1",
                "saga.receiptStatement", "lookup-order-effect",
                "saga.compensation", "cancel"));
        var blocker = new GraphNode("blocker", NodeKind.BEHAVIOR, "jdbc.insert", Map.of(
                "saga.scope", "order", "saga.step", "blocker",
                "saga.participant", "jdbc-receipt-v1",
                "saga.adapter", "ravenroot.jdbc-receipt.v1",
                "saga.receiptStatement", "lookup-blocker", "saga.irreversible", true));
        var compensation = new GraphNode("cancel", NodeKind.BEHAVIOR, "jdbc.insert", Map.of(
                "saga.scope", "order", "saga.role", "compensation",
                "saga.participant", "jdbc-receipt-v1",
                "saga.adapter", "ravenroot.jdbc-receipt.v1",
                "saga.receiptStatement", "lookup-order-reversal"));
        var graph = new GraphDefinition(List.of(GraphNode.start("start"), forward, blocker, compensation,
                GraphNode.end("end")), List.of(GraphEdge.to("start", "write"),
                GraphEdge.to("write", "blocker"), GraphEdge.to("blocker", "end")));
        var registry = new BehaviorRegistry().registerFactory(new NodeBehaviorFactory() {
            @Override public NodeTypeDescriptor descriptor() {
                return new NodeTypeDescriptor("jdbc.insert", "JDBC", "Test", "Trusted JDBC probe",
                        "actor", false, List.of(), java.util.Set.of(
                        "side-effect", "saga-adapter:ravenroot.jdbc-receipt.v1"));
            }
            @Override public NodeHandler create(GraphNode ignored) {
                return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
            }
        });
        UUID process = UUID.randomUUID(), traversal = UUID.randomUUID();
        var key = new ExecutionKey("tenant-a", process);
        var originalPayload = Map.<String, Object>of("contract", "jdbc.parameters.v1", "parameters", Map.of(
                "orderId", "ORDER-A", "sku", "SKU-A", "quantity", 1));
        var original = new NodeMessage(TestIdentities.of("tenant-a", "alice"), process, traversal,
                UUID.randomUUID(), UUID.randomUUID(), "write", originalPayload,
                Map.of("businessTarget", "ORDER-A"));
        try (var store = new SqliteExecutionStore(directory.resolve("frozen-live-compensation.db"),
                Clock.systemUTC())) {
            long revision = createRunning(store, key, traversal);
            try (var recorder = ExecutionRecorder.open(store, key, "worker", Duration.ofSeconds(30), revision)) {
                var coordinator = new SagaCoordinator(graph, registry, Clock.systemUTC());
                var dispatched = coordinator.before(forward, original, recorder);
                coordinator.succeeded(dispatched, NodeResult.continueWith(dispatched.message().payload()), recorder);
                var blocked = coordinator.before(blocker, new NodeMessage(original.security(), process, traversal,
                        UUID.randomUUID(), UUID.randomUUID(), "blocker", originalPayload, original.attributes()),
                        recorder);
                coordinator.failed(blocked, new java.io.IOException("later participant outcome unknown"), recorder);
                coordinator.cancellationRequested(traversal, recorder);

                var step = store.listSagas(key).toCompletableFuture().join().getFirst().occurrences().values()
                        .stream().filter(value -> value.stepId().equals("order-write")).findFirst().orElseThrow();
                var frozenIntent = SagaRecoveryEnvelope.decode(step.receipt()).compensation();
                Map<?, ?> envelope = jsonMap(frozenIntent.payload().bytes());
                var changedPayload = Map.<String, Object>of("contract", "jdbc.parameters.v1", "parameters", Map.of(
                        "orderId", "ORDER-B", "sku", "SKU-B", "quantity", 99));
                var currentAuthority = TestIdentities.of("tenant-a", "recovery-worker");
                var changed = new NodeMessage(currentAuthority, process, traversal, UUID.randomUUID(),
                        UUID.randomUUID(), "cancel", changedPayload, Map.of("businessTarget", "ORDER-B"));

                var live = coordinator.before(compensation, changed, recorder);
                assertEquals(envelope.get("payload"), live.message().payload());
                assertEquals(envelope.get("attributes"), live.message().attributes());
                assertEquals(currentAuthority, live.message().security());
                assertEquals("ORDER-A", jsonMap(live.message().payload()).get("parameters") instanceof Map<?, ?> p
                        ? p.get("orderId") : null);
                assertEquals("ORDER-A", live.message().attributes().get("businessTarget"));
                assertEquals(frozenIntent.operationId(), live.message().attributes().get("sagaOperationId"));
            }
        }
    }

    @Test
    void compensationNodeMustMatchTheTrustedParticipantAdapter() {
        var forward = new GraphNode("effect", NodeKind.BEHAVIOR, "effect", Map.of(
                "saga.scope", "order", "saga.step", "reserve", "saga.participant", "pure",
                "saga.compensation", "undo"));
        var compensation = new GraphNode("undo", NodeKind.BEHAVIOR, "untrusted", Map.of(
                "saga.scope", "order", "saga.role", "compensation", "saga.participant", "pure"));
        var graph = new GraphDefinition(List.of(GraphNode.start("start"), forward, compensation,
                GraphNode.end("end")), List.of(GraphEdge.to("start", "effect"),
                GraphEdge.to("effect", "end")));
        var registry = pureRegistry("effect", message -> CompletableFuture.completedFuture(
                NodeResult.continueWith(message.payload()))).register("untrusted", message ->
                CompletableFuture.completedFuture(NodeResult.continueWith(message.payload())));

        assertThrows(IllegalArgumentException.class,
                () -> new SagaCoordinator(graph, registry, Clock.systemUTC()));
    }

    @Test
    void compensationNodeMustDeclareTheSameParticipantContractAsItsForwardStep() {
        var forward = new GraphNode("effect", NodeKind.BEHAVIOR, "effect", Map.of(
                "saga.scope", "order", "saga.step", "reserve", "saga.participant", "pure",
                "saga.compensation", "undo"));
        var missing = new GraphNode("undo", NodeKind.BEHAVIOR, "effect", Map.of(
                "saga.scope", "order", "saga.role", "compensation"));
        var mismatched = new GraphNode("undo", NodeKind.BEHAVIOR, "effect", Map.of(
                "saga.scope", "order", "saga.role", "compensation",
                "saga.participant", "http-idempotency-v1"));
        var registry = pureRegistry("effect", message -> CompletableFuture.completedFuture(
                NodeResult.continueWith(message.payload())));

        for (GraphNode compensation : List.of(missing, mismatched)) {
            var graph = new GraphDefinition(List.of(GraphNode.start("start"), forward, compensation,
                    GraphNode.end("end")), List.of(GraphEdge.to("start", "effect"),
                    GraphEdge.to("effect", "end")));
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> new SagaCoordinator(graph, registry, Clock.systemUTC()));
            assertEquals("Invalid saga configuration at node 'undo': "
                            + "compensation participant contract must equal 'pure'",
                    failure.getMessage());
        }
    }

    @Test
    void expiredFrozenDeadlinePreventsANewEffectAfterRestart(@TempDir Path directory) {
        var node = new GraphNode("effect", NodeKind.BEHAVIOR, "effect", Map.of(
                "saga.scope", "order", "saga.step", "reserve", "saga.participant", "pure",
                "saga.deadlineMs", 1000L));
        var graph = new GraphDefinition(List.of(GraphNode.start("start"), node, GraphNode.end("end")),
                List.of(GraphEdge.to("start", "effect"), GraphEdge.to("effect", "end")));
        var registry = pureRegistry("effect", message -> CompletableFuture.completedFuture(
                NodeResult.continueWith(message.payload())));
        UUID process = UUID.randomUUID(), traversal = UUID.randomUUID();
        var key = new ExecutionKey("tenant-a", process);
        Instant createdAt = Instant.parse("2026-09-27T10:00:00Z");
        try (var store = new SqliteExecutionStore(directory.resolve("deadline.db"), Clock.systemUTC())) {
            long revision = createRunning(store, key, traversal);
            try (var recorder = ExecutionRecorder.open(store, key, "worker-before-pause",
                    Duration.ofSeconds(30), revision)) {
                var coordinator = new SagaCoordinator(graph, registry, Clock.fixed(createdAt, ZoneOffset.UTC));
                var before = coordinator.before(node, message(process, traversal, UUID.randomUUID()), recorder);
                coordinator.failed(before, new java.io.IOException("runner stopped before outcome"), recorder);
            }
            long resumedRevision = store.load(key).toCompletableFuture().join().revision();
            try (var recorder = ExecutionRecorder.open(store, key, "worker-after-deadline",
                    Duration.ofSeconds(30), resumedRevision)) {
                var recovered = new SagaCoordinator(graph, registry,
                        Clock.fixed(createdAt.plusSeconds(2), ZoneOffset.UTC));
                assertThrows(IllegalStateException.class, () -> recovered.before(
                        node, message(process, traversal, UUID.randomUUID()), recorder));
            }
            var saga = store.listSagas(key).toCompletableFuture().join().getFirst();
            assertEquals(true, saga.cancellationRequested());
            assertEquals(SagaDisposition.COMPENSATED, saga.disposition());
            assertEquals(createdAt.plusSeconds(1), saga.deadline());
        }
    }

    @Test
    void trustedParticipantNoEffectFailureIsNotParkedAsUnknown(@TempDir Path directory) {
        var node = new GraphNode("effect", NodeKind.BEHAVIOR, "http-request", Map.of(
                "saga.scope", "order", "saga.step", "notify",
                "saga.participant", "http-idempotency-v1", "saga.adapter", "ravenroot.http-idempotency.v1",
                "saga.irreversible", true, "saga.outcomeLookupUrl", "https://participant.test/operations/{{attributes.sagaOperationId}}"));
        var graph = new GraphDefinition(List.of(GraphNode.start("start"), node, GraphNode.end("end")),
                List.of(GraphEdge.to("start", "effect"), GraphEdge.to("effect", "end")));
        var registry = new BehaviorRegistry().registerFactory(new NodeBehaviorFactory() {
            @Override public NodeTypeDescriptor descriptor() {
                return new NodeTypeDescriptor("http-request", "HTTP", "Test", "Test HTTP",
                        "actor", false, List.of(), java.util.Set.of("side-effect", "saga-adapter:ravenroot.http-idempotency.v1"));
            }
            @Override public NodeHandler create(GraphNode ignored) {
                return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
            }
        });
        UUID process = UUID.randomUUID(), traversal = UUID.randomUUID();
        var key = new ExecutionKey("tenant-a", process);
        try (var store = new SqliteExecutionStore(directory.resolve("no-effect.db"), Clock.systemUTC())) {
            long revision = createRunning(store, key, traversal);
            try (var recorder = ExecutionRecorder.open(store, key, "worker", Duration.ofSeconds(30), revision)) {
                var coordinator = new SagaCoordinator(graph, registry, Clock.systemUTC());
                var before = coordinator.before(node, message(process, traversal, UUID.randomUUID()), recorder);
                coordinator.failed(before, new ConfirmedNoEffect(), recorder);
            }
            var saga = store.listSagas(key).toCompletableFuture().join().getFirst();
            assertEquals(SagaDisposition.COMPENSATED, saga.disposition());
            assertEquals(SagaStepStatus.CONFIRMED_NO_EFFECT,
                    saga.occurrences().values().iterator().next().status());
        }
    }

    private static final class ConfirmedNoEffect extends RuntimeException implements RetryClassified {
        @Override public Retryability retryability() { return Retryability.RETRYABLE_NO_EFFECT; }
    }

    private static NodeMessage message(UUID process, UUID traversal, UUID invocation) {
        return new NodeMessage(TestIdentities.of("tenant-a", "alice"), process, traversal, invocation,
                UUID.randomUUID(), "effect", Map.of("order", "A-1"), Map.of());
    }

    private static Map<?, ?> jsonMap(Object value) {
        if (value instanceof byte[] bytes) {
            value = PayloadJson.read(bytes, ai.ravenroot.api.payload.PayloadLimits.DEFAULTS).toJava();
        }
        return (Map<?, ?>) value;
    }

    private static void assertUntrustedParticipantRejected(String behavior, String participant,
                                                            Map<String, Object> protocol) {
        var properties = new java.util.LinkedHashMap<String, Object>(protocol);
        properties.put("saga.scope", "order");
        properties.put("saga.step", "effect");
        properties.put("saga.participant", participant);
        properties.put("saga.irreversible", true);
        var node = new GraphNode("effect", NodeKind.BEHAVIOR, behavior, Map.copyOf(properties));
        var graph = new GraphDefinition(List.of(GraphNode.start("start"), node, GraphNode.end("end")),
                List.of(GraphEdge.to("start", "effect"), GraphEdge.to("effect", "end")));
        var registry = new BehaviorRegistry().registerFactory(new NodeBehaviorFactory() {
            @Override public NodeTypeDescriptor descriptor() {
                return new NodeTypeDescriptor(behavior, behavior, "Test", "Ordinary effect adapter",
                        "actor", false, List.of(), java.util.Set.of("side-effect"));
            }
            @Override public NodeHandler create(GraphNode ignored) {
                return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
            }
        });
        try (var engine = new JoinTestEngine(); var manager = GraphManager.from(graph)) {
            assertThrows(IllegalArgumentException.class,
                    () -> new GraphRunner(manager, engine, registry, new ExecutionMonitor()));
        }
    }

    private static GraphNode sagaNode(String id, String step) {
        return new GraphNode(id, NodeKind.BEHAVIOR, "effect", Map.of(
                "saga.scope", "order", "saga.step", step, "saga.participant", "pure"));
    }

    private static BehaviorRegistry pureRegistry(String behavior, NodeHandler handler) {
        return new BehaviorRegistry().registerFactory(pureFactory(behavior, handler));
    }

    private static NodeBehaviorFactory pureFactory(String behavior, NodeHandler handler) {
        return new NodeBehaviorFactory() {
            @Override public NodeTypeDescriptor descriptor() {
                return new NodeTypeDescriptor(behavior, behavior, "Test", "Test probe",
                        "actor", false, List.of(), java.util.Set.of("saga-pure"));
            }
            @Override public NodeHandler create(GraphNode ignored) { return handler; }
        };
    }

    private static long createRunning(ExecutionStore store, ExecutionKey key, UUID traversalId) {
        var accepted = new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.ACCEPTED,
                Map.of(traversalId, new Traversal(traversalId, "start", TraversalStatus.ACCEPTED, Map.of())));
        var created = store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(accepted, new GraphVersionPin("graph-v1")))
                .build()).toCompletableFuture().join();
        return store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .build()).toCompletableFuture().join().revision();
    }
}
