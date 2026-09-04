package ai.ravenroot.api.node.service;

import java.time.Duration;
import java.util.Optional;

/** One finite pre-egress model reservation. Terminal methods are idempotent. */
public interface AgentModelReservation {
    /** Maximum completion tokens authorized for the outbound request. */
    long maximumOutputTokens();

    /** Maximum transport duration authorized for the outbound request. */
    Duration maximumDuration();

    /** Makes this held reservation non-refundable immediately before provider egress. */
    void dispatch();

    /** Releases a held reservation when local preparation fails before provider egress. */
    void release();

    /** Settles known provider usage; missing or invalid usage is charged conservatively in full. */
    void settle(Optional<Long> inputTokens, Optional<Long> outputTokens);

    /** Records an ambiguous or unavailable provider outcome conservatively. */
    void indeterminate();
}
