package ai.ravenroot.api.programming;

/**
 * Verifies that an artifact's provenance is authentic, immediately before its source is released for
 * execution (SEC-12).
 *
 * <p><b>Core ships no verifying implementation, and that is the decision rather than an omission.</b>
 * Verifying provenance needs a key; ADR 0018's documented contract puts cache, TTL, rotation and revocation
 * of key material with whoever implements the backend and keeps only the read-side indirection in
 * core. The sandbox supervisor uses the same division: the integrator
 * supplies the implementation, this project supplies the contract and a conformance suite. So this
 * interface names <em>where</em> verification happens and <em>what it must decide</em>, and holds no
 * key type, no algorithm, no rotation schedule and no revocation list.
 *
 * <p><b>Where it is called, and why nowhere else will do.</b> Implementations are invoked inside
 * {@link ProgramAdmission#redeem()}, after the ownership, state and revision gates and before the
 * artifact is returned. Not beside redemption — beside it would re-create the interval ADR 0020
 * exists to eliminate, and a signature checked a few hundred milliseconds before the source is
 * written is a signature checked on a different question than the one being asked.
 *
 * <p><b>The default is {@link #refusing()} and the absence of a verifier is not permission.</b>
 * Programmable execution is already off unless an integrator deliberately installs a sandbox runtime
 * adapter; it is also off unless they deliberately install a verifier. This mirrors
 * {@code DisabledProgramRuntime} and {@code RejectingAuthenticator}: an absent adapter refuses rather
 * than waves through. Core deliberately ships no permissive implementation at all, so any deployment
 * that chooses to skip verification has to write that choice down somewhere a reviewer can grep for.
 */
@FunctionalInterface
public interface ArtifactProvenanceVerifier {
    /**
     * Accepts the artifact, or throws to refuse it.
     *
     * <p>Refusal must throw {@link SecurityException}. Returning normally asserts that this artifact,
     * at this revision, is the one whose provenance was signed — not merely that some artifact was.
     *
     * @throws SecurityException if the provenance is absent, unverifiable, or does not bind this
     *                           artifact and revision
 * @param artifact exact artifact revision being redeemed for sandbox execution.
     */
    void verify(GeneratedArtifact artifact);

    /**
     * The fail-closed default: refuses every artifact.
     *
     * <p>Not a placeholder to be replaced by a permissive one. A deployment that has not supplied a
     * verifier has not established that anything it is about to execute is authentic, and executing
     * anyway would make the whole admission path decorative.
 * @return verifier that rejects every artifact until an integrator installs a real verifier.
     */
    static ArtifactProvenanceVerifier refusing() {
        return artifact -> {
            throw new SecurityException("No artifact provenance verifier is configured, so the "
                    + "provenance of " + (artifact == null ? "the artifact" : artifact.id())
                    + " cannot be established; refusing to execute it");
        };
    }
}
