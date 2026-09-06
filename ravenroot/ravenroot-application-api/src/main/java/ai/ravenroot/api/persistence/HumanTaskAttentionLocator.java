package ai.ravenroot.api.persistence;

import java.util.UUID;

/**
 * Bare durable locator used to recover one Human Task after browser or process-context loss.
 *
 * <p>The authenticated tenant and current authorization are supplied separately. A lookup derives
 * graph, deployment and process pins from durable state and remains nondisclosing when the task is
 * absent, terminal, stale or unauthorized.</p>
 *
 * @param taskId exact durable task identity.
 * @param generation exact optimistic decision fence.
 */
public record HumanTaskAttentionLocator(UUID taskId, long generation) {
    /** Validates the complete recovery locator. */
    public HumanTaskAttentionLocator {
        if (taskId == null) throw new IllegalArgumentException("taskId cannot be null");
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
    }
}
