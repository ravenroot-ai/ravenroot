package ai.ravenroot.core.security.nodepackage;

import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;

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

    private WebSocketManagedServicesHarness() { }
}
