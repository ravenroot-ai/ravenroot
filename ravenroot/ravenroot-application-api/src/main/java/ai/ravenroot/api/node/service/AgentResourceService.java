package ai.ravenroot.api.node.service;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.ToolCallContinuationInput;

/** Trusted mediation for finite agent authority and economic resources. */
public interface AgentResourceService {
    AgentResourceSession admit(NodeMessage message, AgentResourceRequest request);

    AgentResourceSession resume(ToolCallContinuationInput continuation, AgentResourceRequest request);

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
