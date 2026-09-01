package ai.ravenroot.extensions.amqp091;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.testkit.api.NodeBehaviorContract;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Clock;

class AmqpNodePackageConformanceTest extends NodeBehaviorContract {
    @Override
    protected NodePackage nodePackage() {
        return new NodePackage() {
            @Override public String id() { return "ai.ravenroot.extensions.amqp091"; }
            @Override public String version() { return "1.0.0"; }
            @Override public String sdkContract() { return ai.ravenroot.api.node.NodeSdk.CONTRACT; }
            @Override public List<ai.ravenroot.api.node.NodeBehavior> behaviors() {
                return List.of(new AmqpPublishNodeBehavior(reference -> Optional.empty(),
                        (tenant, name) -> Optional.of(AmqpTestSupport.profile(tenant, name, 1, 1, 100, 0))),
                        new AmqpConsumeNodeBehavior(reference -> Optional.empty(),
                                (tenant, name) -> Optional.of(AmqpTestSupport.profile(tenant, name, 1, 1, 100, 0)),
                                (tenant, name) -> Optional.of(AmqpConsumerTestSupport.policy()),
                                new AmqpConsumerTestSupport.FakeProtocol(), Runnable::run, Clock.systemUTC()));
            }
        };
    }

    @Override
    protected NodeConfiguration configurationFor(NodeTypeDescriptor descriptor) {
        return new NodeConfiguration("amqp", descriptor.behavior(), Map.of("brokerProfile", AmqpTestSupport.PROFILE));
    }
}
