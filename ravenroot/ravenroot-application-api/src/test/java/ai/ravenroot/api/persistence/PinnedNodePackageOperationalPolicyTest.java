package ai.ravenroot.api.persistence;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PinnedNodePackageOperationalPolicyTest {
    private static final String POLICY_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String POLICY_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    void emptyPolicyPreservesTheHistoricalIdentityExactly() {
        assertEquals(PinnedNodePackage.of("example.nodes", "1", "sdk"),
                PinnedNodePackage.of("example.nodes", "1", "sdk", Optional.empty()));
    }

    @Test
    void differentOperationalPoliciesProduceDifferentRuntimeIdentities() {
        var first = PinnedNodePackage.of("example.nodes", "1", "sdk", Optional.of(POLICY_A));
        var same = PinnedNodePackage.of("example.nodes", "1", "sdk", Optional.of(POLICY_A));
        var changed = PinnedNodePackage.of("example.nodes", "1", "sdk", Optional.of(POLICY_B));
        assertEquals(first, same);
        assertNotEquals(first, changed);
    }

    @Test
    void malformedPolicyDigestsFailClosed() {
        assertThrows(IllegalArgumentException.class, () ->
                PinnedNodePackage.of("example.nodes", "1", "sdk", Optional.of("not-a-digest")));
    }
}
