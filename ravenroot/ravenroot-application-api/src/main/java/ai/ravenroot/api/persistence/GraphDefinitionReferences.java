package ai.ravenroot.api.persistence;

/**
 * Answers whether retained work still reaches one stored graph definition.
 *
 * <h2>Reachability, not a reference count</h2>
 * <p>Retention is decided by asking this question inside the removal transaction, never by
 * maintaining a stored counter. A counter is a second copy of a fact the referencing tables already
 * hold, and every crash between incrementing it and writing the reference — or between deleting the
 * reference and decrementing it — leaves the two disagreeing, with no way to tell which is right.
 * The execution store declines instance archival for exactly this reason. Recomputing reachability
 * is more expensive and cannot drift.</p>
 *
 * <h2>What is and is not covered today, stated precisely</h2>
 * <p>An adapter co-located with durable execution state answers this from that state, so a
 * definition referenced by a retained or recoverable execution is never removed. That is the
 * guarantee this contract makes.</p>
 *
 * <p>It is <strong>not</strong> yet a guarantee about every class of reference. Deployments,
 * continuations, execution results and audit obligations are not durable in this build — the
 * deployment registry and the result registry hold their state in memory, and audit trails live
 * outside the store entirely — so no adapter can compute reachability from them, and none claims to.
 * An implementation may compose additional reference sources through this interface as those
 * reference classes become durable, and the removal decision stays a conjunction: a definition is
 * removable only when <em>every</em> consulted source reports it unreachable.</p>
 */
@FunctionalInterface
public interface GraphDefinitionReferences {

    /** Reports every definition as unreferenced. Composed with, never substituted for, a durable source. */
    GraphDefinitionReferences NONE = key -> false;

    /**
     * Reports whether any retained work still reaches this definition.
     *
     * <p>Called inside the removal transaction. An implementation that cannot answer must report
     * {@code true}: refusing to remove a definition that turns out to be unreferenced costs disk,
     * while removing one that turns out to be referenced costs an unrecoverable execution.</p>
     *
     * @param key tenant-scoped address of the definition being considered for removal.
     * @return whether retained work still references it.
     */
    boolean isReferenced(GraphDefinitionKey key);
}
