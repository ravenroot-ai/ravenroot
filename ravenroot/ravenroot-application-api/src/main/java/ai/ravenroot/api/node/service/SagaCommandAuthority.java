package ai.ravenroot.api.node.service;

import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.SagaCommandIntent;

/**
 * Current operator authority for one durable saga participant command.
 *
 * <p>The command payload contains the identity and participant configuration frozen when the graph
 * ran. Those values are evidence used to reproduce the same call; they are never authority. A
 * recovery worker must resolve this boundary from its current manifest, connector grants,
 * destination policy and credential-reference policy before every dispatch and outcome lookup.</p>
 */
@FunctionalInterface
public interface SagaCommandAuthority {
    /**
     * Refuses a command that the current worker is not authorized to deliver or reconcile.
     *
     * @param key persisted execution identity; its tenant is authoritative
     * @param intent immutable participant command whose frozen evidence is being checked
     * @throws SecurityException when the current worker lacks any required authority
     */
    void authorize(ExecutionKey key, SagaCommandIntent intent);
}
