package ai.ravenroot.api.node.service;

import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.execution.NodeMessage;

import java.time.Duration;

/** Tenant-bound credential resolution granted independently from network egress. */
@FunctionalInterface
public interface NodeCredentialService {
    /**
     * Resolves a tenant-scoped reference under the security context of a delivered node message.
     *
     * @param deliveredMessage trusted message context; implementations must not obtain identity from
     *                         graph-supplied payload data
     * @param opaqueReference application-owned reference, never raw secret material
     * @param requestedDeadline caller's upper bound for the operation
     * @return a cancellable call that either yields one lease or a sanitized service refusal
     */
    OutboundCall<CredentialLease> resolve(NodeMessage deliveredMessage, String opaqueReference,
                                          Duration requestedDeadline);

/**
 * Source-lifecycle variant deriving authority only from the delivered deployment context.
     * @param deliveredContext deployment-derived authority for a source lifecycle operation
     * @param opaqueReference application-owned reference, never raw secret material
     * @param requestedDeadline caller's upper bound for the operation
     * @return a failed call by default, so a source cannot resolve credentials unless its service
     *         provider explicitly supports this authority path
 */
    default OutboundCall<CredentialLease> resolve(InboundSourceContext deliveredContext,
                                                  String opaqueReference,
                                                  Duration requestedDeadline) {
        return OutboundCall.failed(new NodePackageServiceException(
                NodePackageServiceException.Reason.SERVICE_UNAVAILABLE));
    }
}
