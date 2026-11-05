package ai.ravenroot.api.history;
import ai.ravenroot.api.application.NodeInvocationStatus;
import java.time.Instant;
import java.util.UUID;
/** One actual visit. Position is durable creation order, never a claim that parallel paths were sequential.
 * @param processInstanceId original owning process identity, independent from a business history
 * @param traversalId actual traversal identity within the owning process
 * @param invocationId actual node-visit identity that owns this observation
 * @param nodeId actual node identity within the pinned graph
 * @param position durable visit creation order; it does not linearize parallel branches
 * @param status authoritative recorded lifecycle status at the represented level
 * @param command explicit node command delivered for this visit
 * @param attemptCount number of actual delivery attempts recorded for this visit
 * @param parentCount number of actual causal parent invocations; parent traversals may differ
 * @param startedAt recorded start time, absent when retained event evidence is unavailable
 * @param settledAt recorded final-attempt settlement time, absent when retained event evidence is unavailable
 * @param outcome safe recorded outcome classifier, absent when not retained or not representable
 * @param eventHistoryAvailable whether retained journal evidence supplied timing or outcome facts for this row
 */
public record NodeVisitSummary(UUID processInstanceId, UUID traversalId, UUID invocationId, String nodeId,
        long position, NodeInvocationStatus status, String command, long attemptCount, long parentCount,
        Instant startedAt, Instant settledAt, String outcome, boolean eventHistoryAvailable) { }
