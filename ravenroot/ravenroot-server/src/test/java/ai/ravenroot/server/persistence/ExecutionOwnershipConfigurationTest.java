package ai.ravenroot.server.persistence;

import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.core.runtime.WorkerIdentity;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The composition root's reading of who this replica is and how long it claims for.
 *
 * <p>The defaults are the load-bearing assertions: an unchanged deployment must get the ttl the field
 * initializer used to hold, and a name that identifies the pod rather than a random UUID.</p>
 */
class ExecutionOwnershipConfigurationTest {

    @Test
    void theTtlDefaultsToTheValueThatUsedToBeHardCoded() {
        assertEquals(Duration.ofSeconds(30),
                ExecutionOwnershipConfiguration.fromEnvironment(Map.of()).leaseTtl());
    }

    @Test
    void anExplicitTtlIsReadInWholeSeconds() {
        assertEquals(Duration.ofSeconds(45), ExecutionOwnershipConfiguration.fromEnvironment(
                Map.of(ExecutionOwnershipConfiguration.LEASE_TTL_VARIABLE, " 45 ")).leaseTtl());
    }

    @Test
    void aMalformedTtlFailsClosedRatherThanFallingBackToTheDefault() {
        for (String invalid : new String[] {"0", "-30", "30s", "PT30S", "", " ", "thirty"}) {
            if (invalid.isBlank()) {
                assertEquals(Duration.ofSeconds(30), ExecutionOwnershipConfiguration.fromEnvironment(
                                Map.of(ExecutionOwnershipConfiguration.LEASE_TTL_VARIABLE, invalid)).leaseTtl(),
                        "blank is unset, which is the only value that may fall back");
                continue;
            }
            var failure = assertThrows(IllegalArgumentException.class, () ->
                    ExecutionOwnershipConfiguration.fromEnvironment(Map.of(
                            ExecutionOwnershipConfiguration.LEASE_TTL_VARIABLE, invalid)));
            assertEquals(ExecutionOwnershipConfiguration.LEASE_TTL_VARIABLE
                    + " must be a positive whole number of seconds", failure.getMessage());
        }
    }

    @Test
    void theConfiguredNameIsUsedForBothRolesAndTheRolesStayDistinct() {
        var configuration = ExecutionOwnershipConfiguration.fromEnvironment(
                Map.of(ExecutionOwnershipConfiguration.WORKER_ID_VARIABLE, "ravenroot-2"));

        var runtime = configuration.runtimeOwnership().identity();
        var recovery = configuration.recoveryIdentity();

        assertEquals("ravenroot-2", runtime.replicaName());
        assertEquals("ravenroot-2", recovery.replicaName());
        assertEquals(runtime.incarnation(), recovery.incarnation());
        assertEquals(WorkerIdentity.Role.RUNTIME, runtime.role());
        assertEquals(WorkerIdentity.Role.RECOVERY, recovery.role());
        assertNotEquals(runtime.value(), recovery.value(),
                "the sweep must be a different worker from the runtime it runs beside, or it claims "
                        + "that runtime's live work and keeps its fencing token");
        assertEquals("ravenroot-2:" + WorkerIdentity.processIncarnation() + "/runtime", runtime.value());
    }

    @Test
    void anUnsetNameStillProducesAUsableIdentity() {
        var identity = ExecutionOwnershipConfiguration.fromEnvironment(Map.of()).runtimeOwnership()
                .identity();

        // The host name is an operating-system fact this test cannot pin, so what is asserted is the
        // property that matters: whatever was resolved, it is a legal name and the rendered identity
        // still has its three parts.
        assertFalse(identity.replicaName().isBlank());
        assertEquals(identity.replicaName() + ":" + WorkerIdentity.processIncarnation() + "/runtime",
                identity.value());
    }

    @Test
    void aMalformedNameIsRefusedAgainstTheVariableTheOperatorSet() {
        for (String invalid : new String[] {"pod#1", "pod/1", "secret value", "x".repeat(101)}) {
            var failure = assertThrows(IllegalArgumentException.class, () ->
                    ExecutionOwnershipConfiguration.fromEnvironment(Map.of(
                            ExecutionOwnershipConfiguration.WORKER_ID_VARIABLE, invalid)));
            assertEquals(ExecutionOwnershipConfiguration.WORKER_ID_VARIABLE + " "
                    + WorkerIdentity.NAME_RULE, failure.getMessage());
            assertFalse(failure.getMessage().contains(invalid));
        }
    }

    @Test
    void theTtlIsCheckedAgainstTheComposedStoresOwnPublishedBounds() {
        try (var store = new InMemoryExecutionStore(Clock.systemUTC(), Duration.ofMinutes(5), 1024,
                Duration.ofSeconds(5))) {
            assertDoesNotThrow(() -> ExecutionOwnershipConfiguration.fromEnvironment(Map.of())
                    .requireCompatible(store), "the shipped default must pass against a shipped store");

            var tooLong = assertThrows(IllegalArgumentException.class, () ->
                    ExecutionOwnershipConfiguration.fromEnvironment(Map.of(
                            ExecutionOwnershipConfiguration.LEASE_TTL_VARIABLE, "600"))
                            .requireCompatible(store));
            assertTrue(tooLong.getMessage()
                    .startsWith(ExecutionOwnershipConfiguration.LEASE_TTL_VARIABLE), tooLong.getMessage());
            assertTrue(tooLong.getMessage().contains("300 seconds"), tooLong.getMessage());

            var tooShort = assertThrows(IllegalArgumentException.class, () ->
                    ExecutionOwnershipConfiguration.fromEnvironment(Map.of(
                            ExecutionOwnershipConfiguration.LEASE_TTL_VARIABLE, "5"))
                            .requireCompatible(store));
            assertTrue(tooShort.getMessage().contains("clock-skew budget"), tooShort.getMessage());
        }
    }

    @Test
    void noComposedStorePublishesNoBound() {
        assertDoesNotThrow(() -> ExecutionOwnershipConfiguration.fromEnvironment(Map.of(
                ExecutionOwnershipConfiguration.LEASE_TTL_VARIABLE, "86400")).requireCompatible(null));
    }
}
