package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionSubmission;
import ai.ravenroot.api.persistence.*;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Operator recovery must restart exhausted durable work rather than only changing display state. */
class SagaOperatorRecoveryTest {
    private static final String GRAPH = """
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="node-kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="operator-fixture" edgedefault="directed">
                <node id="error"><data key="node-kind">ERROR</data></node>
                <node id="start"><data key="node-kind">START</data></node>
                <node id="end"><data key="node-kind">END</data></node>
                <edge id="next" source="start" target="end"><data key="edge-outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    @Test
    void reconcileRearmsTheSameExhaustedForwardCommandForOneNewBoundedWindow() throws Exception {
        verifyOperatorRearm(false);
    }

    @Test
    void retryCompensationRearmsTheSameExhaustedCompensationCommandForOneNewBoundedWindow()
            throws Exception {
        verifyOperatorRearm(true);
    }

    private static void verifyOperatorRearm(boolean compensation) throws Exception {
        var store = new InMemoryExecutionStore();
        ExecutionStore durableFixture = (ExecutionStore) java.lang.reflect.Proxy.newProxyInstance(
                SagaOperatorRecoveryTest.class.getClassLoader(), new Class<?>[]{ExecutionStore.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("supports") && arguments[0] == StoreCapability.DURABLE) {
                        return true;
                    }
                    try {
                        return method.invoke(store, arguments);
                    } catch (java.lang.reflect.InvocationTargetException wrapped) {
                        throw wrapped.getCause();
                    }
                });
        var application = new DefaultRavenrootApplication(new SameThreadExecutionEngine(),
                new ExecutionMonitor(), BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()),
                new ai.ravenroot.core.programming.InMemoryArtifactRegistry(),
                new ai.ravenroot.core.programming.DisabledProgramRuntime(),
                ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(), durableFixture);
        try {
            ExecutionSubmission execution = application.startGraphMl(TestIdentities.TENANT_A,
                    UUID.randomUUID(), new ByteArrayInputStream(GRAPH.getBytes(StandardCharsets.UTF_8)), "input");
            var key = new ExecutionKey(TestIdentities.TENANT_A.tenantId(), execution.processInstanceId());
            var stored = store.load(key).toCompletableFuture().join();
            Instant now = Instant.now();
            UUID sagaId = UUID.randomUUID(), occurrenceId = UUID.randomUUID(), messageId = UUID.randomUUID();
            String forwardOperation = "forward-" + occurrenceId;
            String compensationOperation = "compensate-" + occurrenceId;
            var payload = OpaquePayload.of("command".getBytes(StandardCharsets.UTF_8), "application/json");
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.bytes()));
            var command = new SagaCommandIntent(messageId, sagaId,
                    compensation ? compensationOperation : forwardOperation, "participant", "order.command",
                    1, payload, digest, null, now, 1);
            var definition = new SagaDefinition(1, "operator", "a".repeat(64), "b".repeat(64), Map.of(
                    "effect", new SagaStepDefinition("effect", "participant", "receipt-v1",
                            "compensate", List.of(), false, false)));
            var step = new SagaStepSnapshot(occurrenceId, "effect", UUID.randomUUID(), forwardOperation,
                    compensationOperation, digest, compensation ? SagaStepStatus.COMPENSATING
                    : SagaStepStatus.DISPATCHED, OpaquePayload.empty("application/json"), "", now);
            var saga = new SagaSnapshot(key, sagaId, execution.traversalId(), definition, 1,
                    compensation ? SagaDisposition.COMPENSATION_PENDING : SagaDisposition.RUNNING,
                    compensation, Map.of(occurrenceId, step), null, now, now, "", false);
            store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(stored.revision()))
                    .writeSaga(new SagaWrite(UUID.randomUUID(), 0, saga)).enqueueSagaCommand(command).build())
                    .toCompletableFuture().join();
            var claim = store.claimSagaCommands(key.tenantId(), "publisher", 1, Duration.ofSeconds(5))
                    .toCompletableFuture().join().getFirst();
            store.settleSagaCommand(key.tenantId(), messageId, "publisher", claim.fencingToken(),
                    new SagaOutboxSettlement.Exhausted("participant unavailable"))
                    .toCompletableFuture().join();
            var exhaustedSaga = store.loadSaga(key, sagaId).toCompletableFuture().join().orElseThrow();
            var exhaustedCommand = store.listSagaCommands(key).toCompletableFuture().join().getFirst();
            assertEquals(SagaOutboxStatus.EXHAUSTED, exhaustedCommand.status());

            application.requestSagaAction(key.tenantId(), key.processInstanceId(), sagaId,
                    exhaustedSaga.revision(), compensation ? SagaOperatorAction.RETRY_COMPENSATION
                            : SagaOperatorAction.RECONCILE, UUID.randomUUID());

            var rearmed = store.listSagaCommands(key).toCompletableFuture().join().getFirst();
            assertEquals(SagaOutboxStatus.PENDING, rearmed.status());
            assertEquals(0, rearmed.attempts());
            assertEquals(command, rearmed.intent(), "operator recovery keeps the immutable participant identity");
            var retried = store.claimSagaCommands(key.tenantId(), "publisher-after-operator", 1,
                    Duration.ofSeconds(5)).toCompletableFuture().join().getFirst();
            assertEquals(1, retried.attempts(), "the operator action opens exactly one fresh bounded window");
            assertEquals(messageId, retried.intent().messageId());
        } finally {
            application.close();
            store.close();
        }
    }
}
