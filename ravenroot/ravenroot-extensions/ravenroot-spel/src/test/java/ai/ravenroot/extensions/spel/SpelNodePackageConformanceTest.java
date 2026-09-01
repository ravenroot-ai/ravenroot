package ai.ravenroot.extensions.spel;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.testkit.api.NodeBehaviorContract;

import java.util.Map;

class SpelNodePackageConformanceTest extends NodeBehaviorContract {
    @Override
    protected NodePackage nodePackage() {
        return new SpelNodePackage();
    }

    @Override
    protected NodeConfiguration configurationFor(NodeTypeDescriptor descriptor) {
        Map<String, Object> properties = descriptor.behavior().equals("spel.decision")
                ? Map.of("expression", "true", "trueOutcome", "yes", "falseOutcome", "no")
                : Map.of("expression", "'conformance'");
        return new NodeConfiguration("spel", descriptor.behavior(), properties);
    }
}
