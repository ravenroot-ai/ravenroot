package ai.ravenroot.api.persistence;

/**
 * Answers whether retained work still needs one pinned execution manifest.
 *
 * <h2>Reachability, not a reference count</h2>
 * <p>Retention is decided by asking this question inside the removal transaction, for the reason
 * {@link GraphDefinitionReferences} states: a stored counter is a second copy of a fact the
 * referencing table already holds, and every crash between the two leaves them disagreeing with no
 * way to tell which is right.</p>
 *
 * <h2>What the answer covers here, and why it is a stronger guarantee than the definition case</h2>
 * <p>A manifest is keyed by the very execution that needs it, so an adapter co-located with durable
 * execution state answers this by asking whether that one process instance is still retained —
 * rather than by searching for any execution that might reach a shared row. There is no fan-in to
 * lose track of, so a manifest cannot be removed while its execution remains recoverable or
 * retained. An adapter that cannot answer must report {@code true}: refusing to remove a manifest
 * whose execution is gone costs a row, while removing one whose execution remains costs an
 * unrecoverable execution.</p>
 */
@FunctionalInterface
public interface ExecutionManifestReferences {

    /** Reports every manifest as unreferenced. Composed with, never substituted for, a durable source. */
    ExecutionManifestReferences NONE = key -> false;

    /**
     * Reports whether retained work still needs this manifest.
     *
     * @param key tenant-scoped execution whose manifest is being considered for removal.
     * @return whether retained or recoverable work still needs it.
     */
    boolean isReferenced(ExecutionKey key);
}
