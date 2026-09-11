package ai.ravenroot.core.runtime;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.deployment.TrustedIngress;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.NodePackageEgressCapacityProfile;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.persistence.PinnedNodePackage;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SourceAuthorityLifecycleTest {

    @Test
    void exactContextIsDeniedDuringConstructionActivatedForStartAndRevokedWithOperations() {
        BehaviorRegistry registry = registry();
        InboundSourceContext context = context("source-a");
        var registration = registry.registerSourceAuthority(context, "test.source",
                DeploymentId.of("deployment-a"), "source-a", 7, identity());

        assertReason(() -> registry.sourceAuthorityFor("test.source", context));
        registration.activate();
        var authority = registry.sourceAuthorityFor("test.source", context);
        assertEquals(identity(), authority.identity());

        AtomicInteger openingCancelled = new AtomicInteger();
        AtomicInteger sessionCancelled = new AtomicInteger();
        var operation = authority.track(openingCancelled::incrementAndGet);
        operation.transfer(sessionCancelled::incrementAndGet);

        registration.close();

        assertEquals(0, openingCancelled.get());
        assertEquals(1, sessionCancelled.get());
        assertReason(authority::requireActive);
        assertReason(() -> registry.sourceAuthorityFor("test.source", context));
    }

    @Test
    void forgedEquivalentOrForeignPackageContextsNeverResolve() {
        BehaviorRegistry registry = registry();
        InboundSourceContext registered = context("source-a");
        var registration = registry.registerSourceAuthority(registered, "test.source",
                DeploymentId.of("deployment-a"), "source-a", 7, identity());
        registration.activate();

        assertReason(() -> registry.sourceAuthorityFor("test.source", context("source-a")));
        assertReason(() -> registry.sourceAuthorityFor("test.foreign", registered));
        registration.close();
    }

    @Test
    void transferAfterRevocationCancelsTheHandedOffResourceAndRefusesAuthority() {
        BehaviorRegistry registry = registry();
        InboundSourceContext context = context("source-a");
        var registration = registry.registerSourceAuthority(context, "test.source",
                DeploymentId.of("deployment-a"), "source-a", 8, identity());
        registration.activate();
        var operation = registry.sourceAuthorityFor("test.source", context).track(() -> { });
        registration.close();
        AtomicInteger cancelled = new AtomicInteger();

        assertReason(() -> operation.transfer(cancelled::incrementAndGet));
        assertEquals(1, cancelled.get(), "a session that loses the revoke race must be cancelled");
    }

    private static BehaviorRegistry registry() {
        NodeBehavior behavior = new NodeBehavior() {
            @Override public NodeTypeDescriptor descriptor() {
                return new NodeTypeDescriptor("source.behavior", "Source", "Test", "", "source",
                        false, List.of(), Set.of());
            }
            @Override public ai.ravenroot.api.node.NodeAction create(NodeConfiguration configuration) {
                return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
            }
        };
        var registry = new BehaviorRegistry();
        registry.registerPackageFactory(new NodePackages.SdkNodeBehaviorFactory(behavior), "test.source",
                PinnedNodePackage.of("test.source", "1", "2"), java.util.Optional.of(
                        NodePackageEgressCapacityProfile.bounded(1024, 2048, 1024, 8, 4, 2, 4, 100,
                                Duration.ofSeconds(3), Duration.ofMinutes(1), Duration.ofSeconds(10))));
        return registry;
    }

    private static InboundSourceContext context(String nodeId) {
        return new InboundSourceContext() {
            @Override public DeploymentId deploymentId() { return DeploymentId.of("deployment-a"); }
            @Override public String nodeId() { return nodeId; }
            @Override public SecurityContext identity() { return identity(); }
            @Override public TrustedIngress ingress() { throw new UnsupportedOperationException(); }
            @Override public void reportDegraded(String sanitizedReason) { }
            @Override public void reportHealthy() { }
        };
    }

    private static SecurityContext identity() {
        return new SecurityContext("request", "tenant-a", "subject", PrincipalType.USER, "issuer");
    }

    private static void assertReason(Runnable operation) {
        NodePackageServiceException failure = assertThrows(NodePackageServiceException.class, operation::run);
        assertEquals(NodePackageServiceException.Reason.SERVICE_UNAVAILABLE, failure.reason());
    }
}
