package ai.ravenroot.core.security.nodepackage;

import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.NodePackageServiceRegistry;
import ai.ravenroot.core.runtime.NodePackages;

import java.net.http.HttpClient;
import java.util.function.Supplier;

/** Test-only access to the package-private trusted client factory composition seam. */
public final class WebSocketManagedServicesHarness {
    public static NodePackageServices services(NodePackageEgressPolicy policy,
                                               TenantCredentialResolver credentials,
                                               Supplier<HttpClient> clients) {
        return ManagedNodePackageServices.builder("ai.ravenroot.extensions.websocket", policy, credentials)
                .clientFactory(clients)
                .grant(NodePackageCapability.OUTBOUND_WEBSOCKET)
                .build();
    }

    /**
     * Test-only composition of the production package registration and source-authority binder.
     * The trusted deployment facts are supplied independently of the context's public getters, as
     * they are by {@code DefaultGraphDeployment}; only the exact registered context gains authority.
     */
    public static SourceServices sourceServices(NodePackage nodePackage, NodePackageEgressPolicy policy,
                                                TenantCredentialResolver credentials,
                                                Supplier<HttpClient> clients,
                                                InboundSourceContext context,
                                                DeploymentId deploymentId, String nodeId,
                                                SecurityContext identity) {
        NodePackageServices services = services(policy, credentials, clients);
        BehaviorRegistry registry = NodePackages.register(new BehaviorRegistry(), nodePackage,
                NodePackageServiceRegistry.builder().grant(nodePackage.id(), services).build());
        BehaviorRegistry.SourceRegistration registration = registry.registerSourceAuthority(
                context, nodePackage.id(), deploymentId, nodeId, 1L, identity);
        registration.activate();
        return new SourceServices(services, registration);
    }

    /** Active source service view; close revokes operations and handed-off sessions idempotently. */
    public record SourceServices(NodePackageServices services,
                                 BehaviorRegistry.SourceRegistration registration)
            implements AutoCloseable {
        @Override public void close() { registration.close(); }
    }

    private WebSocketManagedServicesHarness() { }
}
