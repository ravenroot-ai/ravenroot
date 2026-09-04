package ai.ravenroot.extensions.github;

import ai.ravenroot.api.ingress.IngressAuthorityContributor;
import ai.ravenroot.api.ingress.IngressAuthorityDeclaration;
import ai.ravenroot.api.ingress.IngressRequestProjectionPolicy;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;

import java.util.List;
import java.util.Optional;

/** Optional GitHub automation package with no repository or publication authority of its own. */
public final class GithubNodePackage implements NodePackage, IngressAuthorityContributor {
    private final GithubRuntime runtime;
    private final List<NodeBehavior> behaviors;

    public GithubNodePackage() {
        this.runtime = new GithubRuntime(GithubConfiguration::fromEnvironment);
        this.behaviors = behaviors(runtime);
    }

    GithubNodePackage(GithubConfiguration configuration) {
        this(configuration, new SqliteGithubOperationStore(configuration.store()));
    }

    GithubNodePackage(GithubConfiguration configuration, GithubOperationStore store) {
        this.runtime = new GithubRuntime(java.util.Objects.requireNonNull(configuration), java.util.Objects.requireNonNull(store));
        this.behaviors = behaviors(runtime);
    }

    @Override public String id() { return GithubConfiguration.PACKAGE_ID; }
    @Override public String version() { return "1.0.0"; }
    @Override public String sdkContract() { return NodeSdk.CONTRACT; }
    @Override public List<NodeBehavior> behaviors() { return behaviors; }
    @Override public List<IngressAuthorityDeclaration> ingressAuthorities() { return List.of(runtime.configuration().authority()); }
    @Override public Optional<IngressRequestProjectionPolicy> ingressRequestProjection() {
        return Optional.of(runtime.configuration().projection());
    }

    private static List<NodeBehavior> behaviors(GithubRuntime runtime) {
        return List.of(new GithubEventsSourceBehavior(runtime), new ProjectTransitionBehavior(runtime),
                new GithubAppReviewBehavior(runtime), new GithubWorkflowWatchBehavior(runtime),
                new ReleasePrepareBehavior(runtime));
    }
}
