package ai.ravenroot.api.history;
import ai.ravenroot.api.application.*;
import java.time.Instant;
import java.util.UUID;
/** Ordered delivery attempt; raw diagnostic/park strings never enter the browser-safe metadata channel.
 * @param attemptId actual delivery-attempt identity within the visit
 * @param ordinal one-based retry-attempt order within a single node invocation
 * @param status authoritative recorded lifecycle status at the represented level
 * @param completion recorded completion classification, absent when no completion was recorded
 * @param startedAt recorded start time, absent when retained event evidence is unavailable
 * @param settledAt recorded final-attempt settlement time, absent when retained event evidence is unavailable
 * @param eventHistoryAvailable whether retained journal evidence supplied timing or outcome facts for this row
 */
public record NodeAttemptSummary(UUID attemptId, int ordinal, NodeAttemptStatus status,
        NodeAttemptCompletion completion, Instant startedAt, Instant settledAt, boolean eventHistoryAvailable) { }
