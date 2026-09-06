package ai.ravenroot.core.deployment;

import ai.ravenroot.api.deployment.DeploymentId;

/**
 * What one reconciliation sweep decided about one deployment.
 *
 * <p>Sealed so a later kind cannot be added without every consumer being told, and returned rather
 * than only logged so the decision is assertable by a test and reportable on an operational surface
 * without a second read of the authority — the same two reasons {@code RecoveryOutcome} gives one
 * layer down, and this type is deliberately its sibling rather than a reuse of it: that one is about
 * an attempt inside a process instance, this one about a deployment's lifecycle, and the only thing
 * they share is the shape of the loop.</p>
 *
 * <p>A sweep reports only the deployments it <em>considered</em>. One whose evidence has already
 * caught up with its intent produces no outcome at all, because there was nothing to reconcile and a
 * row saying so would grow with the size of the installation rather than with the size of the
 * problem.</p>
 */
public sealed interface DeploymentReconcileOutcome {

    /**
     * Returns the tenant owning the deployment this outcome is about.
     * @return tenant id.
     */
    String tenantId();

    /**
     * Returns the deployment this outcome is about.
     * @return deployment id.
     */
    DeploymentId deploymentId();

    /**
     * Returns the lifecycle generation the durable intent was recorded at.
     * @return generation this sweep was trying to make true.
     */
    long generation();

    /**
     * The intent was carried out and the evidence now names the generation it was recorded at.
     *
     * <p>This is the outcome that closes the crash window: the runtime has been driven to the durable
     * intent and the observation has been published under this owner's fence, so a later sweep — by
     * this process or by whichever one takes over next — finds the evidence current and does nothing.
     * That is what stops a resumed command from being applied twice.</p>
     *
     * @param tenantId tenant owning the deployment.
     * @param deploymentId deployment that was driven to its intent.
     * @param generation generation the runtime and the evidence now agree on.
     * @param fence ownership epoch the effect and the evidence were performed under.
     */
    record Reconciled(String tenantId, DeploymentId deploymentId, long generation, long fence)
            implements DeploymentReconcileOutcome {
    }

    /**
     * Another live owner holds the lease, so this process performed nothing.
     *
     * <p>Not a failure and not a retry: exactly one owner may act, and being told which one is the
     * point. Nothing was written, the durable intent still stands, and the holder's own sweep is what
     * finishes it.</p>
     *
     * @param tenantId tenant owning the deployment.
     * @param deploymentId deployment this process could not claim.
     * @param generation generation of the intent that is still outstanding.
     * @param holder identity of the owner that holds the lease.
     */
    record NotOwned(String tenantId, DeploymentId deploymentId, long generation, String holder)
            implements DeploymentReconcileOutcome {
    }

    /**
     * The intent is outstanding and this process has no runtime to apply it to.
     *
     * <p>The ordinary answer on a host that does not run this deployment. Deliberately not an error:
     * the alternative — driving a runtime this process does not have, or acknowledging intent nobody
     * carried out — is the silent loss reconciliation exists to prevent.</p>
     *
     * @param tenantId tenant owning the deployment.
     * @param deploymentId deployment whose runtime is not hosted here.
     * @param generation generation of the intent that is still outstanding.
     * @param reason short, sanitized statement of why nothing was done.
     */
    record Deferred(String tenantId, DeploymentId deploymentId, long generation, String reason)
            implements DeploymentReconcileOutcome {
    }

    /**
     * Driving the runtime to the durable intent failed, classified rather than described.
     *
     * <p>The intent is untouched and still outstanding, so the next sweep tries again. The classifier
     * is drawn from the failure's class name and never from its message, for the reason
     * {@code DeploymentCommandOutcome.Failed} states: a message may carry payload fragments or
     * author-controlled text, and this value reaches operator surfaces and logs.</p>
     *
     * @param tenantId tenant owning the deployment.
     * @param deploymentId deployment whose intent could not be applied.
     * @param generation generation of the intent that is still outstanding.
     * @param classifiedCause sanitized classification of what failed.
     */
    record Failed(String tenantId, DeploymentId deploymentId, long generation, String classifiedCause)
            implements DeploymentReconcileOutcome {
    }
}
