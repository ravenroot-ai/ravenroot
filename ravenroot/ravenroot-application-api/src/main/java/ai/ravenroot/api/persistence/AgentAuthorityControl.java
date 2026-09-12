package ai.ravenroot.api.persistence;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable store-global agent-authority control epoch.
 *
 * @param state whether new agent effects may be admitted
 * @param epoch monotonically increasing control generation
 * @param changedAt store-clock time of the latest transition
 * @param teamActiveReleased cumulative active-team slots released by global kill transitions
 */
public record AgentAuthorityControl(AgentAuthorityControlState state, long epoch, Instant changedAt,
                                    long teamActiveReleased) {
    /** Validates a durable control snapshot. */
    public AgentAuthorityControl {
        Objects.requireNonNull(state, "state");
        if (epoch < 0) throw new IllegalArgumentException("agent authority control epoch cannot be negative");
        Objects.requireNonNull(changedAt, "changedAt");
        if (teamActiveReleased < 0) {
            throw new IllegalArgumentException("released active-team slots cannot be negative");
        }
    }
}
