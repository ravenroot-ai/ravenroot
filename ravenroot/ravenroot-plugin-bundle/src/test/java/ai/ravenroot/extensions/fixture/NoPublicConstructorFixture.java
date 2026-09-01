package ai.ravenroot.extensions.fixture;

import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;

import java.util.List;

/** Test fixture only (PLAT-12): a {@code NodePackage} with no public no-argument constructor. */
public final class NoPublicConstructorFixture implements NodePackage {

    public NoPublicConstructorFixture(String notAllowed) {
    }

    @Override
    public String id() {
        return "test.no-public-constructor";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public String sdkContract() {
        return NodeSdk.CONTRACT;
    }

    @Override
    public List<NodeBehavior> behaviors() {
        return List.of();
    }
}
