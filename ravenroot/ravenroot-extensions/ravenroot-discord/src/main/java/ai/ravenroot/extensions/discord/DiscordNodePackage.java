package ai.ravenroot.extensions.discord;

import ai.ravenroot.api.ingress.IngressAuthorityContributor;
import ai.ravenroot.api.ingress.IngressAuthorityDeclaration;
import ai.ravenroot.api.ingress.IngressRequestProjectionPolicy;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/** Optional Discord messaging and signed-interaction package. */
public final class DiscordNodePackage implements NodePackage, IngressAuthorityContributor {
    private final DiscordRuntime runtime;
    private final List<NodeBehavior> behaviors;

    /** Creates a package backed by the operator-provided Discord configuration. */
    public DiscordNodePackage() {
        runtime = new DiscordRuntime(DiscordConfiguration::fromEnvironment);
        behaviors = behaviors(runtime);
    }
    DiscordNodePackage(DiscordConfiguration configuration, DiscordDeliveryStore store, Clock clock) {
        runtime = new DiscordRuntime(configuration, store, clock); behaviors = behaviors(runtime);
    }

    @Override public String id() { return DiscordConfiguration.PACKAGE_ID; }
    @Override public String version() { return "1.0.0"; }
    @Override public String sdkContract() { return NodeSdk.CONTRACT; }
    @Override public List<NodeBehavior> behaviors() { return behaviors; }
    @Override public List<IngressAuthorityDeclaration> ingressAuthorities() {
        return List.of(runtime.configuration().authority());
    }
    @Override public Optional<IngressRequestProjectionPolicy> ingressRequestProjection() {
        return Optional.of(runtime.configuration().projection());
    }

    private static List<NodeBehavior> behaviors(DiscordRuntime runtime) {
        return List.of(new DiscordInteractionsSourceBehavior(runtime), new DiscordSendNodeBehavior(runtime));
    }
}
