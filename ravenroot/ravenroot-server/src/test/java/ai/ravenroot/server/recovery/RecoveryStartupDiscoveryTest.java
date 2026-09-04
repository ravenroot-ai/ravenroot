package ai.ravenroot.server.recovery;

import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionOrigin;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.core.persistence.InMemoryGraphDefinitionStore;
import ai.ravenroot.core.recovery.ExecutionRecoveryCoordinator;
import ai.ravenroot.core.recovery.ExecutionRecoveryService;
import ai.ravenroot.core.recovery.PinnedGraphRecoveryAuthority;
import ai.ravenroot.core.recovery.RecoveryCandidate;
import ai.ravenroot.core.recovery.RecoveryClassification;
import ai.ravenroot.core.runtime.GraphExecutionLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What holds readiness closed at startup, and what deliberately does not.
 *
 * <p>Every test here drives the real pass rather than asserting on a constructed result: the whole
 * value of the gate is that it can be observed closed and then observed opening, and a test that
 * only ever built a finished {@code Result} would prove nothing about whether the pass runs.</p>
 */
class RecoveryStartupDiscoveryTest {

    private static final String TENANT = "acme";
    private static final Duration TICK = Duration.ofMillis(10);
    private static final long BOUND_MILLIS = 20_000;

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("readiness stays closed while the durable state cannot be read, and opens once it can")
    void aPassThatCannotReadTheStoreDoesNotCompleteUntilItCan() throws Exception {
        var unreachable = new AtomicBoolean(true);
        try (var backing = new InMemoryExecutionStore(clock);
             var definitions = new InMemoryGraphDefinitionStore(clock)) {
            interrupted(backing, "00".repeat(32));
            // The inventory read is the one operation the pass depends on, so it is the one this
            // double refuses. Everything else delegates, so a pass that completed here would have
            // completed for a reason this test did not create.
            ExecutionStore intermittent = failingInventory(backing, unreachable);
            try (var discovery = discoveryOver(intermittent, definitions, capability -> true)) {
                discovery.start();
                Thread.sleep(150);

                assertFalse(discovery.complete(),
                        "a pass that could not look at the durable state must not report that it has");
                assertNull(discovery.completed());

                unreachable.set(false);

                RecoveryStartupDiscovery.Result result = awaitCompletion(discovery);
                assertTrue(result.scanned(),
                        "the pass retries rather than failing permanently, because the condition that "
                                + "stopped it is the one that resolves on its own");
                assertEquals(1, result.candidates().size());
            }
        }
    }

    /** Delegates everything, refusing only the inventory read, and only while {@code refusing} is set. */
    private static ExecutionStore failingInventory(ExecutionStore backing, AtomicBoolean refusing) {
        return (ExecutionStore) Proxy.newProxyInstance(
                RecoveryStartupDiscoveryTest.class.getClassLoader(),
                new Class<?>[] {ExecutionStore.class},
                (proxy, method, args) -> {
                    if ("listProcessInstances".equals(method.getName()) && refusing.get()) {
                        throw new IllegalStateException("the store is unreachable");
                    }
                    try {
                        return method.invoke(backing, args);
                    } catch (java.lang.reflect.InvocationTargetException invoked) {
                        throw invoked.getCause();
                    }
                });
    }

    @Test
    @DisplayName("an adapter with no durable inventory completes the pass instead of blocking forever")
    void aStoreWithoutAnInventoryIsNotWaitedOn() throws Exception {
        try (var store = new InMemoryExecutionStore(clock);
             var definitions = new InMemoryGraphDefinitionStore(clock);
             var discovery = discoveryOver(store, definitions,
                     capability -> capability != StoreCapability.PROCESS_INVENTORY)) {
            discovery.start();

            RecoveryStartupDiscovery.Result result = awaitCompletion(discovery);

            assertFalse(result.scanned(),
                    "the empty cohort must be reported as nothing looked at, not as nothing found");
            assertTrue(result.candidates().isEmpty());
        }
    }

    @Test
    @DisplayName("a completed pass keeps every refusal inspectable rather than reducing it to a count")
    void refusalsSurviveThePassAndDoNotHoldTheGateClosed() throws Exception {
        try (var store = new InMemoryExecutionStore(clock);
             var definitions = new InMemoryGraphDefinitionStore(clock)) {
            // Pinned to a document that was never stored: the deployment inherited work it cannot
            // rebuild, which is an operator fact rather than a reason to refuse unrelated traffic.
            ExecutionKey refusedKey = interrupted(store, "11".repeat(32));
            try (var discovery = discoveryOver(store, definitions, capability -> true)) {
                discovery.start();

                RecoveryStartupDiscovery.Result result = awaitCompletion(discovery);

                assertTrue(result.scanned());
                assertEquals(1, result.candidates().size());
                List<RecoveryCandidate> refused = result.refused();
                assertEquals(1, refused.size());
                assertEquals(refusedKey, refused.get(0).key());
                var verdict = assertInstanceOf(RecoveryClassification.Refused.class,
                        refused.get(0).classification());
                assertEquals(RecoveryClassification.Reason.DEFINITION_UNRESOLVED, verdict.reason());
                assertFalse(verdict.detail().isBlank(),
                        "an operator asked to act on this has to be told what could not be resolved");
            }
        }
    }

    // ------------------------------------------------------------------ fixture

    private RecoveryStartupDiscovery discoveryOver(ExecutionStore store,
                                                   InMemoryGraphDefinitionStore definitions,
                                                   java.util.function.Predicate<StoreCapability> supports) {
        var authority = new PinnedGraphRecoveryAuthority(store, definitions, null,
                behavior -> Optional.empty(), GraphExecutionLimits.DEFAULTS);
        var coordinator = new ExecutionRecoveryCoordinator(authority, List.of());
        var recovery = new ExecutionRecoveryService(store, List.of(TENANT), "startup-discovery", 10,
                Duration.ofSeconds(30), coordinator.declarations(), coordinator);
        return new RecoveryStartupDiscovery(recovery, coordinator, supports, TICK);
    }

    /** An instance left non-terminal with no live lease: the durable shape of interrupted work. */
    private ExecutionKey interrupted(ExecutionStore store, String pin) {
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        var accepted = new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.ACCEPTED,
                Map.of(traversalId, new Traversal(traversalId, "start", TraversalStatus.ACCEPTED, Map.of())));
        var created = await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.notPresent())
                .recordOrigin(ExecutionOrigin.none())
                .apply(new ExecutionTransition.ProcessCreated(accepted, new GraphVersionPin(pin)))
                .build()));
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .build()));
        return key;
    }

    private static RecoveryStartupDiscovery.Result awaitCompletion(RecoveryStartupDiscovery discovery)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMillis(BOUND_MILLIS).toNanos();
        while (System.nanoTime() < deadline) {
            RecoveryStartupDiscovery.Result result = discovery.completed();
            if (result != null) {
                return result;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("the startup pass never completed");
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
