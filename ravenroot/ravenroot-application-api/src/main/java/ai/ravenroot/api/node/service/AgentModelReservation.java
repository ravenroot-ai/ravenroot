package ai.ravenroot.api.node.service;

import java.time.Duration;
import java.util.Optional;

/** One finite pre-egress model reservation. Terminal methods are idempotent. */
public interface AgentModelReservation {
    /**
     * Returns the maximum completion tokens authorized for the outbound request.
     * @return maximum completion-token count
     */
    long maximumOutputTokens();

    /**
     * Returns the maximum transport duration authorized for the outbound request.
     * @return maximum transport duration
     */
    Duration maximumDuration();

    /** Makes this held reservation non-refundable immediately before provider egress. */
    void dispatch();

    /** Releases a held reservation when local preparation fails before provider egress. */
    void release();

    /**
     * Settles known provider usage; missing or invalid usage is charged conservatively in full.
     * @param inputTokens provider-reported input usage, when valid and present
     * @param outputTokens provider-reported output usage, when valid and present
     */
    void settle(Optional<Long> inputTokens, Optional<Long> outputTokens);

    /** Records an ambiguous or unavailable provider outcome conservatively. */
    void indeterminate();
}
