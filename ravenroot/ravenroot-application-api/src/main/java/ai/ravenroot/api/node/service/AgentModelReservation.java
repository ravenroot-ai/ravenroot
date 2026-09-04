package ai.ravenroot.api.node.service;

import java.util.Optional;

/** One finite pre-egress model reservation. Terminal methods are idempotent. */
public interface AgentModelReservation {
    /** Settles known provider usage; missing or invalid usage is charged conservatively in full. */
    void settle(Optional<Long> inputTokens, Optional<Long> outputTokens);

    /** Records an ambiguous or unavailable provider outcome conservatively. */
    void indeterminate();
}
