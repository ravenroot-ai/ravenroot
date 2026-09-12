package ai.ravenroot.core.runtime;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.catalog.NodeCatalogSource;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.graph.ReservedGraphProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A third-party node package is trusted by the deployment, versioned by contract, and — the
 * part that matters most — catalogued well enough to be schema-validated like a built-in.
 */
class NodePackageRegistrationTest {

    private static final String BEHAVIOR = "example-greeting";

    // ------------------------------------------------------------- the schema-coverage guarantee

    /**
     * The reason the SDK does not build on {@code BehaviorRegistry.register(String, NodeHandler)}.
     *
     * <p>A behavior registered through the SDK carries a real descriptor, so it is covered by
     * {@code BehaviorPropertySchema} exactly like a built-in: a graph omitting a required property is
     * refused before any node is spawned. The contrast test below shows what the handler-only path
     * does with the same graph.</p>
     */
    @Test
    void anSdkRegisteredBehaviorIsSchemaValidatedLikeABuiltIn() {
        var registry = NodePackages.register(new BehaviorRegistry(), new GreetingPackage());
        assertEquals(NodeCatalogSource.Origin.BUNDLE, registry.catalogSources().get(BEHAVIOR).origin());
        assertEquals("com.example.ravenroot.nodes", registry.catalogSources().get(BEHAVIOR).bundleId());
        var schema = new BehaviorPropertySchema(registry);

        assertDoesNotThrow(() -> schema.validate(graphWith(Map.of("greeting", "hello"))),
                "a graph satisfying the package's declared schema must be accepted");

        var missing = assertThrows(BehaviorPropertySchema.BehaviorPropertyException.class,
                () -> schema.validate(graphWith(Map.of())),
                "a graph omitting a property the package declares required must be refused");
        assertEquals("greeting", missing.propertyName());

        var wrongType = assertThrows(BehaviorPropertySchema.BehaviorPropertyException.class,
                () -> schema.validate(graphWith(Map.of("greeting", "hello", "shout", "yes"))),
                "a graph writing a non-boolean into a declared boolean must be refused");
        assertEquals("shout", wrongType.propertyName());
    }

    /**
     * Characterises the handler-only path, and deliberately changes nothing about it.
     *
     * <p>{@code register(String, NodeHandler)} synthesises a descriptor with no properties, and
     * {@code BehaviorPropertySchema} skips behaviors whose descriptor declares none — so the same
     * graph that the SDK path refuses above is accepted here, unvalidated. This test exists so that
     * the difference is recorded and cannot regress silently: it is exactly why the SDK is not built
     * on this overload. Changing the overload's behaviour is a public contract change and is not
     * outside this registration boundary.</p>
     */
    @Test
    void theHandlerOnlyPathRemainsUnvalidatedAndThatIsWhyTheSdkDoesNotUseIt() {
        var registry = new BehaviorRegistry().register(BEHAVIOR,
                message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload())));
        var schema = new BehaviorPropertySchema(registry);

        assertDoesNotThrow(() -> schema.validate(graphWith(Map.of())),
                "unchanged pre-existing behaviour: a handler-registered behavior declares no properties, "
                        + "so nothing about the graph's properties is checked");
        assertTrue(registry.descriptor(BEHAVIOR).orElseThrow().properties().isEmpty(),
                "the synthesised descriptor carries no schema, which is the whole difference");
    }

    // ------------------------------------------------------------------------ cross-version

    @Test
    void aPackageBuiltAgainstAnUnsupportedContractIsRefusedAtRegistration() {
        var registry = new BehaviorRegistry();
        var refused = assertThrows(NodeSdk.IncompatibleNodePackageException.class,
                () -> NodePackages.register(registry, new GreetingPackage("ravenroot.node-sdk/3")));

        assertEquals("ravenroot.node-sdk/3", refused.declaredContract());
        assertTrue(refused.getMessage().contains(NodeSdk.CONTRACT),
                "the diagnostic must say which contract this runtime does host: " + refused.getMessage());
        assertTrue(registry.descriptor(BEHAVIOR).isEmpty(),
                "an incompatible package must register nothing at all");
    }

    @Test
    void aPackageDeclaringNoContractIsRefused() {
        var refused = assertThrows(NodeSdk.IncompatibleNodePackageException.class,
                () -> NodePackages.register(new BehaviorRegistry(), new GreetingPackage(null)));
        assertTrue(refused.getMessage().contains("no Node SDK contract"), refused.getMessage());
    }

    @Test
    void thisRuntimeHostsCurrentAndLegacyContractsOnly() {
        assertTrue(NodeSdk.supports(NodeSdk.CONTRACT));
        assertTrue(NodeSdk.supports(NodeSdk.LEGACY_CONTRACT));
        assertFalse(NodeSdk.supports("ravenroot.node-sdk/0"));
        assertFalse(NodeSdk.supports("ravenroot.node-sdk/3"));
        assertFalse(NodeSdk.supports(""));
        assertFalse(NodeSdk.supports(null));
    }

    // ------------------------------------------------------------------------ malformed packages

    @Test
    void aPackageMayNotReplaceAnExistingBehaviorName() {
        var registry = BehaviorRegistry.standard();
        var clash = assertThrows(IllegalArgumentException.class,
                () -> NodePackages.register(registry, new GreetingPackage(NodeSdk.CONTRACT, "log")));
        assertTrue(clash.getMessage().contains("already registered"), clash.getMessage());
        assertEquals("Actions", registry.descriptor("log").orElseThrow().category(),
                "the built-in entry must be untouched");
    }

    @Test
    void aPackageMayNotDeclareAPropertyGraphContentCanNeverSet() {
        var reserved = assertThrows(IllegalArgumentException.class,
                () -> NodePackages.register(new BehaviorRegistry(),
                        new ReservedPropertyPackage()));
        assertTrue(reserved.getMessage().contains(ReservedGraphProperties.PREFIX), reserved.getMessage());
    }

    @Test
    void aPackageContributingNothingIsRefusedRatherThanIgnored() {
        var empty = assertThrows(IllegalArgumentException.class,
                () -> NodePackages.register(new BehaviorRegistry(), new EmptyPackage()));
        assertTrue(empty.getMessage().contains("declares no behaviors"), empty.getMessage());
    }

    /**
     * The reserved-namespace prefix is published on the SDK so node authors and the conformance
     * contract can see it, while the runtime's own enforcement lives with the graph model. Two
     * constants with one meaning drift; this is the test that makes drift fail.
     */
    @Test
    void thePublishedReservedPrefixMatchesTheRuntimesOwn() {
        assertEquals(ReservedGraphProperties.PREFIX, NodeSdk.RESERVED_PROPERTY_PREFIX);
        assertTrue(NodeSdk.isReservedProperty("ravenroot.security.tenantId"));
        assertTrue(NodeSdk.isReservedProperty("  RavenRoot.Provenance.Synthetic  "),
                "must fold case and padding exactly as the runtime's own check does");
        assertFalse(NodeSdk.isReservedProperty("example.greeted"));
    }

    // ------------------------------------------------------------------------ it actually runs

    @Test
    void aRegisteredThirdPartyBehaviorProducesItsResult() {
        var registry = NodePackages.register(new BehaviorRegistry(), new GreetingPackage());
        var handler = registry.create(new GraphNode("probe", NodeKind.BEHAVIOR, BEHAVIOR,
                Map.of("greeting", "hello"))).orElseThrow();

        var result = handler.handle(messageFor("probe", "world")).toCompletableFuture().join();

        assertEquals("hello, world", result.payload());
        assertEquals("continue", result.outcome());
    }

    // ------------------------------------------------------------------------ diagnostics

    @Test
    void unregisteredBehaviorsAreReportedWithoutChangingWhatHappensToThem() {
        var registry = NodePackages.register(new BehaviorRegistry(), new GreetingPackage());
        var schema = new BehaviorPropertySchema(registry);
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                new GraphNode("known", NodeKind.BEHAVIOR, BEHAVIOR, Map.of("greeting", "hi")),
                new GraphNode("typo", NodeKind.BEHAVIOR, "example-greetng", Map.of()),
                new GraphNode("absent", NodeKind.BEHAVIOR, "never-installed", Map.of()),
                GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "known"), GraphEdge.to("known", "typo"),
                        GraphEdge.to("typo", "absent"), GraphEdge.to("absent", "end")));

        assertEquals(List.of("example-greetng", "never-installed"), schema.unregisteredBehaviors(graph),
                "the diagnostic names exactly the behaviors this deployment does not know, sorted");
        // The whole point: reporting them changes nothing about validation.
        assertDoesNotThrow(() -> schema.validate(graph),
                "an unregistered behavior is still skipped by validation; this test does not decide "
                        + "what happens to it at execution");
    }

    @Test
    void aFullyInstalledGraphReportsNothingMissing() {
        var registry = NodePackages.register(BehaviorRegistry.standard(), new GreetingPackage());
        var schema = new BehaviorPropertySchema(registry);
        assertTrue(schema.unregisteredBehaviors(graphWith(Map.of("greeting", "hi"))).isEmpty());
    }

    // ------------------------------------------------------------------------ fixtures

    private static ai.ravenroot.api.execution.NodeMessage messageFor(String nodeId, Object payload) {
        var id = java.util.UUID.randomUUID();
        return new ai.ravenroot.api.execution.NodeMessage(TestIdentities.TENANT_A, id, id, id, id,
                Set.of(), nodeId, payload, Map.of());
    }

    private static GraphDefinition graphWith(Map<String, Object> properties) {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                new GraphNode("probe", NodeKind.BEHAVIOR, BEHAVIOR, properties),
                GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "probe"), GraphEdge.to("probe", "end")));
    }

    /** Mirrors the starter's behavior, with one extra typed property so type checking is exercised. */
    private record GreetingBehavior(String name) implements NodeBehavior {
        @Override
        public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor(name, "Greeting", "Examples", "Prefixes the payload.",
                    "actor", false, List.of(
                    NodePropertyDescriptor.required("greeting", "Greeting", NodePropertyType.STRING,
                            "Text placed before the payload."),
                    NodePropertyDescriptor.optional("shout", "Shout", NodePropertyType.BOOLEAN,
                            "Upper-cases the result.", "false")),
                    Set.of("deterministic"));
        }

        @Override
        public NodeAction create(NodeConfiguration configuration) {
            String greeting = configuration.requiredProperty("greeting");
            boolean shout = Boolean.parseBoolean(configuration.property("shout", "false"));
            return message -> {
                String text = greeting + ", " + message.payload();
                return CompletableFuture.completedFuture(
                        NodeResult.continueWith(shout ? text.toUpperCase(java.util.Locale.ROOT) : text));
            };
        }
    }

    // ------------------------------------------------------------- what a package may declare

    /**
     * The Node SDK contract has never constrained {@code version()} or {@code sdkContract()}, and
     * describes the version as being for diagnostics. Registration must therefore keep accepting a
     * value that is neither a token nor present at all.
     *
     * <p>This is a regression assertion with a specific shape in mind. Recording a package's identity
     * in an execution manifest tempts a validating record, and a validating record would fire here —
     * inside plugin activation, at start-up, after earlier packages have already registered — turning
     * an upgrade into a deployment that will not boot. The pinned identity is a digest precisely so
     * that this test can pass.</p>
     */
    @Test
    void aPackageMayDeclareAVersionThatIsNotATokenOrNoVersionAtAll() {
        assertDoesNotThrow(() -> NodePackages.register(new BehaviorRegistry(),
                        new LooseVersionPackage("1.0 beta", NodeSdk.CONTRACT, "loose.spaced")),
                "a spaced version is what the SDK contract has always permitted");
        assertDoesNotThrow(() -> NodePackages.register(new BehaviorRegistry(),
                        new LooseVersionPackage(null, NodeSdk.CONTRACT, "loose.absent")),
                "and a package that declares no version at all still registers");
        assertDoesNotThrow(() -> NodePackages.register(new BehaviorRegistry(),
                        new LooseVersionPackage("", NodeSdk.CONTRACT, "loose.empty")));
    }

    /** Two builds a manifest must still be able to tell apart, whatever their versions look like. */
    @Test
    void looselyShapedVersionsAreStillDistinguishedFromOneAnother() {
        var first = NodePackages.register(new BehaviorRegistry(),
                new LooseVersionPackage("1.0 beta", NodeSdk.CONTRACT, "loose.compare"))
                .nodePackageIdentities();
        var second = NodePackages.register(new BehaviorRegistry(),
                new LooseVersionPackage("1.0 rc", NodeSdk.CONTRACT, "loose.compare"))
                .nodePackageIdentities();
        var absent = NodePackages.register(new BehaviorRegistry(),
                new LooseVersionPackage(null, NodeSdk.CONTRACT, "loose.compare"))
                .nodePackageIdentities();

        assertEquals(1, first.size());
        assertEquals(first.get(0).packageId(), second.get(0).packageId());
        assertNotEquals(first.get(0).identityDigest(), second.get(0).identityDigest(),
                "two builds differing only in an unconstrained version are still two identities");
        assertNotEquals(first.get(0).identityDigest(), absent.get(0).identityDigest(),
                "and declaring no version is a third one, not a match for either");
        assertFalse(first.get(0).toString().contains("1.0 beta"),
                "the declared string is digested rather than retained, so nothing downstream has to "
                        + "decide whether it is safe to render");
    }

    private record LooseVersionPackage(String declaredVersion, String contract, String packageId)
            implements NodePackage {

        @Override
        public String id() {
            return packageId;
        }

        @Override
        public String version() {
            return declaredVersion;
        }

        @Override
        public String sdkContract() {
            return contract;
        }

        @Override
        public List<NodeBehavior> behaviors() {
            return List.of(new GreetingBehavior(packageId + ".behavior"));
        }
    }

    private record GreetingPackage(String contract, String behaviorName) implements NodePackage {
        GreetingPackage() {
            this(NodeSdk.CONTRACT, BEHAVIOR);
        }

        GreetingPackage(String contract) {
            this(contract, BEHAVIOR);
        }

        @Override
        public String id() {
            return "com.example.ravenroot.nodes";
        }

        @Override
        public String version() {
            return "1.0.0";
        }

        @Override
        public String sdkContract() {
            return contract;
        }

        @Override
        public List<NodeBehavior> behaviors() {
            return List.of(new GreetingBehavior(behaviorName));
        }
    }

    private record ReservedPropertyPackage() implements NodePackage {
        @Override
        public String id() {
            return "com.example.ravenroot.reserved";
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
            return List.of(new NodeBehavior() {
                @Override
                public NodeTypeDescriptor descriptor() {
                    return new NodeTypeDescriptor("reserved-probe", "Reserved", "Examples", "",
                            "actor", false,
                            List.of(NodePropertyDescriptor.optional("ravenroot.security.tenantId",
                                    "Tenant", NodePropertyType.STRING, "", "")),
                            Set.of());
                }

                @Override
                public NodeAction create(NodeConfiguration configuration) {
                    return message -> CompletableFuture.completedFuture(
                            NodeResult.continueWith(message.payload()));
                }
            });
        }
    }

    private record EmptyPackage() implements NodePackage {
        @Override
        public String id() {
            return "com.example.ravenroot.empty";
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
}
