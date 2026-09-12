package ai.ravenroot.core.runtime;

import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the application refuses to be composed with.
 *
 * <p>Both refusals are cheaper here than where they would otherwise appear. A recovery identity in
 * the runtime seat shows up as a fencing anomaly under load; a ttl above the store's bound shows up
 * as an application that starts cleanly and then fails every claim.</p>
 */
class DefaultRavenrootApplicationOwnershipTest {

    @Test
    void theRuntimeSeatRefusesTheRecoveryIdentity() {
        try (var store = new InMemoryExecutionStore()) {
            var recoverySeat = new ExecutionOwnership(
                    WorkerIdentity.of("ravenroot-0", WorkerIdentity.Role.RECOVERY), Duration.ofSeconds(30));

            var failure = assertThrows(IllegalArgumentException.class, () -> application(store, recoverySeat));

            assertTrue(failure.getMessage().contains("RUNTIME"), failure.getMessage());
        }
    }

    @Test
    void aTtlAboveTheComposedStoresBoundIsRefusedAtConstruction() {
        try (var store = new InMemoryExecutionStore(Clock.systemUTC(), Duration.ofMinutes(5), 1024)) {
            var tooLong = new ExecutionOwnership(
                    WorkerIdentity.of("ravenroot-0", WorkerIdentity.Role.RUNTIME), Duration.ofMinutes(6));

            var failure = assertThrows(IllegalArgumentException.class, () -> application(store, tooLong));

            assertTrue(failure.getMessage().contains("exceeds the store's published maximum"),
                    failure.getMessage());
        }
    }

    @Test
    void aNamedRuntimeIdentityWithTheShippedTtlIsAccepted() {
        try (var store = new InMemoryExecutionStore()) {
            assertDoesNotThrow(() -> application(store, new ExecutionOwnership(
                    WorkerIdentity.of("ravenroot-0", WorkerIdentity.Role.RUNTIME),
                    ExecutionOwnership.DEFAULT_LEASE_TTL)));
        }
    }

    private static DefaultRavenrootApplication application(InMemoryExecutionStore store,
                                                           ExecutionOwnership ownership) {
        return new DefaultRavenrootApplication(new SameThreadExecutionEngine(), new ExecutionMonitor(),
                BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()),
                new ai.ravenroot.core.programming.InMemoryArtifactRegistry(),
                new ai.ravenroot.core.programming.DisabledProgramRuntime(),
                ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(), store, 0,
                UnknownBehaviorPolicy.passThrough(), null, null, null,
                GraphExecutionLimits.DEFAULTS, null, null, Duration.ofMillis(50), ownership);
    }
}
