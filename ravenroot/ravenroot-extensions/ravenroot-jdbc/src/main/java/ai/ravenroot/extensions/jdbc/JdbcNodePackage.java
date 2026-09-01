package ai.ravenroot.extensions.jdbc;

import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;

import java.util.List;

public final class JdbcNodePackage implements NodePackage {
    private final List<NodeBehavior> behaviors;
    public JdbcNodePackage() {
        var executor = new JdbcExecutor(new EnvironmentJdbcProfileResolver(), JdbcDriverLoader.verified(),
                JdbcRuntime.production());
        behaviors = List.of(new JdbcNodeBehavior(JdbcStatementProfile.Kind.QUERY, executor),
                new JdbcNodeBehavior(JdbcStatementProfile.Kind.INSERT, executor));
    }
    @Override public String id() { return "ai.ravenroot.extensions.jdbc"; }
    @Override public String version() { return "1.0.0"; }
    @Override public String sdkContract() { return NodeSdk.CONTRACT; }
    @Override public List<NodeBehavior> behaviors() { return behaviors; }
}
