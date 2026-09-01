package ai.ravenroot.extensions.kafka;

import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import java.util.List;

public final class KafkaNodePackage implements NodePackage {
    @Override public String id() { return "ai.ravenroot.extensions.kafka"; }
    @Override public String version() { return "1.0.0"; }
    @Override public String sdkContract() { return NodeSdk.CONTRACT; }
    @Override public List<NodeBehavior> behaviors() {
        return List.of(new KafkaProduceNodeBehavior(), new KafkaConsumeNodeBehavior());
    }
}
