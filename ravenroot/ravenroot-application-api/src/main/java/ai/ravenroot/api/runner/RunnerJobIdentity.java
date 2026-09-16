package ai.ravenroot.api.runner;

import ai.ravenroot.api.persistence.ExecutionKey;

import java.util.Objects;
import java.util.UUID;

/**
 * The durable identity hierarchy of a runner job. A loop creates a new invocation; a technical retry
 * creates a new attempt; runner reconnects and reconciliation preserve all five identities.
 * @param execution tenant and process instance, also the primary workspace scope
 * @param traversalId traversal within the process
 * @param invocationId logical visit to a node
 * @param attemptId technical attempt of that visit
 * @param runnerJobId job accepted for that attempt
 */
public record RunnerJobIdentity(ExecutionKey execution, UUID traversalId, UUID invocationId,
                                UUID attemptId, UUID runnerJobId) {
    /** Rejects incomplete identities. */
    public RunnerJobIdentity {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(traversalId, "traversalId");
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(runnerJobId, "runnerJobId");
    }
}
