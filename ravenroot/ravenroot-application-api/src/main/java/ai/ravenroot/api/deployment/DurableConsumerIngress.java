package ai.ravenroot.api.deployment;

/**
 * A source-activation-bound ingress whose checkpoint and inbox namespace survives deployment IDs.
 * Closing or retiring its issuing context revokes admission; pending durable writes keep exclusive
 * storage ownership until they settle. Never transfer this handle between sources or tenants.
 */
public interface DurableConsumerIngress extends TrustedIngress, AutoCloseable {
    /**
     * Reads durable first-invocation custody for a stable event key. A scheduler must retain its
     * occurrence cursor until this returns {@link DurableIngressStartState#STARTED}; an inbox receipt
     * or a RUNNING traversal alone cannot establish that the occurrence can survive a crash.
     */
    default java.util.concurrent.CompletionStage<DurableIngressStartState> startState(
            ai.ravenroot.api.security.SecurityContext security, String sourceId, String idempotentKey) {
        return java.util.concurrent.CompletableFuture.failedFuture(
                new UnsupportedOperationException("durable start-state inspection is unavailable"));
    }

    /** Revokes this handle; pending writes retain ownership until settlement. */
    @Override void close();
}
