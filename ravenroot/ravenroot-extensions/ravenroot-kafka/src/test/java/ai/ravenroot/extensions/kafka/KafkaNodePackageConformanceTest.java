package ai.ravenroot.extensions.kafka;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.testkit.api.NodeBehaviorContract;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class KafkaNodePackageConformanceTest extends NodeBehaviorContract {
    @Override protected NodePackage nodePackage(){return new NodePackage(){@Override public String id(){return"ai.ravenroot.extensions.kafka";}@Override public String version(){return"1.0.0";}@Override public String sdkContract(){return ai.ravenroot.api.node.NodeSdk.CONTRACT;}@Override public List<ai.ravenroot.api.node.NodeBehavior> behaviors(){return List.of(new KafkaProduceNodeBehavior(ref->Optional.empty(),(t,n)->Optional.of(KafkaTestSupport.profile(t,n,1000))),new KafkaConsumeNodeBehavior(ref->Optional.empty(),(t,n)->Optional.of(KafkaConsumerTestSupport.profile())));}};}
    @Override protected NodeConfiguration configurationFor(NodeTypeDescriptor descriptor){return new NodeConfiguration("kafka",descriptor.behavior(),Map.of("clusterProfile",KafkaTestSupport.PROFILE));}
}
