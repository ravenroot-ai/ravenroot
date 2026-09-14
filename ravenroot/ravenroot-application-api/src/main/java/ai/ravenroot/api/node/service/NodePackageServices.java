package ai.ravenroot.api.node.service;

import java.util.Set;
import java.util.Optional;

/** Immutable, package-scoped service view supplied by the trusted runtime composition root. */
public interface NodePackageServices {
    /**
     * Describes the immutable quantitative external-I/O policy enforced by this service view.
     * Empty means a custom provider has not supplied the v2 contract; it does not imply denial.
     *
     * @return quantitative managed external-I/O capacity, when supplied by the provider
     */
    default Optional<NodePackageEgressCapacityProfile> egressCapacityProfile() {
        return Optional.empty();
    }
    /**
     * Lists exactly the operator-composed capabilities exposed by this view.
     *
     * @return immutable grants; an empty set is deny-by-default
     */
    Set<NodePackageCapability> capabilities();
    /**
     * Returns the capability-gated credential resolver available to this package.
     *
     * @return credential service whose calls refuse when credential resolution was not granted
     */
    NodeCredentialService credentials();
    /**
     * Returns the managed HTTP executor available to this package.
     *
     * @return managed HTTP service whose calls refuse when outbound HTTP was not granted
     */
    OutboundHttpService outboundHttp();
    /**
     * Returns the managed WebSocket connector available to this package.
     *
     * @return managed WebSocket service whose calls refuse when outbound WebSocket was not granted
     */
    OutboundWebSocketService outboundWebSocket();

    /**
     * Returns the server-owned authorization and audit boundary for model-requested tool calls.
     *
     * <p>This is a default method so SDK /2 packages compiled before the service existed remain
     * binary compatible. The default refuses every call: adding the method cannot turn an older
     * service view into authority it was never granted.</p>
     *
     * @return a fail-closed service unless the operator explicitly granted tool authorization
     */
    default ToolCallAuthorizationService toolAuthorization() {
        return ToolCallAuthorizationService.unavailable();
    }

    /**
     * Returns finite agent authority/economic mediation. Kept additive and deny-only for older SDKs.
     * @return agent authority service, deny-only unless explicitly composed
     */
    default AgentResourceService agentResources() {
        return AgentResourceService.unavailable();
    }

    /**
     * Deny-only view used for legacy packages and deployments which composed no grants.
     * @return a reusable deny-only view that advertises no capabilities and fails every operation
     */
    static NodePackageServices unavailable() {
        NodePackageServiceException unavailable = new NodePackageServiceException(
                NodePackageServiceException.Reason.SERVICE_UNAVAILABLE);
        return new NodePackageServices() {
            @Override public Optional<NodePackageEgressCapacityProfile> egressCapacityProfile() {
                return Optional.of(NodePackageEgressCapacityProfile.noManagedEgress());
            }
            @Override public Set<NodePackageCapability> capabilities() { return Set.of(); }
            @Override public NodeCredentialService credentials() {
                return (message, reference, deadline) -> OutboundCall.failed(unavailable);
            }
            @Override public OutboundHttpService outboundHttp() {
                return (message, request) -> OutboundCall.failed(unavailable);
            }
            @Override public OutboundWebSocketService outboundWebSocket() {
                return (message, request, listener) -> OutboundCall.failed(unavailable);
            }
        };
    }
}
