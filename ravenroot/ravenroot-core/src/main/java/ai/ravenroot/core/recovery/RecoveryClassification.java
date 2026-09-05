package ai.ravenroot.core.recovery;

import ai.ravenroot.api.persistence.ExecutionKey;

/**
 * Whether one persisted process instance can be rebuilt by this deployment, and when it cannot, why.
 *
 * <h2>Why the verdict is a value rather than a boolean</h2>
 * <p>The four ways a rebuild is refused call for different operator actions, and collapsing them
 * loses exactly the distinction that decides what to do. A definition or manifest that will never
 * resolve is a deployment fact somebody has to act on; an answer that is merely not available yet is
 * a wait. A boolean would report both as "no" and leave an operator watching an item that is either
 * about to recover on its own or never will, with nothing saying which.</p>
 *
 * <h2>What a refusal is not</h2>
 * <p>A refusal is not a park and is not an acknowledgement. Nothing here writes to the execution
 * store; a refused item is left exactly as it was found, still claimable, so the durable wait
 * survives the refusal. That is what fail-closed means on this path: the work is withheld from
 * dispatch rather than disposed of.</p>
 *
 * <p>Sealed so a later verdict cannot be added without every consumer being told.</p>
 */
public sealed interface RecoveryClassification {

    /**
     * Returns the instance this verdict is about.
     *
     * @return tenant-scoped identity of the classified process instance.
     */
    ExecutionKey key();

    /**
     * Returns whether this deployment may rebuild a runner for the instance.
     *
     * @return {@code true} only for {@link Rehydratable}.
     */
    boolean rehydratable();

    /**
     * Returns a bounded, operator-facing diagnosis, empty when nothing was refused.
     *
     * <p>Never contains graph bytes, payloads, or a stored document's content: this string reaches
     * startup logs, and the definition and manifest failure taxonomies both promise the same of
     * their own {@code describe()}, which is where the text comes from.</p>
     *
     * @return content-safe explanation of a refusal, or the empty string.
     */
    String detail();

    /**
     * The pinned graph definition rebuilt and, where a manifest store is composed, the manifest this
     * execution was accepted against still describes what this deployment resolves now.
     *
     * @param key the classified process instance.
     */
    record Rehydratable(ExecutionKey key) implements RecoveryClassification {

        /**
         * Confirms the instance may be rebuilt.
         *
         * @return always {@code true}.
         */
        @Override
        public boolean rehydratable() {
            return true;
        }

        /**
         * Reports that nothing was refused.
         *
         * @return the empty string.
         */
        @Override
        public String detail() {
            return "";
        }
    }

    /**
     * The instance was not rebuilt, and dispatch stays closed for it.
     *
     * @param key    the classified process instance.
     * @param reason which of the four refusals this is.
     * @param detail bounded operator-facing diagnosis.
     */
    record Refused(ExecutionKey key, Reason reason, String detail) implements RecoveryClassification {

        /** Rejects a refusal that names no reason or carries no diagnosis. */
        public Refused {
            if (key == null) throw new IllegalArgumentException("key cannot be null");
            if (reason == null) throw new IllegalArgumentException("reason cannot be null");
            if (detail == null || detail.isBlank()) {
                throw new IllegalArgumentException("a refusal must carry a diagnosis");
            }
        }

        /**
         * Confirms the instance may not be rebuilt.
         *
         * @return always {@code false}.
         */
        @Override
        public boolean rehydratable() {
            return false;
        }
    }

    /** The closed set of reasons a rebuild is withheld. */
    enum Reason {

        /**
         * The pinned graph definition is absent, does not hash to the address it is filed under, or
         * did not read back as a legal document. Deterministic: it will refuse identically next time.
         */
        DEFINITION_UNRESOLVED,

        /**
         * The execution manifest is absent, does not verify, or did not read back at all.
         * Deterministic. An execution accepted before this deployment began pinning manifests
         * arrives here rather than being rebuilt from today's environment.
         */
        MANIFEST_UNRESOLVED,

        /**
         * The manifest resolved and this deployment resolves something different. The detail names
         * the differing dimensions and never what they changed to.
         */
        MANIFEST_INCOMPATIBLE,

        /**
         * The answer is not known yet — a store that is briefly unreachable, or a write whose
         * outcome is undecided. Distinct from the three above because it is expected to resolve
         * itself, and reporting it as a deployment fault would send an operator after nothing.
         */
        UNAVAILABLE
    }
}
