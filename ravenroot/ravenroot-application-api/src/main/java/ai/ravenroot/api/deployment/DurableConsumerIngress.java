package ai.ravenroot.api.deployment;

/**
 * A source-activation-bound ingress whose checkpoint and inbox namespace survives deployment IDs.
 * Closing or retiring its issuing context revokes admission; pending durable writes keep exclusive
 * storage ownership until they settle. Never transfer this handle between sources or tenants.
 */
public interface DurableConsumerIngress extends TrustedIngress, AutoCloseable {
    /** Revokes this handle; pending writes retain ownership until settlement. */
    @Override void close();
}
