package ai.ravenroot.api.history;
import ai.ravenroot.api.application.*;
import java.time.Instant;
import java.util.UUID;
/** Ordered delivery attempt; raw diagnostic/park strings never enter the browser-safe metadata channel. */
public record NodeAttemptSummary(UUID attemptId, int ordinal, NodeAttemptStatus status,
        NodeAttemptCompletion completion, Instant startedAt, Instant settledAt, boolean eventHistoryAvailable) { }
