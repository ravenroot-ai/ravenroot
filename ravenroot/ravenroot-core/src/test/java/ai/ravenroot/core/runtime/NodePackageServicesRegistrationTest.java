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
