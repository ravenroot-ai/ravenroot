package ai.ravenroot.extensions.systemone;

import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;

import java.util.List;

/** Independently installable typed probabilistic evaluation bundle. */
public final class SystemOneNodePackage implements NodePackage {
    public static final String ID = "ai.ravenroot.extensions.systemone";
    private final SystemOneProfileResolver profiles;

    public SystemOneNodePackage() { this(new EnvironmentSystemOneProfileResolver()); }
    SystemOneNodePackage(SystemOneProfileResolver profiles) { this.profiles = profiles; }

    @Override public String id() { return ID; }
    @Override public String version() { return "1.0.0"; }
    @Override public String sdkContract() { return NodeSdk.CONTRACT; }
    @Override public List<NodeBehavior> behaviors() {
        return List.of(new SystemOneEvaluateNodeBehavior(profiles));
    }
}
