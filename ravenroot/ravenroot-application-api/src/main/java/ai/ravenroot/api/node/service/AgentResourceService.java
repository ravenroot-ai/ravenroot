package ai.ravenroot.api.node.service;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.ToolCallContinuationInput;

/** Trusted mediation for finite agent authority and economic resources. */
public interface AgentResourceService {
    /**
     * Admits a new logical agent invocation.
     * @param message trusted runtime invocation message
     * @param request author bounds to tighten
     * @return invocation-bound resource session
     */
    AgentResourceSession admit(NodeMessage message, AgentResourceRequest request);

    /**
     * Resumes the exact invocation bound to a trusted approval continuation.
     * @param continuation validated trusted continuation input
     * @param request original author bounds to tighten
     * @return resumed invocation-bound resource session
     */
    AgentResourceSession resume(ToolCallContinuationInput continuation, AgentResourceRequest request);

    /**
     * Returns the fail-closed service used when no authority mediator was composed.
     * @return deny-only service
     */
    static AgentResourceService unavailable() {
        return new AgentResourceService() {
            private AgentResourceSession refused() {
                throw new NodePackageServiceException(NodePackageServiceException.Reason.SERVICE_UNAVAILABLE);
            }
            @Override public AgentResourceSession admit(NodeMessage message, AgentResourceRequest request) {
                return refused();
            }
            @Override public AgentResourceSession resume(ToolCallContinuationInput continuation,
                                                          AgentResourceRequest request) {
                return refused();
            }
        };
    }
}
