package ai.ravenroot.extensions.amqp091;

import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Package-local AMQP fixture factory for the server's live palette test. */
public final class AmqpPaletteTestPackage {
    private AmqpPaletteTestPackage() { }

    /** Returns real AMQP behaviors with deterministic profile and policy authority. */
    public static NodePackage create(String allowedTenant, String allowedProfile) {
        var profiles = (AmqpProfileResolver) (tenant, name) -> allowedTenant.equals(tenant)
                && allowedProfile.equals(name) ? Optional.of(profile(tenant, name)) : Optional.empty();
        var policies = (AmqpConsumerPolicyResolver) (tenant, name) -> allowedTenant.equals(tenant)
                && allowedProfile.equals(name) ? Optional.of(policy(tenant, name)) : Optional.empty();
        NodeBehavior publish = new AmqpPublishNodeBehavior(ignored -> Optional.empty(), profiles);
        NodeBehavior consume = new AmqpConsumeNodeBehavior(ignored -> Optional.empty(), profiles, policies,
                (profile, policy, password, prefetch) -> {
                    throw new AssertionError("template validation must not open an AMQP consumer");
                }, Runnable::run, Clock.systemUTC());
        return new NodePackage() {
            @Override public String id() { return "ai.ravenroot.extensions.amqp091.http-test"; }
            @Override public String version() { return "1.0.0"; }
            @Override public String sdkContract() { return NodeSdk.CONTRACT; }
            @Override public List<NodeBehavior> behaviors() { return List.of(publish, consume); }
        };
    }

    private static AmqpProfile profile(String tenant, String name) {
        return new AmqpProfile(tenant, name, "broker.example.test", 5671, true, "/tenant-a", "publisher",
                "amqp-orders", "orders", Set.of("audit"), "created", Set.of("updated"),
                Set.of("trace", "source"), Set.of("responses"), true, 5, 60_000,
                4, 100, 1_000, 4_096, 2);
    }

    private static AmqpConsumerPolicy policy(String tenant, String profile) {
        return new AmqpConsumerPolicy(tenant, profile, "orders.q", 4, Set.of("trace"), "",
                4_096, 1_024, 100, 1_000, 3, "dead-letter", 10);
    }
}
