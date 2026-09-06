package ai.ravenroot.extensions.teams;

import ai.ravenroot.api.ingress.IngressAuthorityContributor;
import ai.ravenroot.api.ingress.IngressAuthorityDeclaration;
import ai.ravenroot.api.ingress.IngressRequestProjectionPolicy;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/** Optional Microsoft Teams messaging package. */
public final class TeamsNodePackage implements NodePackage, IngressAuthorityContributor {
    private final TeamsRuntime runtime;
    private final List<NodeBehavior> behaviors;

    /** Creates a package backed by operator-provided Teams configuration. */
    public TeamsNodePackage() {
        runtime = new TeamsRuntime(TeamsConfiguration::fromEnvironment);
        behaviors = behaviors(runtime);
    }

    TeamsNodePackage(TeamsConfiguration configuration, TeamsDeliveryStore store, Clock clock) {
        runtime = new TeamsRuntime(configuration, store, clock);
        behaviors = behaviors(runtime);
    }

    @Override public String id() { return TeamsConfiguration.PACKAGE_ID; }
    @Override public String version() { return "1.0.0"; }
    @Override public String sdkContract() { return NodeSdk.CONTRACT; }
    @Override public List<NodeBehavior> behaviors() { return behaviors; }
    @Override public List<IngressAuthorityDeclaration> ingressAuthorities() {
        return List.of(runtime.configuration().authority());
    }
    @Override public Optional<IngressRequestProjectionPolicy> ingressRequestProjection() {
        return Optional.of(runtime.configuration().projection());
    }

    private static List<NodeBehavior> behaviors(TeamsRuntime runtime) {
        return List.of(new TeamsOutgoingWebhookSourceBehavior(runtime), new TeamsSendBehavior(runtime));
    }
}
