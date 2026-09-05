package ai.ravenroot.extensions.openapi.server;

import ai.ravenroot.api.ingress.IngressAuthorityContributor;
import ai.ravenroot.api.ingress.IngressAuthorityDeclaration;
import ai.ravenroot.api.ingress.IngressRequestProjectionPolicy;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Optional durable asynchronous OpenAPI 3 ingress package. */
public final class OpenApiServerNodePackage implements NodePackage, IngressAuthorityContributor {
    private final OpenApiServerConfigurationResolver resolver;
    private final OpenApiAdmissionRegistry admission = new OpenApiAdmissionRegistry();
    private final OpenApiResponseRegistry responses = new OpenApiResponseRegistry();
    private final OpenApiReceiveNodeBehavior behavior = new OpenApiReceiveNodeBehavior(
            this::configuration, admission);
    private final OpenApiRequestReplyNodeBehavior requestReplyBehavior = new OpenApiRequestReplyNodeBehavior(
            this::configuration, admission, responses);
    private volatile OpenApiServerConfiguration resolved;

    public OpenApiServerNodePackage() { this(new EnvironmentOpenApiServerConfigurationResolver()); }

    OpenApiServerNodePackage(OpenApiServerConfigurationResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override public String id() { return OpenApiServerConfiguration.PACKAGE_ID; }
    @Override public String version() { return "1.0.0"; }
    @Override public String sdkContract() { return NodeSdk.CONTRACT; }
    @Override public List<NodeBehavior> behaviors() { return List.of(behavior, requestReplyBehavior); }
    @Override public List<IngressAuthorityDeclaration> ingressAuthorities() { return List.of(configuration().authority()); }
    @Override public Optional<IngressRequestProjectionPolicy> ingressRequestProjection() {
        return Optional.of(configuration().projection());
    }

    private OpenApiServerConfiguration configuration() {
        OpenApiServerConfiguration current = resolved;
        if (current != null) return current;
        synchronized (this) {
            current = resolved;
            if (current == null) {
                current = resolver.resolve().orElseThrow(() ->
                        new OpenApiServerException(OpenApiServerException.Code.CONFIGURATION_INVALID));
                resolved = current;
            }
            return current;
        }
    }
}
