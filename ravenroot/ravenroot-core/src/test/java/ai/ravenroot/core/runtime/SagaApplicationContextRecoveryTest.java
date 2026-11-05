package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.catalog.*;
import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.execution.*;
import ai.ravenroot.api.node.service.SagaCommandTransport;
import ai.ravenroot.api.payload.*;
import ai.ravenroot.api.persistence.*;
import ai.ravenroot.core.graph.*;
import ai.ravenroot.persistence.sqlite.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class SagaApplicationContextRecoveryTest {
    @ParameterizedTest @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void committedEnvelopeReopensAndDispatchesOnlyUnderCurrentAuthority(boolean correlated, boolean allowed, @TempDir Path directory) throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC);
        var path = directory.resolve("saga-context.db"); var config = SqliteStoreConfig.defaults().withBusinessHistories(BusinessHistoryPolicy.DEFAULTS);
        UUID process = UUID.randomUUID(), traversal = UUID.randomUUID(); var key = new ExecutionKey("tenant-a", process);
        var context = correlated ? new ApplicationCorrelationContext("derived-child-operation", Optional.of("part-2"), Optional.empty(), "round-3", Optional.of("file.csv")) : null;
        var node = new GraphNode("publish", NodeKind.BEHAVIOR, "amqp.publish", Map.of("saga.scope", "order", "saga.step", "publish",
                "saga.participant", "amqp-inbox-v1", "saga.adapter", "ravenroot.amqp-inbox.v1", "saga.inboxBinding", "orders-v1",
                "saga.businessCompletionRequired", true, "persistent", true, "saga.irreversible", true));
        var graph = new GraphDefinition(List.of(GraphNode.start("start"), node, GraphNode.end("end")), List.of(GraphEdge.to("start", "publish"), GraphEdge.to("publish", "end")));
        var registry = new BehaviorRegistry().registerFactory(new NodeBehaviorFactory() {
            @Override public NodeTypeDescriptor descriptor() { return new NodeTypeDescriptor("amqp.publish", "AMQP", "Test", "Trusted protocol probe", "actor", false,
                    List.of(), Set.of("side-effect", "saga-adapter:ravenroot.amqp-inbox.v1")); }
            @Override public NodeHandler create(GraphNode ignored) { return message -> { throw new AssertionError("governed AMQP must use committed outbox"); }; }
        });
        SagaCommandIntent committed;
        try (var store = new SqliteExecutionStore(path, clock, config)) {
            var created = store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent()).apply(new ExecutionTransition.ProcessCreated(
                    new ProcessInstance(process, ProcessInstanceStatus.ACCEPTED, Map.of(traversal, new Traversal(traversal, "publish", TraversalStatus.ACCEPTED, Map.of()))), new GraphVersionPin("graph-v1")))
                    .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING)).apply(new ExecutionTransition.TraversalTransitioned(traversal, TraversalStatus.RUNNING)).build()).toCompletableFuture().join();
            if (correlated) store.recordCorrelatedTraversal(new CorrelatedTraversal(new BusinessScope("tenant-a", "deployment", "graph-v1"), context, process, traversal, "publish",
                    CorrelatedTraversal.Cause.DERIVED_CONTEXT, TraversalStatus.RUNNING, null, clock.instant(), clock.instant(), null,
                    new CorrelationDerivation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "explicit child operation")), CorrelationMapping.Mode.ACCEPT_OR_CREATE).toCompletableFuture().join();
            var current = store.correlatedTraversal("tenant-a", process, traversal).toCompletableFuture().join().map(CorrelatedTraversal::context).orElse(null);
            var delivered = new NodeMessage(TestIdentities.of("tenant-a", "original-worker"), process, traversal, UUID.randomUUID(), UUID.randomUUID(), Set.of(), "publish",
                    Map.of("version", "amqp.publish.v1", "bodyJson", Map.of("applicationCorrelationId", "forged-payload")), Map.of("applicationCorrelationId", "stale-parent-attribute"), NodeCommand.PROCESS, current);
            try (var recorder = ExecutionRecorder.open(store, key, "before-restart", Duration.ofSeconds(30), created.revision())) {
                var before = new SagaCoordinator(graph, registry, clock).before(node, delivered, recorder);
                assertEquals(current, before.message().applicationCorrelation()); assertNotNull(before.replay());
            }
            committed = store.listSagaCommands(key).toCompletableFuture().join().getFirst().intent();
        }
        try (var reopened = new SqliteExecutionStore(path, clock, config)) {
            var effects = new AtomicInteger(); var authorities = new AtomicInteger();
            var transport = new SagaCommandTransport() {
                @Override public CompletionStage<BrokerResult> publish(SagaCommandIntent intent) {
                    effects.incrementAndGet(); assertEquals(committed, intent);
                    var envelope = (Map<?, ?>) PayloadJson.read(intent.payload().bytes(), PayloadLimits.DEFAULTS).toJava();
                    assertEquals(process.toString(), envelope.get("processInstanceId")); assertEquals(traversal.toString(), envelope.get("traversalId"));
                    assertEquals("stale-parent-attribute", ((Map<?, ?>) envelope.get("attributes")).get("applicationCorrelationId"));
                    if (correlated) assertEquals(context.applicationCorrelationId(), ((Map<?, ?>) envelope.get("applicationContext")).get("applicationCorrelationId"));
                    else assertFalse(envelope.containsKey("applicationContext"), "legacy absence must not resurrect attribute/payload identity");
                    return CompletableFuture.completedFuture(new BrokerResult(true, "fixture accepted"));
                }
                @Override public CompletionStage<Boolean> businessCompleted(SagaCommandIntent intent) { return CompletableFuture.completedFuture(false); }
            };
            var publisher = new SagaOutboxPublisher(reopened, transport, (actualKey, intent) -> {
                authorities.incrementAndGet(); assertEquals(key, actualKey);
                assertEquals(context, reopened.correlatedTraversal("tenant-a", process, traversal).toCompletableFuture().join().map(CorrelatedTraversal::context).orElse(null));
                if (!allowed) throw new SecurityException("current authority revoked despite intact frozen metadata");
            }, "current-worker", 1, Duration.ofSeconds(5), Duration.ofSeconds(1), clock);
            var result = publisher.runOnce("tenant-a");
            assertFalse(result.isEmpty()); assertTrue(authorities.get() > 0); assertEquals(allowed ? 1 : 0, effects.get());
            assertEquals(allowed ? SagaOutboxStatus.BROKER_ACCEPTED : SagaOutboxStatus.PENDING, result.getFirst().status());
        }
    }
}
