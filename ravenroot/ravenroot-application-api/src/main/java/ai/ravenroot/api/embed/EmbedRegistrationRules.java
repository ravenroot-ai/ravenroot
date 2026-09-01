package ai.ravenroot.api.embed;

import java.util.Objects;

/**
 * The one place a provision command is judged, shared by every adapter.
 *
 * <p>Two adapters that each implement «only PUBLISHED or ACTIVE snapshots, coherent digest, allowed
 * eligibility» are two chances to implement it differently, and the durable one is the one an
 * operator will actually be running. Keeping the gate here means the in-memory adapter used by tests
 * and the SQLite adapter used in production refuse the same commands for the same reasons, and means
 * a mutation to this method turns both adapters' tests red at once.</p>
 */
public final class EmbedRegistrationRules {

    private EmbedRegistrationRules() {
    }

    /** Evaluates lifecycle, capability, coherence, policy, and budget gates for a provision.
     * @param command candidate operator provision command
     * @param budget size ceiling that must accept the captured projection
     * @return reason to refuse {@code command}, or {@code null} if it may be written
     */
    public static EmbedProvisionOutcome.Reason rejectionOf(EmbedProvisionCommand command,
                                                           EmbedProjectionBudget budget) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(budget, "budget");
        if (command.snapshotLifecycle() != EmbedSnapshotLifecycle.PUBLISHED
                && command.snapshotLifecycle() != EmbedSnapshotLifecycle.ACTIVE) {
            return EmbedProvisionOutcome.Reason.SNAPSHOT_NOT_PUBLISHED;
        }
        if (!command.capabilities().contains(EmbedCapability.GRAPH_READ)) {
            return EmbedProvisionOutcome.Reason.CAPABILITY_MISSING;
        }
        VerifiedEmbedGraphGrant graph = command.graphGrant();
        EmbedGraphProjection projection = command.projection();
        // Tenant is checked in both directions on purpose: the command's tenant is the operator's
        // authorization scope, and the grant's is what the browser boundary will compare against.
        // A command in which they differ is a command that would authorize a different tenant's read.
        if (!command.tenantId().equals(graph.tenantId())
                || !projection.viewerContractVersion().equals(EmbedGraphProjection.CURRENT_CONTRACT_VERSION)
                || !projection.graphId().equals(graph.graphId())
                || !projection.graphVersionId().equals(graph.graphVersionId())
                || !projection.canonicalDigest().equals(graph.canonicalDigest())
                || !command.eligibility().policyRevision().equals(graph.projectionPolicyRevision())) {
            return EmbedProvisionOutcome.Reason.IDENTITY_INCOHERENT;
        }
        if (!command.eligibility().allowsProjection()) {
            return EmbedProvisionOutcome.Reason.ELIGIBILITY_DENIED;
        }
        // Checked at provision as well as at read. At read it protects the reader's own ceiling; here
        // it stops a registration that can never be served from being written and then discovered by
        // a browser session that has already been told a launch URL.
        if (!budget.allows(projection)) {
            return EmbedProvisionOutcome.Reason.BUDGET_EXCEEDED;
        }
        return null;
    }
}
