package ai.ravenroot.api.persistence;

/**
 * Every way an {@link ExecutionManifestStore} operation can fail, as a closed classification.
 *
 * <p>Sealed for the reason {@link GraphDefinitionStoreFailure} is sealed: a caller must be told when
 * a new failure kind appears rather than absorbing it into a default branch, and a recovery path
 * deciding between refusing, parking and retrying cannot make that decision from a message string.
 * {@link Retryability} is reused unchanged, so a caller composing all three persistence ports
 * reasons about one retry vocabulary instead of three.</p>
 */
public sealed interface ExecutionManifestStoreFailure {

    /**
     * Retry guidance for this classification.
     *
     * @return whether repeating the operation is safe, useless, or of unknown effect.
     */
    Retryability retryability();

    /**
     * A bounded, operator-facing description carrying no protected configuration.
     *
     * @return stable single-line description of this failure.
     */
    String describe();

    /**
     * No manifest is stored for this execution, including when it belongs to another tenant.
     *
     * <p>Reported rather than a denial for the reason {@link GraphDefinitionKey} gives: a store that
     * answered "forbidden" for another tenant's execution would be a cross-tenant existence oracle.
     * It is also the classification an execution accepted before manifests existed produces, and
     * that execution does not become recoverable by upgrading.</p>
     *
     * @param key tenant-scoped execution whose manifest is absent.
     */
    record NotFound(ExecutionKey key) implements ExecutionManifestStoreFailure {

        /**
         * Repeating the read cannot make an absent manifest present.
         *
         * @return {@link Retryability#DETERMINISTIC_REJECT}.
         */
        @Override
        public Retryability retryability() {
            return Retryability.DETERMINISTIC_REJECT;
        }

        /**
         * Names the execution whose manifest is absent.
         *
         * @return stable single-line description of this failure.
         */
        @Override
        public String describe() {
            return "no execution manifest is stored for process instance "
                    + key.processInstanceId() + " in this tenant";
        }
    }

    /**
     * The stored fields no longer digest to the address recorded beside them.
     *
     * <p>Distinct from {@link Corrupted}: the row was readable and reconstructed into a manifest,
     * and it is the integrity check that failed. That distinction is what tells an operator whether
     * to suspect storage damage or an edited row.</p>
     *
     * @param key tenant-scoped execution whose manifest failed verification.
     * @param observedDigest digest the stored fields actually derive to.
     */
    record DigestMismatch(ExecutionKey key, String observedDigest)
            implements ExecutionManifestStoreFailure {

        /**
         * Repeating the read returns the same mismatched content.
         *
         * @return {@link Retryability#DETERMINISTIC_REJECT}.
         */
        @Override
        public Retryability retryability() {
            return Retryability.DETERMINISTIC_REJECT;
        }

        /**
         * Names the execution and the digest its stored fields actually derive to.
         *
         * @return stable single-line description of this failure.
         */
        @Override
        public String describe() {
            return "the stored execution manifest for process instance " + key.processInstanceId()
                    + " digests to " + observedDigest + ", which is not the address recorded beside it";
        }
    }

    /**
     * The row exists but cannot be read back as a manifest at all.
     *
     * @param key tenant-scoped execution whose manifest could not be reconstructed.
     * @param reason bounded description of what could not be reconstructed.
     */
    record Corrupted(ExecutionKey key, String reason) implements ExecutionManifestStoreFailure {

        /**
         * Repeating the read returns the same unreadable row.
         *
         * @return {@link Retryability#DETERMINISTIC_REJECT}.
         */
        @Override
        public Retryability retryability() {
            return Retryability.DETERMINISTIC_REJECT;
        }

        /**
         * Names the execution and what could not be reconstructed.
         *
         * @return stable single-line description of this failure.
         */
        @Override
        public String describe() {
            return "the stored execution manifest for process instance " + key.processInstanceId()
                    + " cannot be read back as a manifest: " + reason;
        }
    }

    /**
     * A different manifest is already pinned for this execution.
     *
     * <p>A manifest is write-once for the same reason {@link GraphVersionPin} is: an execution whose
     * pinned dependencies could be replaced after acceptance is an execution whose recovery has no
     * fixed meaning. A byte-identical repeat is not a conflict and converges silently.</p>
     *
     * @param key tenant-scoped execution that is already pinned.
     * @param storedDigest address of the manifest already pinned for it.
     */
    record ManifestConflict(ExecutionKey key, ExecutionManifestDigest storedDigest)
            implements ExecutionManifestStoreFailure {

        /**
         * Repeating the write presents the same disagreeing content.
         *
         * @return {@link Retryability#DETERMINISTIC_REJECT}.
         */
        @Override
        public Retryability retryability() {
            return Retryability.DETERMINISTIC_REJECT;
        }

        /**
         * Names the execution and the manifest already pinned for it.
         *
         * @return stable single-line description of this failure.
         */
        @Override
        public String describe() {
            return "process instance " + key.processInstanceId()
                    + " is already pinned to execution manifest " + storedDigest.value()
                    + "; a manifest is write-once";
        }
    }

    /**
     * The request could not be accepted as a manifest operation at all.
     *
     * @param reason bounded description of what made the request unusable.
     */
    record InvalidRequest(String reason) implements ExecutionManifestStoreFailure {

        /**
         * Repeating an unusable request produces the same refusal.
         *
         * @return {@link Retryability#DETERMINISTIC_REJECT}.
         */
        @Override
        public Retryability retryability() {
            return Retryability.DETERMINISTIC_REJECT;
        }

        /**
         * Names what made the request unusable.
         *
         * @return stable single-line description of this failure.
         */
        @Override
        public String describe() {
            return "the execution manifest request is not usable: " + reason;
        }
    }

    /**
     * Removal was refused because retained work still needs the manifest.
     *
     * <p>Refusing is the point: a caller answered with silence could not tell refusal from success,
     * and a manifest an execution can still be recovered against must outlive every request to
     * delete it.</p>
     *
     * @param key tenant-scoped execution whose manifest is still needed.
     */
    record StillReferenced(ExecutionKey key) implements ExecutionManifestStoreFailure {

        /**
         * Repeating the removal is refused again while the work is still retained.
         *
         * @return {@link Retryability#DETERMINISTIC_REJECT}.
         */
        @Override
        public Retryability retryability() {
            return Retryability.DETERMINISTIC_REJECT;
        }

        /**
         * Names the execution whose manifest may not be removed.
         *
         * @return stable single-line description of this failure.
         */
        @Override
        public String describe() {
            return "the execution manifest for process instance " + key.processInstanceId()
                    + " is still needed by retained work and was not removed";
        }
    }

    /**
     * The caller is not permitted to perform this manifest operation.
     *
     * @param reason bounded description carrying no protected configuration.
     */
    record NotAuthorized(String reason) implements ExecutionManifestStoreFailure {

        /**
         * Repeating an unauthorized operation produces the same refusal.
         *
         * @return {@link Retryability#DETERMINISTIC_REJECT}.
         */
        @Override
        public Retryability retryability() {
            return Retryability.DETERMINISTIC_REJECT;
        }

        /**
         * Names why the operation was refused.
         *
         * @return stable single-line description of this failure.
         */
        @Override
        public String describe() {
            return "the execution manifest operation was not authorized: " + reason;
        }
    }

    /**
     * The adapter could not be reached, and provably did nothing.
     *
     * @param reason bounded description of what was unreachable.
     */
    record Unavailable(String reason) implements ExecutionManifestStoreFailure {

        /**
         * Nothing was written, so repeating the operation is safe.
         *
         * @return {@link Retryability#RETRYABLE_NO_EFFECT}.
         */
        @Override
        public Retryability retryability() {
            return Retryability.RETRYABLE_NO_EFFECT;
        }

        /**
         * Names what was unreachable.
         *
         * @return stable single-line description of this failure.
         */
        @Override
        public String describe() {
            return "the execution manifest store is unavailable: " + reason;
        }
    }

    /**
     * The operation's outcome is unknown, so it may or may not have been applied.
     *
     * <p>Safe to repeat only because a manifest write is idempotent by content: a repeat after an
     * unknown outcome converges on exactly one pinned manifest, or reports
     * {@link ManifestConflict} if the content disagrees.</p>
     *
     * @param key tenant-scoped execution the operation addressed.
     * @param reason bounded description of why the outcome is unknown.
     */
    record OutcomeUnknown(ExecutionKey key, String reason) implements ExecutionManifestStoreFailure {

        /**
         * The write may or may not have landed, so a caller must resolve rather than assume.
         *
         * @return {@link Retryability#INDETERMINATE}.
         */
        @Override
        public Retryability retryability() {
            return Retryability.INDETERMINATE;
        }

        /**
         * Names the execution and why the outcome is unknown.
         *
         * @return stable single-line description of this failure.
         */
        @Override
        public String describe() {
            return "the outcome of the execution manifest operation for process instance "
                    + key.processInstanceId() + " is unknown: " + reason;
        }
    }
}
