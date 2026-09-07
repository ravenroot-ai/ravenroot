package ai.ravenroot.api.persistence;

import java.util.Objects;

/**
 * Explicit configuration provenance for one immutable agent root. These trusted fingerprints are
 * equality evidence supplied by the runtime, not signatures or authority granted by a caller.
 *
 * @param root root registered with the fingerprints
 * @param policyFingerprint lowercase SHA-256 of the canonical authority policy
 * @param rateCardFingerprint lowercase SHA-256 of the canonical rate card
 */
public record PinnedAgentAuthorityRoot(AgentAuthorityRootRegistration root,
                                       String policyFingerprint, String rateCardFingerprint) {
    public PinnedAgentAuthorityRoot {
        Objects.requireNonNull(root, "root");
        fingerprint(policyFingerprint, "policyFingerprint");
        fingerprint(rateCardFingerprint, "rateCardFingerprint");
    }

    private static void fingerprint(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 fingerprint");
        }
    }
}
