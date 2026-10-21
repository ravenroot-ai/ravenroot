package ai.ravenroot.api.history;

import ai.ravenroot.api.correlation.*;
import java.util.*;

/** Read-only SDK facade. Every operation rechecks its captured physical source and logical history scope. */
public interface DeploymentHistoryReader {
    /** Immutable captured source; reading it does not imply the source remains current.
 * @return captured logical deployment, physical incarnation and pinned version
 */
    DeploymentHistorySource source();
    /** Published operator-owned page, deadline and response limits; graph content cannot raise them.
 * @return effective operator-owned history admission, retention, read and response bounds
 */
    BusinessHistoryPolicy policy();
    /** Store-bounded search by exact application correlation ID/public reference and lifecycle.
 * @param query bounded exact selection, filters, page size and continuation
 * @return bounded business history summaries under the captured deployment scope
 */
    BusinessHistoryPage histories(BusinessHistoryQuery query);
    /** Actual admitted traversals; original process/traversal identities survive deployment restarts.
 * @param applicationCorrelationId opaque business operation identity within the explicit scope
 * @param cursor opaque continuation from the preceding page of the same selection and filters
 * @param limit requested page size, checked against the operator-owned maximum
 * @return bounded original process and traversal relations within the selected business history
 */
    CorrelatedTraversalPage traversals(String applicationCorrelationId, Optional<String> cursor, int limit);
    /** Legacy traversal selector scoped to this deployment/version, separate from correlated business histories.
 * @param cursor opaque continuation from the preceding page of the same selection and filters
 * @param limit requested page size, checked against the operator-owned maximum
 * @return bounded actual legacy traversals with no invented application correlation context
 */
    HistoryPage<TraversalSummary> uncorrelatedTraversals(Optional<String> cursor, int limit);
    /** Actual traversals for an authorized process, including legacy uncorrelated execution.
 * @param processInstanceId original owning process identity, independent from a business history
 * @param cursor opaque continuation from the preceding page of the same selection and filters
 * @param limit requested page size, checked against the operator-owned maximum
 * @return bounded actual constituent traversals of the selected original process
 */
    HistoryPage<TraversalSummary> processTraversals(UUID processInstanceId, Optional<String> cursor, int limit);
    /** Paged visits of a selected traversal, optionally filtered by an exact node.
 * @param processInstanceId original owning process identity, independent from a business history
 * @param traversalId actual traversal identity within the owning process
 * @param nodeId actual node identity within the pinned graph
 * @param cursor opaque continuation from the preceding page of the same selection and filters
 * @param limit requested page size, checked against the operator-owned maximum
 * @return bounded actual node visits within the selected traversal
 */
    HistoryPage<NodeVisitSummary> visits(UUID processInstanceId, UUID traversalId,
            Optional<String> nodeId, Optional<String> cursor, int limit);
    /** Paged retry attempts of an exact selected visit.
 * @param processInstanceId original owning process identity, independent from a business history
 * @param traversalId actual traversal identity within the owning process
 * @param invocationId actual node-visit identity that owns this observation
 * @param cursor opaque continuation from the preceding page of the same selection and filters
 * @param limit requested page size, checked against the operator-owned maximum
 * @return bounded actual retry attempts of the selected node visit
 */
    HistoryPage<NodeAttemptSummary> attempts(UUID processInstanceId, UUID traversalId, UUID invocationId,
            Optional<String> cursor, int limit);
    /** Actual causal parents, whose traversal identities may differ from the selected visit.
 * @param processInstanceId original owning process identity, independent from a business history
 * @param traversalId actual traversal identity within the owning process
 * @param invocationId actual node-visit identity that owns this observation
 * @param cursor opaque continuation from the preceding page of the same selection and filters
 * @param limit requested page size, checked against the operator-owned maximum
 * @return bounded causal parent visits with their original traversal identities
 */
    HistoryPage<VisitParent> parents(UUID processInstanceId, UUID traversalId, UUID invocationId,
            Optional<String> cursor, int limit);
    /** Scalar join observation; this read never deserializes retained contribution payloads.
 * @param applicationCorrelationId opaque business operation identity within the explicit scope
 * @param cursor opaque continuation from the preceding page of the same selection and filters
 * @param limit requested page size, checked against the operator-owned maximum
 * @return bounded scalar join summaries without contribution payload deserialization
 */
    CorrelatedJoinPage joins(String applicationCorrelationId, Optional<String> cursor, int limit);
    /** Captured content remains governed by the existing activity policy, redaction and retention.
 * @param processInstanceId original owning process identity, independent from a business history
 * @param traversalId actual traversal identity within the owning process
 * @param invocationId actual node-visit identity that owns this observation
 * @param attemptId actual delivery-attempt identity within the visit
 * @param limit requested page size, checked against the operator-owned maximum
 * @return explicit capture availability and bounded retained redacted content
 * @param afterCursor exclusive retained capture cursor, scoped to the exact selected identities
 */
    HistoryActivityPage activity(UUID processInstanceId, UUID traversalId, UUID invocationId, UUID attemptId,
            long afterCursor, int limit);
}
