package ai.ravenroot.extensions.gitworkspace;

import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;

import java.util.List;

/** Independently installable provider-neutral Git workspace package. */
public final class GitWorkspaceNodePackage implements NodePackage {
    public static final String ID = "ai.ravenroot.extensions.gitworkspace";
    private final List<NodeBehavior> behaviors;

    public GitWorkspaceNodePackage() {
        this(new EnvironmentGitWorkspaceProfileResolver());
    }

    GitWorkspaceNodePackage(GitWorkspaceProfileResolver profiles) {
        behaviors = List.of(new GitWorkspaceNodeBehavior(profiles));
    }

    @Override public String id() { return ID; }
    @Override public String version() { return "1.0.0"; }
    @Override public String sdkContract() { return NodeSdk.CONTRACT; }
    @Override public List<NodeBehavior> behaviors() { return behaviors; }
}
