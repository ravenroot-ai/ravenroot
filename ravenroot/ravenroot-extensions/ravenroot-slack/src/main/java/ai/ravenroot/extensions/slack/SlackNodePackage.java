package ai.ravenroot.extensions.slack;

import ai.ravenroot.api.ingress.IngressAuthorityContributor;
import ai.ravenroot.api.ingress.IngressAuthorityDeclaration;
import ai.ravenroot.api.ingress.IngressRequestProjectionPolicy;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/** Optional Slack messaging, events, and slash-command package. */
public final class SlackNodePackage implements NodePackage, IngressAuthorityContributor {
    private final SlackRuntime runtime;
    private final List<NodeBehavior> behaviors;
    /** Creates a package backed by operator-provided Slack configuration. */
    public SlackNodePackage() {
        runtime = new SlackRuntime(SlackConfiguration::fromEnvironment); behaviors = behaviors(runtime);
    }
    SlackNodePackage(SlackConfiguration configuration, SlackDeliveryStore store, Clock clock) {
        runtime = new SlackRuntime(configuration, store, clock); behaviors = behaviors(runtime);
    }
    @Override public String id() { return SlackConfiguration.PACKAGE_ID; }
    @Override public String version() { return "1.0.0"; }
    @Override public String sdkContract() { return NodeSdk.CONTRACT; }
    @Override public List<NodeBehavior> behaviors() { return behaviors; }
    @Override public List<IngressAuthorityDeclaration> ingressAuthorities() {
        return List.of(runtime.configuration().authority());
    }
    @Override public Optional<IngressRequestProjectionPolicy> ingressRequestProjection() {
        return Optional.of(runtime.configuration().projection());
    }
    private static List<NodeBehavior> behaviors(SlackRuntime runtime) {
        return List.of(new SlackEventsSourceBehavior(runtime), new SlackCommandsSourceBehavior(runtime),
                new SlackPostMessageBehavior(runtime));
    }
}
