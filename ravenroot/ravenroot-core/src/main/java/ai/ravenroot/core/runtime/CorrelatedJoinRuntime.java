package ai.ravenroot.core.runtime;

import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.payload.*;
import ai.ravenroot.api.persistence.*;
import ai.ravenroot.core.graph.GraphNode;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletionStage;

/** One deterministic contribution decision, used under the original execution's live store fence. */
final class CorrelatedJoinRuntime {
    interface StoreAwait { <T> T get(CompletionStage<T> stage); }
    private CorrelatedJoinRuntime() { }
    static CorrelatedJoinRecord accept(ExecutionStore store, LeaseHandle fence, GraphNode node,
            String entryNodeId, NodeMessage message, Instant now,
            StoreAwait await) {
        if (!store.supports(StoreCapability.CORRELATED_JOINS) || message.applicationCorrelation() == null)
            throw new IllegalStateException("correlated join requires persisted application context and store support");
        var policy = store.businessHistoryPolicy();
        var configuration = CorrelatedJoinConfiguration.read(node, policy);
        var input = PayloadValue.fromJava(message.payload(), policy.selection().input());
        var announcement = configuration.announcement(entryNodeId, input);
        var context = message.applicationCorrelation();
        var relation = await.get(store.correlatedTraversal(message.tenantId(), message.processInstanceId(),
                message.traversalId())).orElseThrow();
        var key = new CorrelatedJoinKey(relation.scope(), context.applicationCorrelationId(), node.id(), context.roundId());
        var cause = new JoinCause(message.processInstanceId(), message.traversalId(), message.invocationId(), message.attemptId(), message.security());
        JoinContribution contribution = null;
        if (context.contributionId().isPresent()) {
            byte[] data = PayloadJson.write(input).getBytes(StandardCharsets.UTF_8);
            var payload = OpaquePayload.of(data, "application/json");
            contribution = new JoinContribution(context.contributionId().orElseThrow(), context.contributionKind().orElse(null),
                    message.processInstanceId(), message.traversalId(), message.invocationId(), message.attemptId(),
                    message.security(), payload, JoinContribution.digest(data), now);
        } else if (!announcement.controlAllowed()) {
            throw new IllegalArgumentException("correlated join requires a contribution ID or an authorized producer signal");
        }
        for (int retry = 0; retry < policy.maxJoinCasAttempts(); retry++) {
            var prior = await.get(store.correlatedJoin(key));
            CorrelatedJoinRecord next;
            if (prior.isEmpty()) {
                next = CorrelatedJoinRecord.open(key, announcement.expected(), announcement.count(), false,
                        cause, now, now.plus(policy.joinDeadline()));
                next = next.contribute(contribution, announcement.expected(), announcement.count(), announcement.seal(), now, policy);
                next = new CorrelatedJoinRecord(next.key(), 1, next.phase(), next.expectedIds(), next.expectedCount(), next.sealed(),
                        next.contributions(), next.openedBy(), next.openedAt(), next.deadlineAt(), next.updatedAt());
            } else {
                next = prior.orElseThrow().contribute(contribution, announcement.expected(), announcement.count(), announcement.seal(), now, policy);
                if (next == prior.orElseThrow()) return next;
            }
            try { return await.get(store.compareAndSetCorrelatedJoin(next, fence)); }
            catch (BusinessHistoryException conflict) {
                if (conflict.reason() != BusinessHistoryException.Reason.REVISION_CONFLICT) throw conflict;
            }
        }
        throw new BusinessHistoryException(BusinessHistoryException.Reason.REVISION_CONFLICT);
    }
}
