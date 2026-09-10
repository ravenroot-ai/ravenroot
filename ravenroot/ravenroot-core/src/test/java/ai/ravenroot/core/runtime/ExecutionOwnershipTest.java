package ai.ravenroot.core.runtime;

import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lease ttl is bounded by what the composed store publishes, not by taste.
 *
 * <p>Both bounds come from the store. {@code maxLeaseTtl()} is the largest claim it accepts, so a
 * longer one produces an application that starts cleanly and then fails every claim with an
 * invalid-request. {@code maxClockSkew()} is how far two hosts' clocks may disagree, so a ttl at or
 * below it can be judged expired by a peer while its holder still believes it live.</p>
 */
class ExecutionOwnershipTest {

    @Test
    void theDefaultReproducesTheValuesThatUsedToBeFieldInitializers() {
        var defaults = ExecutionOwnership.defaults();

        assertEquals(Duration.ofSeconds(30), defaults.leaseTtl());
        assertEquals(WorkerIdentity.Role.RUNTIME, defaults.identity().role());
        assertEquals(WorkerIdentity.LOCAL_REPLICA_NAME, defaults.identity().replicaName());
        assertEquals(defaults.identity().value(), defaults.workerId());
    }

    @Test
    void aNonPositiveTtlIsRefused() {
        var identity = WorkerIdentity.unnamed(WorkerIdentity.Role.RUNTIME);
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionOwnership(identity, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionOwnership(identity, Duration.ofSeconds(-1)));
    }

    @Test
    void noStoreMeansNoPublishedBoundToCheckAgainst() {
        assertDoesNotThrow(() -> new ExecutionOwnership(
                WorkerIdentity.unnamed(WorkerIdentity.Role.RUNTIME), Duration.ofHours(9))
                .requireCompatible(null));
    }

    @Test
    void aTtlAboveTheStoresPublishedMaximumIsRefused() {
        try (var store = new InMemoryExecutionStore(Clock.systemUTC(), Duration.ofMinutes(5), 1024)) {
            var ownership = new ExecutionOwnership(
                    WorkerIdentity.unnamed(WorkerIdentity.Role.RUNTIME), Duration.ofMinutes(6));

            var failure = assertThrows(IllegalArgumentException.class,
                    () -> ownership.requireCompatible(store));

            assertTrue(failure.getMessage().contains("exceeds the store's published maximum"),
                    failure.getMessage());
            assertDoesNotThrow(() -> new ExecutionOwnership(
                    WorkerIdentity.unnamed(WorkerIdentity.Role.RUNTIME), Duration.ofMinutes(5))
                    .requireCompatible(store), "the bound itself must be accepted");
        }
    }

    @Test
    void aTtlAtOrBelowTheStoresClockSkewBudgetIsRefused() {
        try (var store = new InMemoryExecutionStore(Clock.systemUTC(), Duration.ofMinutes(5), 1024,
                Duration.ofSeconds(5))) {
            for (Duration tooShort : new Duration[] {Duration.ofSeconds(5), Duration.ofSeconds(1)}) {
                var failure = assertThrows(IllegalArgumentException.class,
                        () -> new ExecutionOwnership(
                                WorkerIdentity.unnamed(WorkerIdentity.Role.RUNTIME), tooShort)
                                .requireCompatible(store));
                assertTrue(failure.getMessage().contains("clock-skew budget"), failure.getMessage());
            }
            assertDoesNotThrow(() -> new ExecutionOwnership(
                    WorkerIdentity.unnamed(WorkerIdentity.Role.RUNTIME), Duration.ofSeconds(6))
                    .requireCompatible(store), "strictly greater is the bound, not a margin above it");
        }
    }
}
