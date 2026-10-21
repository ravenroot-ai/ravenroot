package ai.ravenroot.api.history;
import java.util.UUID;
/** Same-process causal parent with its actual traversal and logical node; business-join causes are separate.
 * @param processInstanceId original owning process identity, independent from a business history
 * @param traversalId actual traversal identity within the owning process
 * @param invocationId actual node-visit identity that owns this observation
 * @param nodeId actual node identity within the pinned graph
 */
public record VisitParent(UUID processInstanceId, UUID traversalId, UUID invocationId, String nodeId) { }
