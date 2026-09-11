package ai.ravenroot.core.security.nodepackage;

import ai.ravenroot.api.node.service.NodePackageCapability;

import java.net.http.HttpClient;

/** Test-only bridge for composing the package-private transport seam in cross-package integration tests. */
public final class ManagedNodePackageServicesTestFactory {
    private ManagedNodePackageServicesTestFactory() { }

    public static ManagedNodePackageServices create(String packageId, NodePackageEgressPolicy policy,
            TenantCredentialResolver credentials, HttpClient client, NodePackageCapability... capabilities) {
        var builder = ManagedNodePackageServices.builder(packageId, policy, credentials)
                .clientFactory(() -> client);
        for (NodePackageCapability capability : capabilities) builder.grant(capability);
        return builder.build();
    }
}
