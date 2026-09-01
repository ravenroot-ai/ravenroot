package ai.ravenroot.extensions.jdbc;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.testkit.api.NodeBehaviorContract;

import java.util.Map;

class JdbcNodePackageConformanceTest extends NodeBehaviorContract {
    @Override protected NodePackage nodePackage() { return new JdbcNodePackage(); }
    @Override protected NodeConfiguration configurationFor(NodeTypeDescriptor descriptor) {
        return new NodeConfiguration("jdbc", descriptor.behavior(), Map.of("profile", "main", "statement",
                descriptor.behavior().equals("jdbc.query") ? "find" : "add"));
    }
}
