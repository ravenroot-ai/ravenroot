package ai.ravenroot.extensions.mattermost;

import ai.ravenroot.api.ingress.IngressAuthorityContributor;
import ai.ravenroot.api.ingress.IngressAuthorityDeclaration;
import ai.ravenroot.api.ingress.IngressRequestProjectionPolicy;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/** Optional Mattermost message and outgoing-webhook package. */
public final class MattermostNodePackage implements NodePackage, IngressAuthorityContributor {
    private final MattermostRuntime runtime;
    private final List<NodeBehavior> behaviors;
    /** Creates a package backed by operator-provided Mattermost configuration. */
    public MattermostNodePackage() {
        runtime = new MattermostRuntime(MattermostConfiguration::fromEnvironment); behaviors = behaviors(runtime);
    }
    MattermostNodePackage(MattermostConfiguration configuration, MattermostDeliveryStore store, Clock clock) {
        runtime = new MattermostRuntime(configuration, store, clock); behaviors = behaviors(runtime);
    }
    @Override public String id() { return MattermostConfiguration.PACKAGE_ID; }
    @Override public String version() { return "1.0.0"; }
    @Override public String sdkContract() { return NodeSdk.CONTRACT; }
    @Override public List<NodeBehavior> behaviors() { return behaviors; }
    @Override public List<IngressAuthorityDeclaration> ingressAuthorities() {
        return List.of(runtime.configuration().authority());
    }
    @Override public Optional<IngressRequestProjectionPolicy> ingressRequestProjection() {
        return Optional.of(runtime.configuration().projection());
    }
    private static List<NodeBehavior> behaviors(MattermostRuntime runtime) {
        return List.of(new MattermostSendBehavior(runtime), new MattermostOutgoingWebhookSourceBehavior(runtime));
    }
}
