package ai.ravenroot.api.node.service;

import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.execution.NodeMessage;

/** Managed WebSocket authority bound to one explicitly granted package. */
@FunctionalInterface
public interface OutboundWebSocketService {
    /**
     * Opens a policy-admitted session under one delivered node invocation's security context.
     *
     * @param deliveredMessage trusted invocation context, not graph-provided identity data
     * @param request immutable handshake intent
     * @param listener complete-message and terminal-event observer
     * @return cancellable call that yields a constrained session after a successful handshake
     */
    OutboundCall<OutboundWebSocketSession> open(NodeMessage deliveredMessage,
                                                OutboundWebSocketRequest request,
                                                OutboundWebSocketListener listener);

/**
 * Source-lifecycle variant deriving authority only from the delivered deployment context.
     * @param deliveredContext deployment-owned source authority
     * @param request immutable handshake intent
     * @param listener complete-message and terminal-event observer
     * @return a failed call by default, preventing a source from gaining session authority implicitly
 */
    default OutboundCall<OutboundWebSocketSession> open(InboundSourceContext deliveredContext,
                                                        OutboundWebSocketRequest request,
                                                        OutboundWebSocketListener listener) {
        return OutboundCall.failed(new NodePackageServiceException(
                NodePackageServiceException.Reason.SERVICE_UNAVAILABLE));
    }
}
