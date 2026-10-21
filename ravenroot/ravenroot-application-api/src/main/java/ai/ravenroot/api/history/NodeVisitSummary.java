package ai.ravenroot.api.history;
import ai.ravenroot.api.application.NodeInvocationStatus;
import java.time.Instant;
import java.util.UUID;
/** One actual visit. Position is durable creation order, never a claim that parallel paths were sequential. */
public record NodeVisitSummary(UUID processInstanceId, UUID traversalId, UUID invocationId, String nodeId,
        long position, NodeInvocationStatus status, String command, long attemptCount, long parentCount,
        Instant startedAt, Instant settledAt, String outcome, boolean eventHistoryAvailable) { }
