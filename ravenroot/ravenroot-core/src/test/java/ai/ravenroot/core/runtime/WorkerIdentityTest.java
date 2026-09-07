package ai.ravenroot.core.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A worker id an operator can read, and two roles that stay two workers.
 *
 * <p>What is asserted here is what a random UUID could not do: say which replica, which start of it,
 * and which of its two lease-takers holds a claim. The role distinctness case is the load-bearing
 * one — a shared store's claim-candidate query skips instances leased by a <em>different</em> worker
 * and rotates the fencing token only for a different-worker claim, so one identity for both roles
 * would let a replica's sweep take work its own runtime is advancing and leave the fence unchanged.
 */
class WorkerIdentityTest {

    @Test
    void rendersReplicaIncarnationAndRole() {
        var identity = WorkerIdentity.of("ravenroot-0", WorkerIdentity.Role.RUNTIME);

        assertEquals("ravenroot-0#" + WorkerIdentity.processIncarnation() + "/runtime", identity.value());
        assertEquals(identity.value(), identity.toString());
    }

    @Test
    void theTwoRolesOfOneReplicaAreTwoWorkersThatShareAnIncarnation() {
        var runtime = WorkerIdentity.of("ravenroot-0", WorkerIdentity.Role.RUNTIME);
        var recovery = WorkerIdentity.of("ravenroot-0", WorkerIdentity.Role.RECOVERY);

        assertNotEquals(runtime.value(), recovery.value(),
                "one identity for both roles lets a replica's sweep claim work its own runtime is "
                        + "advancing and keep the fencing token under the runtime's recorder");
        assertEquals(runtime.incarnation(), recovery.incarnation(),
                "an operator must be able to see that both claims come from the same start of the "
                        + "same replica");
        assertTrue(runtime.value().endsWith("/runtime"));
        assertTrue(recovery.value().endsWith("/recovery"));
    }

    @Test
    void theIncarnationIsOneValuePerJvmStartAndCarriesTheUniqueness() {
        assertEquals(WorkerIdentity.processIncarnation(),
                WorkerIdentity.of("a", WorkerIdentity.Role.RUNTIME).incarnation());
        assertEquals(WorkerIdentity.processIncarnation(),
                WorkerIdentity.of("b", WorkerIdentity.Role.RECOVERY).incarnation());
        // Two replicas that were named the same - two hosts with one host name, or one variable set
        // on both - still produce two identities only because of the incarnation. This asserts the
        // weaker, verifiable half: within one JVM the name is what distinguishes them.
        assertNotEquals(WorkerIdentity.of("a", WorkerIdentity.Role.RUNTIME).value(),
                WorkerIdentity.of("b", WorkerIdentity.Role.RUNTIME).value());
    }

    @Test
    void anUnnamedEmbedderStillGetsAReadableIdentity() {
        var identity = WorkerIdentity.unnamed(WorkerIdentity.Role.RUNTIME);

        assertEquals(WorkerIdentity.LOCAL_REPLICA_NAME, identity.replicaName());
        assertTrue(identity.value().startsWith(WorkerIdentity.LOCAL_REPLICA_NAME + "#"));
    }

    @Test
    void theSeparatorsAreReservedSoTheRenderedValueCanBeSplitBack() {
        for (String reserved : new String[] {"pod#1", "pod/1", "pod 1", "pod:1", "pod\"1"}) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> WorkerIdentity.of(reserved, WorkerIdentity.Role.RUNTIME));
            assertEquals("replicaName " + WorkerIdentity.NAME_RULE, failure.getMessage());
            assertFalse(failure.getMessage().contains(reserved),
                    "a replica name is operator-supplied and this message reaches stderr");
        }
    }

    @Test
    void aBlankOrOverlongNameIsRefusedWithTheSameStatedRule() {
        assertEquals("replicaName " + WorkerIdentity.NAME_RULE,
                assertThrows(IllegalArgumentException.class,
                        () -> WorkerIdentity.of("  ", WorkerIdentity.Role.RUNTIME)).getMessage());
        assertEquals("replicaName " + WorkerIdentity.NAME_RULE,
                assertThrows(IllegalArgumentException.class,
                        () -> WorkerIdentity.of("x".repeat(101), WorkerIdentity.Role.RUNTIME)).getMessage());
        // The bound itself, so shortening it later is a deliberate change rather than an accident.
        assertEquals(100, WorkerIdentity.of("x".repeat(100), WorkerIdentity.Role.RUNTIME)
                .replicaName().length());
    }

    @Test
    void aPodNameAndAHostNameAreBothAcceptedUnchanged() {
        for (String name : new String[] {"ravenroot-7d9f4b8c6d-x2k9p", "worker_01", "db.internal.example"}) {
            assertEquals(name, WorkerIdentity.of(name, WorkerIdentity.Role.RUNTIME).replicaName());
        }
    }
}
