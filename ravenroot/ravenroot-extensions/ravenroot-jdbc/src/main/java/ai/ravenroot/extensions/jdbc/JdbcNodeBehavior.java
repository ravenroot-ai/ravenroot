package ai.ravenroot.extensions.jdbc;

import ai.ravenroot.api.catalog.NodeOutcomeDescriptor;
import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;

import java.util.List;
import java.util.Set;

final class JdbcNodeBehavior implements NodeBehavior {
    private final JdbcStatementProfile.Kind kind;
    private final NodeTypeDescriptor descriptor;
    private final JdbcExecutor executor;

    JdbcNodeBehavior(JdbcStatementProfile.Kind kind, JdbcExecutor executor) {
        this.kind = kind; this.executor = executor;
        String behavior = kind == JdbcStatementProfile.Kind.QUERY ? "jdbc.query" : "jdbc.insert";
        this.descriptor = new NodeTypeDescriptor(behavior,
                kind == JdbcStatementProfile.Kind.QUERY ? "Profiled JDBC query" : "Profiled JDBC insert",
                kind == JdbcStatementProfile.Kind.QUERY ? "Data" : "Integrations",
                "Executes one operator-approved prepared JDBC statement with bounded named parameters.",
                "database", false,
                List.of(NodePropertyDescriptor.required("profile", "Profile", NodePropertyType.STRING,
                                "Operator-owned tenant JDBC profile."),
                        NodePropertyDescriptor.required("statement", "Statement", NodePropertyType.STRING,
                                "Operator-approved statement id.")),
                kind == JdbcStatementProfile.Kind.QUERY ? Set.of("network") : Set.of("network", "side-effect"))
                .withOutcomes(NodeOutcomeDescriptor.literal("continue", "The bounded JDBC result."));
    }

    @Override public Set<NodePackageCapability> requiredServices() {
        return Set.of(NodePackageCapability.CREDENTIAL_RESOLUTION);
    }
    @Override public NodeTypeDescriptor descriptor() { return descriptor; }
    @Override public NodeAction create(NodeConfiguration configuration) {
        return create(configuration, NodePackageServices.unavailable());
    }
    @Override public NodeAction create(NodeConfiguration configuration, NodePackageServices services) {
        String profile = identifier(configuration.requiredProperty("profile"));
        String statement = identifier(configuration.requiredProperty("statement"));
        return message -> executor.execute(message, services, profile, statement, kind)
                .thenApply(value -> new NodeResult("continue", value, message.attributes()));
    }
    private static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
            throw new JdbcFailure(JdbcFailure.Code.PROFILE_UNAVAILABLE);
        return value;
    }
}
