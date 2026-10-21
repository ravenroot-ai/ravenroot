package ai.ravenroot.api.history;

import ai.ravenroot.api.correlation.*;
import java.util.*;

/** Read-only SDK facade. Every operation rechecks its captured physical source and logical history scope. */
public interface DeploymentHistoryReader {
    /** Immutable captured source; reading it does not imply the source remains current. */
    DeploymentHistorySource source();
    /** Published operator-owned page, deadline and response limits; graph content cannot raise them. */
    BusinessHistoryPolicy policy();
    /** Store-bounded search by exact application correlation ID/public reference and lifecycle. */
    BusinessHistoryPage histories(BusinessHistoryQuery query);
    /** Actual admitted traversals; original process/traversal identities survive deployment restarts. */
    CorrelatedTraversalPage traversals(String applicationCorrelationId, Optional<String> cursor, int limit);
    /** Legacy traversal selector scoped to this deployment/version, separate from correlated business histories. */
    HistoryPage<TraversalSummary> uncorrelatedTraversals(Optional<String> cursor, int limit);
    /** Actual traversals for an authorized process, including legacy uncorrelated execution. */
    HistoryPage<TraversalSummary> processTraversals(UUID processInstanceId, Optional<String> cursor, int limit);
    /** Paged visits of a selected traversal, optionally filtered by an exact node. */
    HistoryPage<NodeVisitSummary> visits(UUID processInstanceId, UUID traversalId,
            Optional<String> nodeId, Optional<String> cursor, int limit);
    /** Paged retry attempts of an exact selected visit. */
    HistoryPage<NodeAttemptSummary> attempts(UUID processInstanceId, UUID traversalId, UUID invocationId,
            Optional<String> cursor, int limit);
    /** Actual causal parents, whose traversal identities may differ from the selected visit. */
    HistoryPage<VisitParent> parents(UUID processInstanceId, UUID traversalId, UUID invocationId,
            Optional<String> cursor, int limit);
    /** Scalar join observation; this read never deserializes retained contribution payloads. */
    CorrelatedJoinPage joins(String applicationCorrelationId, Optional<String> cursor, int limit);
    /** Captured content remains governed by the existing activity policy, redaction and retention. */
    HistoryActivityPage activity(UUID processInstanceId, UUID traversalId, UUID invocationId, UUID attemptId,
            long afterCursor, int limit);
}
