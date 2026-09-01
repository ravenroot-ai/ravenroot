package ai.ravenroot.api.node.service;

import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.execution.NodeMessage;

/** Managed HTTP authority bound to one explicitly granted package. */
@FunctionalInterface
public interface OutboundHttpService {
    /**
     * Submits a request under the security context attached to the delivered message.
     *
     * @param deliveredMessage trusted invocation context, not graph-provided identity data
     * @param request immutable transport intent to admit and execute
     * @return cancellable response call; policy and transport failures are surfaced as sanitized causes
     */
    OutboundCall<OutboundHttpResponse> execute(NodeMessage deliveredMessage, OutboundHttpRequest request);

/**
 * Source-lifecycle variant deriving authority only from the delivered deployment context.
     * @param deliveredContext deployment-owned source authority
     * @param request immutable transport intent to admit and execute
     * @return a failed call by default, preventing a source from obtaining message-style egress by
     *         accident
 */
    default OutboundCall<OutboundHttpResponse> execute(InboundSourceContext deliveredContext,
                                                       OutboundHttpRequest request) {
        return OutboundCall.failed(new NodePackageServiceException(
                NodePackageServiceException.Reason.SERVICE_UNAVAILABLE));
    }
}
