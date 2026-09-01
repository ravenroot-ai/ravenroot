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
                "session", SourceSessionState.LISTENING, 1, Optional.of("not an incident")));
        assertThrows(IllegalArgumentException.class, () -> SourceSessionStatus.of(
                "session", SourceSessionState.STARTING, 0));
    }
}
