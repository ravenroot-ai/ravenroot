package ai.ravenroot.api.application;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SourceSessionStatusTest {
    @Test
    void diagnosticsAreBoundedAndOnlyIncidentStatesMayCarryThem() {
        SourceSessionStatus degraded = SourceSessionStatus.of(
                "session", SourceSessionState.DEGRADED, 1, "x".repeat(1_000));
        assertEquals(SourceSessionStatus.MAX_DIAGNOSTIC_CHARACTERS,
                degraded.diagnostic().orElseThrow().length());

        assertThrows(IllegalArgumentException.class, () -> new SourceSessionStatus(
                "session", "session", SourceSessionState.LISTENING, 1, Optional.of("not an incident")));
        assertThrows(IllegalArgumentException.class, () -> SourceSessionStatus.of(
                "session", SourceSessionState.STARTING, 0));
    }

    /**
     * The status is the only place a client can learn which deployment a listening session's
     * traversals belong to, and a session emits those traversals with ids nobody can know in
     * advance. A blank value would leave the client with nothing to attribute them by — the exact
     * failure this component exists to remove — so it is refused rather than carried.
     */
    @Test
    void aSessionMustNameTheDeploymentItsTraversalsBelongTo() {
        assertThrows(IllegalArgumentException.class, () -> new SourceSessionStatus(
                "session", null, SourceSessionState.LISTENING, 1, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new SourceSessionStatus(
                "session", "  ", SourceSessionState.LISTENING, 1, Optional.empty()));
    }

    /** The short factories say what the reference implementation does: the session is its own deployment. */
    @Test
    void theShortFactoriesDeployASessionUnderItsOwnId() {
        assertEquals("session", SourceSessionStatus.of("session", SourceSessionState.LISTENING, 1)
                .deploymentId());
        assertEquals("session", SourceSessionStatus.of("session", SourceSessionState.FAILED, 1, "boom")
                .deploymentId());
    }

    /** An implementation whose sessions run under a separately named deployment can say so. */
    @Test
    void aSeparatelyNamedDeploymentIsCarriedThrough() {
        assertEquals("deployment-7", SourceSessionStatus
                .of("session", "deployment-7", SourceSessionState.LISTENING, 4).deploymentId());
        assertEquals("deployment-7", SourceSessionStatus
                .of("session", "deployment-7", SourceSessionState.DEGRADED, 4, "half up").deploymentId());
    }
}
