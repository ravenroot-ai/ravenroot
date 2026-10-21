package ai.ravenroot.api.history;
import java.util.UUID;
/** Same-process causal parent with its actual traversal and logical node; business-join causes are separate. */
public record VisitParent(UUID processInstanceId, UUID traversalId, UUID invocationId, String nodeId) { }
