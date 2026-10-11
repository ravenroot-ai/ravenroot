package ai.ravenroot.extensions.systemone;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.testkit.api.NodeBehaviorContract;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SystemOneNodePackageConformanceTest extends NodeBehaviorContract {
    @Override protected NodePackage nodePackage() {
        return new SystemOneNodePackage(name -> java.util.Optional.empty());
    }

    @Override protected NodeConfiguration configurationFor(NodeTypeDescriptor descriptor) {
        return new NodeConfiguration("evaluate", descriptor.behavior(), Map.of(
                "profile", "", "specification", SystemOneTestSupport.SPECIFICATION));
    }

    @org.junit.jupiter.api.Test
    void contributesOnlyThePortableEvaluator() {
        assertEquals(java.util.List.of(SystemOneEvaluateNodeBehavior.BEHAVIOR),
                nodePackage().behaviors().stream().map(behavior -> behavior.descriptor().behavior()).toList());
    }
}
