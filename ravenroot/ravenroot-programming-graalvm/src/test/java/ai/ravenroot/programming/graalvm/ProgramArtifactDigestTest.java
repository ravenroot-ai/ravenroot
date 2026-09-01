package ai.ravenroot.programming.graalvm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProgramArtifactDigestTest {
    @Test
    void isolatedWorkerImplementationMatchesTheV1ApplicationIdentityVector() {
        assertEquals("d2fcd564a0909d353d4cf2ed12d79fc7ba8510b66530a3afe0014ff62cd93f27",
                ProgramArtifactDigest.canonical("javascript", "payload => payload"));
    }
}
