package ai.ravenroot.core.runtime;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.deployment.TrustedIngress;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.OutboundHttpRequest;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodePackageServicesRegistrationTest {

    @Test
    void sdkTwoReceivesTheExactPackageScopedView() {
        NodePackageServices granted = services(Set.of(NodePackageCapability.OUTBOUND_HTTP));
        AtomicBoolean legacyCalled = new AtomicBoolean();
        AtomicBoolean serviceCalled = new AtomicBoolean();
        NodePackage nodePackage = packageWith("test.services", NodeSdk.CONTRACT,
                behavior("service-probe", Set.of(NodePackageCapability.OUTBOUND_HTTP),
                        legacyCalled, serviceCalled, granted));
        var serviceRegistry = NodePackageServiceRegistry.builder().grant(nodePackage.id(), granted).build();

        BehaviorRegistry registry = NodePackages.register(new BehaviorRegistry(), nodePackage, serviceRegistry);
        registry.create(new GraphNode("probe", NodeKind.BEHAVIOR, "service-probe", Map.of())).orElseThrow();

        assertTrue(serviceCalled.get());
        assertFalse(legacyCalled.get());
    }

    @Test
    void sdkOneUsesOnlyTheLegacyBridgeEvenWhenAnOperatorGrantExists() {
        NodePackageServices granted = services(Set.of(NodePackageCapability.OUTBOUND_HTTP));
        AtomicBoolean legacyCalled = new AtomicBoolean();
        AtomicBoolean serviceCalled = new AtomicBoolean();
        NodePackage legacy = packageWith("test.legacy", NodeSdk.LEGACY_CONTRACT,
                behavior("legacy-probe", Set.of(), legacyCalled, serviceCalled, granted));
        var serviceRegistry = NodePackageServiceRegistry.builder().grant(legacy.id(), granted).build();

        BehaviorRegistry registry = NodePackages.register(new BehaviorRegistry(), legacy, serviceRegistry);
        registry.create(new GraphNode("probe", NodeKind.BEHAVIOR, "legacy-probe", Map.of())).orElseThrow();

        assertTrue(legacyCalled.get());
        assertFalse(serviceCalled.get());
    }

    @Test
    void sourceFactoriesUseTheExactSdkTwoViewAndKeepSdkOneOnTheLegacyBridge() {
        NodePackageServices granted = services(Set.of(NodePackageCapability.CREDENTIAL_RESOLUTION));
        var serviceRegistry = NodePackageServiceRegistry.builder().grant("test.source-two", granted)
                .grant("test.source-one", granted).build();
        AtomicBoolean sdkTwoLegacy = new AtomicBoolean();
        AtomicBoolean sdkTwoServices = new AtomicBoolean();
        AtomicBoolean sdkOneLegacy = new AtomicBoolean();
        AtomicBoolean sdkOneServices = new AtomicBoolean();

        BehaviorRegistry registry = NodePackages.registerAll(new BehaviorRegistry(), List.of(
                packageWith("test.source-two", NodeSdk.CONTRACT,
                        sourceBehavior("source-two", sdkTwoLegacy, sdkTwoServices, granted)),
                packageWith("test.source-one", NodeSdk.LEGACY_CONTRACT,
                        sourceBehavior("source-one", sdkOneLegacy, sdkOneServices, granted))), serviceRegistry);

        InboundSourceContext context = sourceContext();
        registry.sourceCapableFactory("source-two").orElseThrow()
                .createSource(new GraphNode("two", NodeKind.BEHAVIOR, "source-two", Map.of()), context);
        registry.sourceCapableFactory("source-one").orElseThrow()
                .createSource(new GraphNode("one", NodeKind.BEHAVIOR, "source-one", Map.of()), context);

        assertTrue(sdkTwoServices.get());
        assertFalse(sdkTwoLegacy.get());
        assertTrue(sdkOneLegacy.get());
        assertFalse(sdkOneServices.get());
    }

    @Test
    void aMissingRequiredGrantRefusesAllPackagesBeforeRegistryMutation() {
        NodePackage first = packageWith("test.first", NodeSdk.CONTRACT,
                behavior("first-probe", Set.of(), new AtomicBoolean(), new AtomicBoolean(), null));
        NodePackage missing = packageWith("test.missing", NodeSdk.CONTRACT,
                behavior("missing-probe", Set.of(NodePackageCapability.OUTBOUND_WEBSOCKET),
                        new AtomicBoolean(), new AtomicBoolean(), null));
        BehaviorRegistry registry = new BehaviorRegistry();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> NodePackages.registerAll(registry, List.of(first, missing),
                        NodePackageServiceRegistry.empty()));

        assertTrue(refused.getMessage().contains("outbound-websocket"), refused.getMessage());
        assertTrue(registry.descriptors().isEmpty(), "preflight must leave no earlier package registered");
    }

    @Test
    void duplicatePackageIdsAreRefusedBeforeAnyBehaviorRegistration() {
        NodePackage first = packageWith("test.duplicate", NodeSdk.CONTRACT,
                behavior("first", Set.of(), new AtomicBoolean(), new AtomicBoolean(), null));
        NodePackage second = packageWith("test.duplicate", NodeSdk.CONTRACT,
                behavior("second", Set.of(), new AtomicBoolean(), new AtomicBoolean(), null));
        BehaviorRegistry registry = new BehaviorRegistry();

        assertThrows(IllegalArgumentException.class,
                () -> NodePackages.registerAll(registry, List.of(first, second)));
        assertTrue(registry.descriptors().isEmpty());
    }

    @Test
    void packageIdsAreBoundedLowercaseSafeTokens() {
        for (String invalid : List.of("Upper.Case", "../escape", "with space", "", "x".repeat(201))) {
            NodePackage bad = packageWith(invalid, NodeSdk.CONTRACT,
                    behavior("probe", Set.of(), new AtomicBoolean(), new AtomicBoolean(), null));
            assertThrows(IllegalArgumentException.class,
                    () -> NodePackages.register(new BehaviorRegistry(), bad), invalid);
        }
    }

    @Test
    void aGrantForAnotherPackageDoesNotSatisfyTheRequirement() {
        NodePackageServices granted = services(Set.of(NodePackageCapability.OUTBOUND_HTTP));
        NodePackage nodePackage = packageWith("test.target", NodeSdk.CONTRACT,
                behavior("target", Set.of(NodePackageCapability.OUTBOUND_HTTP),
                        new AtomicBoolean(), new AtomicBoolean(), null));
        var registry = NodePackageServiceRegistry.builder().grant("test.other", granted).build();

        assertThrows(IllegalArgumentException.class,
                () -> NodePackages.register(new BehaviorRegistry(), nodePackage, registry));
    }

    @Test
    void anOptionalButAbsentServiceFailsAtInvocationWithoutBlockingActivation() {
        NodeBehavior optional = new NodeBehavior() {
            @Override public NodeTypeDescriptor descriptor() {
                return new NodeTypeDescriptor("optional-service", "Optional", "Test", "", "actor", false,
                        List.of(), Set.of());
            }

            @Override public NodeAction create(NodeConfiguration configuration) {
                throw new AssertionError("SDK /2 must call the service overload");
            }

            @Override public NodeAction create(NodeConfiguration configuration, NodePackageServices services) {
                return message -> services.outboundHttp().execute(message, new OutboundHttpRequest(
                                java.net.URI.create("https://example.invalid/"), "GET", Map.of(), null,
                                java.time.Duration.ofSeconds(1), null))
                        .completion().thenApply(ignored -> NodeResult.continueWith(message.payload()));
            }
        };
        BehaviorRegistry registry = NodePackages.register(new BehaviorRegistry(),
                packageWith("test.optional", NodeSdk.CONTRACT, optional));
        var handler = registry.create(new GraphNode("n", NodeKind.BEHAVIOR, "optional-service", Map.of()))
                .orElseThrow();

        CompletionException refusal = assertThrows(CompletionException.class,
                () -> handler.handle(message()).toCompletableFuture().join());
        assertEquals(NodePackageServiceException.Reason.SERVICE_UNAVAILABLE,
                ((NodePackageServiceException) refusal.getCause()).reason());
    }

    @Test
    void capturesTheActualSdkTwoProfileOnceAndKeepsItsSnapshotImmutable() {
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var current = new java.util.concurrent.atomic.AtomicReference<>(
                java.util.Optional.of(ai.ravenroot.api.node.service.NodePackageEgressCapacityProfile.noManagedEgress()));
        NodePackageServices granted = profiled(() -> { reads.incrementAndGet(); return current.get(); });
        NodePackage nodePackage = packageWith("test.snapshot", NodeSdk.CONTRACT,
                behavior("snapshot", Set.of(), new AtomicBoolean(), new AtomicBoolean(), granted));
        var registry = NodePackages.register(new BehaviorRegistry(), nodePackage,
                NodePackageServiceRegistry.builder().grant(nodePackage.id(), granted).build());
        var snapshot = registry.nodePackageBindings();
        current.set(java.util.Optional.empty());
        registry.create(new GraphNode("probe", NodeKind.BEHAVIOR, "snapshot", Map.of())).orElseThrow();
        assertEquals(1, reads.get());
        assertTrue(snapshot.getFirst().capacityProfile().orElseThrow().limits().isEmpty());
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        assertEquals(snapshot, registry.nodePackageBindings());
    }

    @Test
    void sdkOneAndUnusedGrantsNeverConsultTheGrantedProfile() {
        NodePackageServices unused = profiled(() -> { throw new AssertionError("unused grant inspected"); });
        var registry = NodePackages.register(new BehaviorRegistry(),
                packageWith("test.legacy", NodeSdk.LEGACY_CONTRACT, simpleBehavior("legacy")),
                NodePackageServiceRegistry.builder().grant("test.legacy", unused).grant("test.unused", unused).build());
        assertEquals(java.util.Optional.of(
                ai.ravenroot.api.node.service.NodePackageEgressCapacityProfile.noManagedEgress()),
                registry.nodePackageBindings().getFirst().capacityProfile());
    }

    @Test
    void customUnknownIsNotInferredFromEmptyCapabilitiesAndSnapshotsAreSorted() {
        var unknown = services(Set.of());
        var registry = NodePackages.registerAll(new BehaviorRegistry(), List.of(
                packageWith("test.z", NodeSdk.CONTRACT, simpleBehavior("z")),
                packageWith("test.a", NodeSdk.CONTRACT, simpleBehavior("a"))),
                NodePackageServiceRegistry.builder().grant("test.z", unknown).build());
        assertEquals(List.of("test.a", "test.z"), registry.nodePackageBindings().stream()
                .map(binding -> binding.identity().packageId()).toList());
        assertTrue(registry.nodePackageBindings().get(0).capacityProfile().isPresent());
        assertTrue(registry.nodePackageBindings().get(1).capacityProfile().isEmpty());
        assertEquals(registry.nodePackageIdentities(), registry.nodePackageBindings().stream()
                .map(BehaviorRegistry.RegisteredNodePackageBinding::identity).toList());
    }

    @Test
    void aLaterNullThrowingOrInvalidProfileLeavesAllRegistryProjectionsUntouched() {
        List<java.util.function.Supplier<java.util.Optional<ai.ravenroot.api.node.service.NodePackageEgressCapacityProfile>>>
                malformed = List.of(() -> null, () -> { throw new IllegalStateException("private-provider-value"); },
                () -> java.util.Optional.of(ai.ravenroot.api.node.service.NodePackageEgressCapacityProfile.bounded(
                        0, 1, 1, 1, 1, 1, 1, java.time.Duration.ofSeconds(1),
                        java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1), 1)));
        for (var declaration : malformed) {
            var registry = new BehaviorRegistry();
            var failure = assertThrows(IllegalArgumentException.class, () -> NodePackages.registerAll(registry,
                    List.of(packageWith("test.first", NodeSdk.CONTRACT, simpleBehavior("first")),
                            packageWith("test.bad", NodeSdk.CONTRACT, simpleBehavior("bad"))),
                    NodePackageServiceRegistry.builder().grant("test.bad", profiled(declaration)).build()));
            assertEquals("Node package 'test.bad' has an invalid egress capacity profile", failure.getMessage());
            assertEquals(null, failure.getCause());
            assertTrue(registry.descriptors().isEmpty());
            assertTrue(registry.catalogSources().isEmpty());
            assertTrue(registry.nodePackageBindings().isEmpty());
        }
    }

    @Test
    void repeatedPackagesRequireTheSameIdentityAndProfileBeforeAnyNewBehaviorIsAdded() {
        var registry = NodePackages.register(new BehaviorRegistry(),
                packageWith("test.repeat", NodeSdk.CONTRACT, simpleBehavior("first")));
        NodePackages.register(registry, packageWith("test.repeat", NodeSdk.CONTRACT, simpleBehavior("second")));
        assertEquals(2, registry.descriptors().size());
        var snapshot = registry.nodePackageBindings();
        var unknown = NodePackageServiceRegistry.builder().grant("test.repeat", services(Set.of())).build();
        assertThrows(IllegalArgumentException.class, () -> NodePackages.registerAll(registry,
                List.of(packageWith("test.other", NodeSdk.CONTRACT, simpleBehavior("other")),
                        packageWith("test.repeat", NodeSdk.CONTRACT, simpleBehavior("third"))), unknown));
        assertTrue(registry.descriptor("other").isEmpty());
        assertTrue(registry.descriptor("third").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> NodePackages.register(registry,
                packageWith("test.repeat", NodeSdk.LEGACY_CONTRACT, simpleBehavior("legacy-third"))));
        assertEquals(snapshot, registry.nodePackageBindings());
        assertEquals(2, registry.descriptors().size());
        NodePackages.register(registry, packageWith("test.last", NodeSdk.CONTRACT, simpleBehavior("last")));
        assertEquals(1, snapshot.size(), "an earlier projection is an immutable snapshot");
    }

    private static NodeBehavior simpleBehavior(String name) {
        return behavior(name, Set.of(), new AtomicBoolean(), new AtomicBoolean(), null);
    }

    private static NodePackageServices profiled(java.util.function.Supplier<java.util.Optional<
            ai.ravenroot.api.node.service.NodePackageEgressCapacityProfile>> profile) {
        NodePackageServices deny = NodePackageServices.unavailable();
        return new NodePackageServices() {
            @Override public java.util.Optional<ai.ravenroot.api.node.service.NodePackageEgressCapacityProfile>
                    egressCapacityProfile() { return profile.get(); }
            @Override public Set<NodePackageCapability> capabilities() { return Set.of(); }
            @Override public ai.ravenroot.api.node.service.NodeCredentialService credentials() { return deny.credentials(); }
            @Override public ai.ravenroot.api.node.service.OutboundHttpService outboundHttp() { return deny.outboundHttp(); }
            @Override public ai.ravenroot.api.node.service.OutboundWebSocketService outboundWebSocket() {
                return deny.outboundWebSocket();
            }
        };
    }

    private static NodeBehavior behavior(String name, Set<NodePackageCapability> required,
                                         AtomicBoolean legacyCalled, AtomicBoolean serviceCalled,
                                         NodePackageServices expected) {
        return new NodeBehavior() {
            @Override public NodeTypeDescriptor descriptor() {
                return new NodeTypeDescriptor(name, name, "Test", "", "actor", false, List.of(), Set.of());
            }

            @Override public Set<NodePackageCapability> requiredServices() { return required; }

            @Override public NodeAction create(NodeConfiguration configuration) {
                legacyCalled.set(true);
                return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
            }

            @Override public NodeAction create(NodeConfiguration configuration, NodePackageServices services) {
                serviceCalled.set(true);
                if (expected != null) assertSame(expected, services);
                return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
            }
        };
    }

    private static NodePackage packageWith(String id, String contract, NodeBehavior behavior) {
        return new NodePackage() {
            @Override public String id() { return id; }
            @Override public String version() { return "1"; }
            @Override public String sdkContract() { return contract; }
            @Override public List<NodeBehavior> behaviors() { return List.of(behavior); }
        };
    }

    private static NodeBehavior sourceBehavior(String name, AtomicBoolean legacyCalled,
                                               AtomicBoolean serviceCalled, NodePackageServices expected) {
        final class SourceBehavior implements NodeBehavior, InboundSourceCapable {
            @Override public NodeTypeDescriptor descriptor() {
                return new NodeTypeDescriptor(name, name, "Test", "", "source", false, List.of(), Set.of());
            }
            @Override public NodeAction create(NodeConfiguration configuration) {
                return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
            }
            @Override public InboundSource createSource(NodeConfiguration configuration,
                                                        InboundSourceContext context) {
                legacyCalled.set(true);
                return noOpSource();
            }
            @Override public InboundSource createSource(NodeConfiguration configuration,
                                                        InboundSourceContext context,
                                                        NodePackageServices services) {
                serviceCalled.set(true);
                assertSame(expected, services);
                return noOpSource();
            }
        }
        return new SourceBehavior();
    }

    private static InboundSource noOpSource() {
        return new InboundSource() {
            @Override public java.util.concurrent.CompletionStage<Void> start(InboundSourceContext context) {
                return CompletableFuture.completedFuture(null);
            }
            @Override public java.util.concurrent.CompletionStage<Void> stop() {
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    private static InboundSourceContext sourceContext() {
        return new InboundSourceContext() {
            @Override public DeploymentId deploymentId() { return DeploymentId.of("services-test"); }
            @Override public String nodeId() { return "source"; }
            @Override public ai.ravenroot.api.security.SecurityContext identity() { return TestIdentities.TENANT_A; }
            @Override public TrustedIngress ingress() { throw new UnsupportedOperationException(); }
            @Override public void reportDegraded(String sanitizedReason) { }
            @Override public void reportHealthy() { }
        };
    }

    private static NodePackageServices services(Set<NodePackageCapability> capabilities) {
        NodePackageServices deny = NodePackageServices.unavailable();
        return new NodePackageServices() {
            @Override public Set<NodePackageCapability> capabilities() { return capabilities; }
            @Override public ai.ravenroot.api.node.service.NodeCredentialService credentials() {
                return deny.credentials();
            }
            @Override public ai.ravenroot.api.node.service.OutboundHttpService outboundHttp() {
                return deny.outboundHttp();
            }
            @Override public ai.ravenroot.api.node.service.OutboundWebSocketService outboundWebSocket() {
                return deny.outboundWebSocket();
            }
        };
    }

    private static ai.ravenroot.api.execution.NodeMessage message() {
        java.util.UUID id = java.util.UUID.randomUUID();
        return new ai.ravenroot.api.execution.NodeMessage(TestIdentities.TENANT_A, id, id, id, id,
                Set.of(), "n", null, Map.of());
    }
}
