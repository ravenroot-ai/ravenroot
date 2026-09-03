package ai.ravenroot.api.persistence;

/**
 * The single classification vocabulary every {@link GraphDefinitionStore} adapter must use.
 *
 * <h2>Why this is a separate taxonomy rather than new members on the execution-store one</h2>
 * <p>Three reasons, and the first alone is decisive. Every member of
 * {@link ExecutionStoreFailure} is keyed by an {@link ExecutionKey} or by a journal concept, and a
 * definition failure has neither — there is no process instance to name when a document's digest
 * does not verify. Second, {@code ExecutionStoreFailure} is a public sealed interface, so adding a
 * permitted subtype to it would break every exhaustive switch an integrator has already written
 * against it. Third, the execution store's own contract states that it does not store graph bytes
 * and does not define graph identity; giving it failure members about both would contradict the
 * boundary it publishes.</p>
 *
 * <p>{@link Retryability} is reused unchanged. A second retry vocabulary would force a caller
 * handling both stores to translate between two enums that mean the same four things.</p>
 *
 * <h2>Failing closed</h2>
 * <p>Nothing here is retryable except {@link Unavailable}, and nothing here is ambiguous except
 * {@link OutcomeUnknown}. A definition that cannot be verified is never returned, never partially
 * returned and never returned with a warning: recovery replays a graph against these bytes, so a
 * caller that received unverified content would execute it.</p>
 */
public sealed interface GraphDefinitionStoreFailure {

    /**
     * Returns whether retrying this classified failure can be meaningful.
     *
     * @return retry guidance for callers and adapters.
     */
    Retryability retryability();

    /**
     * Returns a bounded, human-readable diagnosis.
     *
     * <p>Never contains document bytes. A graph definition is authored content and this string
     * reaches logs and operator-facing diagnostics.</p>
     *
     * @return content-safe human-readable diagnosis of this failure.
     */
    String describe();

    /**
     * No definition is stored at this address for this tenant.
     *
     * <p>This is also the answer to a read scoped to the wrong tenant, whether or not some other
     * tenant holds the same content. A denial would confirm the existence of another tenant's
     * document to a caller who already holds its bytes.</p>
     *
     * @param key tenant-scoped address that resolved to nothing observable.
     */
    record NotFound(GraphDefinitionKey key) implements GraphDefinitionStoreFailure {

        /**
         * Classifies an absent definition as a deterministic rejection.
         *
         * @return {@link Retryability#DETERMINISTIC_REJECT}.
         */
        @Override
        public Retryability retryability() {
            return Retryability.DETERMINISTIC_REJECT;
        }

        /**
         * Names the address that resolved to nothing.
         *
         * @return diagnosis naming the missing content address.
         */
        @Override
        public String describe() {
            return "no graph definition is stored at " + key.contentId().value() + " for this tenant";
        }
    }

    /**
     * The stored bytes did not hash to the address they are filed under.
     *
     * <p>This is the assertion the store exists to make. It separates a document that was corrupted
     * after it was written from one that never reconstructed at all, and it must never be swallowed,
     * retried or answered with the bytes as found.</p>
     *
     * @param key tenant-scoped address the definition is filed under.
     * @param observedDigest lowercase hexadecimal digest the stored bytes actually hash to.
     */
    record DigestMismatch(GraphDefinitionKey key, String observedDigest)
            implements GraphDefinitionStoreFailure {

        /**
         * Classifies a digest mismatch as a deterministic rejection.
         *
         * @return {@link Retryability#DETERMINISTIC_REJECT}.
         */
        @Override
        public Retryability retryability() {
            return Retryability.DETERMINISTIC_REJECT;
        }

        /**
         * Names both the expected and the observed digest.
         *
         * @return diagnosis naming the address and the digest the bytes actually produce.
         */
        @Override
        public String describe() {
            return "the graph definition stored at " + key.contentId().value()
                    + " hashes to " + observedDigest + "; the stored bytes are not the bytes filed here";
        }
    }

    /**
     * A stored row could not be read back as a legal definition at all.
     *
     * <p>Distinct from {@link DigestMismatch}, which is a definite verdict about definite bytes.
     * This is the verdict when there are no usable bytes to judge: a truncated row, an impossible
     * format version, an address that is not a digest.</p>
     *
     * @param key tenant-scoped address of the unreadable row.
     * @param reason bounded machine-readable reason the row did not reconstruct.
     */
    record Corrupted(GraphDefinitionKey key, String reason) implements GraphDefinitionStoreFailure {

        /**
         * Classifies unreadable stored state as a deterministic rejection.
         *
         * @return {@link Retryability#DETERMINISTIC_REJECT}.
         */
        @Override
        public Retryability retryability() {
            return Retryability.DETERMINISTIC_REJECT;
        }

        /**
         * Names the address and why the row did not reconstruct.
         *
         * @return diagnosis naming the unreadable definition.
         */
        @Override
        public String describe() {
            return "the graph definition stored at " + key.contentId().value()
                    + " did not reconstruct into a legal definition: " + reason;
        }
    }

    /**
     * The definition exceeds the bound this adapter publishes through
     * {@link GraphDefinitionStore#maxDefinitionBytes()}.
     *
     * <p>Checked before any transaction opens, so nothing was written and nothing was locked.</p>
     *
     * @param actualBytes size of the rejected canonical document.
     * @param limitBytes largest definition this adapter accepts.
     */
    record DefinitionTooLarge(int actualBytes, int limitBytes) implements GraphDefinitionStoreFailure {

        /**
         * Classifies an oversized definition as a deterministic rejection.
         *
         * @return {@link Retryability#DETERMINISTIC_REJECT}.
         */
        @Override
        public Retryability retryability() {
            return Retryability.DETERMINISTIC_REJECT;
        }

        /**
         * States the size and the bound, so an operator can act without inspecting the document.
         *
         * @return diagnosis naming the rejected size and the adapter's limit.
         */
        @Override
        public String describe() {
            return "the graph definition is " + actualBytes + " bytes; this store accepts at most "
                    + limitBytes;
        }
    }

    /**
     * The request itself is not legal, independently of anything stored.
     *
     * @param reason bounded machine-readable reason the request was rejected.
     */
    record InvalidRequest(String reason) implements GraphDefinitionStoreFailure {

        /**
         * Classifies an illegal request as a deterministic rejection.
         *
         * @return {@link Retryability#DETERMINISTIC_REJECT}.
         */
        @Override
        public Retryability retryability() {
            return Retryability.DETERMINISTIC_REJECT;
        }

        /**
         * States why the request was refused.
         *
         * @return diagnosis naming the rejected request.
         */
        @Override
        public String describe() {
            return "the graph definition request is not legal: " + reason;
        }
    }

    /**
     * A logical graph version already names different content.
     *
     * <p>This is the enforcement point for immutability of a version. Storing the same document
     * under a second logical identity is legal and deduplicates onto one stored copy; rebinding one
     * logical identity to a second document is not, because an execution pinned to that version
     * would silently change what it replays.</p>
     *
     * @param tenantId tenant that owns both bindings.
     * @param identity logical graph version that is already bound.
     * @param boundContentId address the version is already bound to.
     * @param requestedContentId address the rejected request tried to bind it to.
     */
    record IdentityConflict(String tenantId, GraphDefinitionIdentity identity,
                            GraphContentId boundContentId, GraphContentId requestedContentId)
            implements GraphDefinitionStoreFailure {

        /**
         * Classifies an attempted rebinding as a deterministic rejection.
         *
         * @return {@link Retryability#DETERMINISTIC_REJECT}.
         */
        @Override
        public Retryability retryability() {
            return Retryability.DETERMINISTIC_REJECT;
        }

        /**
         * Names the version and both addresses.
         *
         * @return diagnosis naming the immutable version that would have been rebound.
         */
        @Override
        public String describe() {
            return "graph version " + identity.graphId() + "/" + identity.versionId()
                    + " is already bound to " + boundContentId.value()
                    + " and cannot be rebound to " + requestedContentId.value();
        }
    }

    /**
     * The definition is still reachable from retained durable work and must not be removed.
     *
     * <p>Retention refuses rather than reports a count of zero, because a caller that asked to purge
     * and was told "nothing removed" cannot tell a store with nothing to remove from a store that
     * declined to remove something.</p>
     *
     * @param key tenant-scoped address of the definition that was not removed.
     */
    record StillReferenced(GraphDefinitionKey key) implements GraphDefinitionStoreFailure {

        /**
         * Classifies a refused removal as a deterministic rejection.
         *
         * @return {@link Retryability#DETERMINISTIC_REJECT}.
         */
        @Override
        public Retryability retryability() {
            return Retryability.DETERMINISTIC_REJECT;
        }

        /**
         * Names the definition retention declined to remove.
         *
         * @return diagnosis naming the still-referenced definition.
         */
        @Override
        public String describe() {
            return "the graph definition at " + key.contentId().value()
                    + " is still referenced by retained work and was not removed";
        }
    }

    /**
     * The caller is not permitted to perform this operation on this store.
     *
     * @param reason bounded machine-readable reason authorization failed.
     */
    record NotAuthorized(String reason) implements GraphDefinitionStoreFailure {

        /**
         * Classifies an authorization failure as a deterministic rejection.
         *
         * @return {@link Retryability#DETERMINISTIC_REJECT}.
         */
        @Override
        public Retryability retryability() {
            return Retryability.DETERMINISTIC_REJECT;
        }

        /**
         * States that the operation was refused, without naming what exists.
         *
         * @return diagnosis of the authorization failure.
         */
        @Override
        public String describe() {
            return "the graph definition store refused the operation: " + reason;
        }
    }

    /**
     * The store could not be reached, and the write is known not to have applied.
     *
     * @param reason bounded machine-readable reason the store was unreachable.
     */
    record Unavailable(String reason) implements GraphDefinitionStoreFailure {

        /**
         * Classifies an unreachable store as safely retryable.
         *
         * @return {@link Retryability#RETRYABLE_NO_EFFECT}.
         */
        @Override
        public Retryability retryability() {
            return Retryability.RETRYABLE_NO_EFFECT;
        }

        /**
         * States why the store could not be reached.
         *
         * @return diagnosis of the unavailability.
         */
        @Override
        public String describe() {
            return "the graph definition store is unavailable: " + reason;
        }
    }

    /**
     * The write may or may not have applied.
     *
     * <p>Recovering from this is a read, never an assumption. Because a definition write is
     * content-addressed and therefore idempotent, re-reading the address settles it completely: the
     * definition is either there and verifies, or it is absent and the write can be repeated.</p>
     *
     * @param key tenant-scoped address whose write outcome is unknown.
     * @param reason bounded machine-readable reason the outcome could not be established.
     */
    record OutcomeUnknown(GraphDefinitionKey key, String reason)
            implements GraphDefinitionStoreFailure {

        /**
         * Classifies an unresolved write as indeterminate.
         *
         * @return {@link Retryability#INDETERMINATE}.
         */
        @Override
        public Retryability retryability() {
            return Retryability.INDETERMINATE;
        }

        /**
         * Names the address the caller must re-read to settle the outcome.
         *
         * @return diagnosis naming the unresolved write.
         */
        @Override
        public String describe() {
            return "the outcome of writing the graph definition at " + key.contentId().value()
                    + " is unknown: " + reason + " (re-read the address to settle it)";
        }
    }
}
