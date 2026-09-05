package ai.ravenroot.core.recovery;

import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionOrigin;
import ai.ravenroot.api.persistence.ProcessInventoryEntry;

/**
 * One discovered process instance paired with this deployment's verdict about rebuilding it.
 *
 * <h2>Why the inventory row is carried rather than summarised</h2>
 * <p>The row is what distinguishes a transient submission from deployment-hosted work: a hosted
 * instance carries a deployment identity and a workload identity in its {@link ExecutionOrigin} and
 * a transient one carries neither. Both are discovered and classified by the same authority, and
 * flattening the row into a key would erase the only thing that says which is which — after which
 * an operator reading the startup report could not tell a stuck deployment from a stuck one-off, and
 * neither could a caller filtering the cohort by deployment.</p>
 *
 * @param entry          the durable inventory row this verdict was reached from.
 * @param classification whether this deployment may rebuild the instance, and why not when it may not.
 */
public record RecoveryCandidate(ProcessInventoryEntry entry, RecoveryClassification classification) {

    /** Rejects a pairing whose verdict is about a different instance than the row it is filed under. */
    public RecoveryCandidate {
        if (entry == null) throw new IllegalArgumentException("entry cannot be null");
        if (classification == null) throw new IllegalArgumentException("classification cannot be null");
        if (!entry.key().equals(classification.key())) {
            throw new IllegalArgumentException("the classification does not describe this inventory row");
        }
    }

    /**
     * Returns the classified instance.
     *
     * @return tenant-scoped identity of the discovered process instance.
     */
    public ExecutionKey key() {
        return entry.key();
    }

    /**
     * Returns the deployment and workload relationship this instance was accepted under.
     *
     * @return the instance's origin; a transient submission reports no deployment and no workload.
     */
    public ExecutionOrigin origin() {
        return entry.origin();
    }

    /**
     * Returns whether this deployment may rebuild a runner for the instance.
     *
     * @return {@code true} when the pinned definition and manifest both resolved here.
     */
    public boolean rehydratable() {
        return classification.rehydratable();
    }
}
