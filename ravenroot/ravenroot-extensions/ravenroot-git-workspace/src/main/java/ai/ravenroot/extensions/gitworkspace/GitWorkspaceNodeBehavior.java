package ai.ravenroot.extensions.gitworkspace;

import ai.ravenroot.api.catalog.NodeOutcomeDescriptor;
import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** The single provider-neutral workspace behavior. */
public final class GitWorkspaceNodeBehavior implements NodeBehavior {
    public static final String BEHAVIOR = "git-workspace";
    private static final CancellationSignal NEVER_CANCELLED = new CancellationSignal() {
        @Override public boolean cancelled() { return false; }
        @Override public void onCancel(Runnable listener) { }
    };

    private final GitWorkspaceProfileResolver profiles;
    private final GitWorkspaceRuntime runtime;

    public GitWorkspaceNodeBehavior() {
        this(new EnvironmentGitWorkspaceProfileResolver());
    }

    GitWorkspaceNodeBehavior(GitWorkspaceProfileResolver profiles) {
        this(profiles, GitWorkspaceRuntime.production());
    }

    GitWorkspaceNodeBehavior(GitWorkspaceProfileResolver profiles, GitWorkspaceRuntime runtime) {
        this.profiles = java.util.Objects.requireNonNull(profiles);
        this.runtime = java.util.Objects.requireNonNull(runtime);
    }

    @Override
    public Set<NodePackageCapability> requiredServices() {
        return Set.of(NodePackageCapability.CREDENTIAL_RESOLUTION);
    }

    @Override
    public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor(BEHAVIOR, "Confined Git workspace", "Source control",
                "Provisions, integrates, and verifies one operator-confined provider-neutral Git workspace.",
                "process", false,
                List.of(NodePropertyDescriptor.required("workspaceProfile", "Workspace profile",
                        NodePropertyType.STRING,
                        "Opaque tenant-scoped operator profile; roots, remotes and credentials never come from the graph.")),
                Set.of("filesystem", "network", "process", "side-effect"))
                .withOutcomes(
                        NodeOutcomeDescriptor.literal("continue", "The operation completed or reconciled safely."),
                        NodeOutcomeDescriptor.literal("conflict", "Existing workspace or branch state requires review."),
                        NodeOutcomeDescriptor.literal("unmerged", "The accepted content is absent from the remote base."));
    }

    @Override
    public NodeAction create(NodeConfiguration configuration) {
        return create(configuration, NodePackageServices.unavailable());
    }

    @Override
    public NodeAction create(NodeConfiguration configuration, NodePackageServices services) {
        String profileName = identifier(configuration.requiredProperty("workspaceProfile"));
        return new NodeAction() {
            @Override
            public CompletionStage<NodeResult> handle(NodeMessage message) {
                return handle(message, NEVER_CANCELLED);
            }

            @Override
            public CompletionStage<NodeResult> handle(NodeMessage message, CancellationSignal cancellation) {
                try {
                    GitWorkspaceProfile profile = profiles.resolve(message.security().tenantId(), profileName)
                            .orElseThrow(() -> GitWorkspaceFailure.of(
                                    GitWorkspaceFailure.Code.PROFILE_UNAVAILABLE));
                    if (!profile.tenant().equals(message.security().tenantId())
                            || !profile.name().equals(profileName)) {
                        throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.PROFILE_UNAVAILABLE);
                    }
                    GitWorkspaceRequest request = GitWorkspaceRequest.parse(message.payload(), profile);
                    return runtime.submit(profile, cancellation,
                            control -> new GitWorkspaceService(profile, services, message, control).execute(request));
                } catch (GitWorkspaceFailure failure) {
                    return CompletableFuture.failedFuture(failure);
                } catch (RuntimeException failure) {
                    return CompletableFuture.failedFuture(
                            GitWorkspaceFailure.of(GitWorkspaceFailure.Code.INVALID_INPUT));
                }
            }
        };
    }

    private static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw GitWorkspaceFailure.of(GitWorkspaceFailure.Code.PROFILE_UNAVAILABLE);
        }
        return value;
    }
}
