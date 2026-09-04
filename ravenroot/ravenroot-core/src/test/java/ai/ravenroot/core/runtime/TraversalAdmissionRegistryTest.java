package ai.ravenroot.core.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The admission registry's own contract, and specifically the difference between a first arrival and
 * a re-entry.
 *
 * <h2>Why this exists as a unit test and not only as a runner scenario</h2>
 * <p>The leak these assertions pin was first caught end to end, through a traversal that ended with a
 * retry parked on its pause gate. That scenario is worth keeping and is kept — but it can only ever
 * exercise whichever of the two layered refusals happens to fire first, and which one that is depends
 * on the order of two lines in {@code GraphRunner.release}. Changing that order for a good reason
 * silently stopped the scenario from discriminating this class's part, which is exactly the way a
 * guard rots: still present, still correct, no longer tested.</p>
 *
 * <p>So the mechanism is pinned here, where there is no choreography to depend on. The registry is
 * asked directly what it does after a traversal has been released, which is the one question the
 * leak turned on.</p>
 */
class TraversalAdmissionRegistryTest {

    private static final int LIMIT = 1;

    @Test
    @DisplayName("a first arrival creates a gate and releasing the traversal removes it")
    void anOrdinaryArrivalCreatesAGateThatTheTraversalsReleaseRemoves() {
        try (var registry = new TraversalAdmissionRegistry()) {
            var key = keyFor(UUID.randomUUID());
            assertEquals(0, registry.gateCount());

            var lease = await(registry.acquire(key, LIMIT));
            assertNotNull(lease);
            assertEquals(1, registry.gateCount());

            lease.close();
            registry.release(key.traversalId());
            assertEquals(0, registry.gateCount(),
                    "a traversal that ended must leave nothing behind in this registry");
        }
    }

    @Test
    @DisplayName("acquiring after the traversal was released creates the gate that leaks")
    void acquireRecreatesAGateNothingWillEverRemove() {
        try (var registry = new TraversalAdmissionRegistry()) {
            var key = keyFor(UUID.randomUUID());
            await(registry.acquire(key, LIMIT)).close();
            registry.release(key.traversalId());

            // This is the shape the leak took: a retry reached admission after its traversal's gates
            // were dropped, and acquire happily made a new one. Nothing removes it -- the traversal
            // that would have is over -- so it lives until the whole runner closes, one entry per
            // retrying node per traversal.
            await(registry.acquire(key, LIMIT)).close();
            assertEquals(1, registry.gateCount(),
                    "acquire is documented to create, and does; this is why a retry must not use it");
        }
    }

    @Test
    @DisplayName("re-entering after the traversal was released is refused and creates nothing")
    void reacquireRefusesInsteadOfRecreating() {
        try (var registry = new TraversalAdmissionRegistry()) {
            var key = keyFor(UUID.randomUUID());
            await(registry.acquire(key, LIMIT)).close();
            registry.release(key.traversalId());

            var refused = assertThrows(CompletionException.class,
                    () -> await(registry.reacquire(key, LIMIT)));
            assertInstanceOf(TraversalCancelledException.class, refused.getCause(),
                    "the refusal names the traversal and the node, so a caller can report which hop "
                            + "did not run rather than surfacing a registry-internal error");
            assertEquals(key.nodeId(),
                    ((TraversalCancelledException) refused.getCause()).refusedNodeId());
            assertEquals(0, registry.gateCount(),
                    "the whole point: a refused re-entry leaves the registry exactly as it found it");
        }
    }

    @Test
    @DisplayName("re-entry uses the gate a first arrival created, so a live retry still runs")
    void reacquireSucceedsWhileTheTraversalIsStillLive() {
        try (var registry = new TraversalAdmissionRegistry()) {
            var key = keyFor(UUID.randomUUID());
            await(registry.acquire(key, LIMIT)).close();

            // The ordinary case, and the one a refusal must not break: attempt one created this
            // gate, and the retry is a re-entry to the same node in the same traversal.
            var lease = await(registry.reacquire(key, LIMIT));
            assertNotNull(lease);
            assertEquals(1, registry.gateCount(), "a re-entry reuses the gate rather than adding one");
            lease.close();
        }
    }

    @Test
    void aClosedRegistryRefusesBothArrivalKinds() {
        var registry = new TraversalAdmissionRegistry();
        var key = keyFor(UUID.randomUUID());
        registry.close();

        assertThrows(CompletionException.class, () -> await(registry.acquire(key, LIMIT)));
        assertThrows(CompletionException.class, () -> await(registry.reacquire(key, LIMIT)));
        assertEquals(0, registry.gateCount());
    }

    private static TraversalAdmissionRegistry.Key keyFor(UUID traversalId) {
        return new TraversalAdmissionRegistry.Key("tenant-a", "deployment-a", "v1", traversalId, "work");
    }

    private static TraversalAdmissionRegistry.Lease await(
            CompletionStage<TraversalAdmissionRegistry.Lease> stage) {
        return stage.toCompletableFuture().join();
    }
}
