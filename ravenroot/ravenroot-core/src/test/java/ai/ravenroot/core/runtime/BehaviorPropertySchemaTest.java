package ai.ravenroot.core.runtime;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The trusted catalog types a behavior's properties, and everything it does not
 * name is left alone.
 *
 * <p>The two halves are tested together on purpose. A schema that only rejected would pass its own
 * tests while breaking the lossless round trip, and both properties are required.</p>
 */
class BehaviorPropertySchemaTest {

    private static final String BEHAVIOR = "typed-probe";

    private final BehaviorPropertySchema schema = new BehaviorPropertySchema(registry());

    // ------------------------------------------------------------------ typed schema

    @Test
    void acceptsEveryDeclaredPropertyAtItsDeclaredType() {
        assertDoesNotThrow(() -> schema.validate(graphWith(Map.of(
                "requiredText", "hello",
                "count", "-42",
                "ratio", "3.14",
                "enabled", "false",
                "endpoint", "https://example.test/hook",
                "credentialRef", "acme-main",
                "mode", "fast"))));
    }

    @Test
    void rejectsAnIntegerPropertyThatIsNotAnInteger() {
        var failure = assertThrows(BehaviorPropertySchema.BehaviorPropertyException.class,
                () -> schema.validate(graphWith(Map.of("requiredText", "hello", "count", "12.5"))));

        assertEquals("probe", failure.nodeId());
        assertEquals("count", failure.propertyName());
        assertTrue(failure.getMessage().contains("must be an integer"), failure.getMessage());
    }

    @Test
    void rejectsABooleanThatIsNotExactlyTrueOrFalse() {
        // Boolean.parseBoolean maps every non-"true" string to false, so "yes" would silently read as
        // an explicit disable. For a property that can gate a safety control that is the wrong
        // failure: the graph author asked for on and would have been given off, with no signal.
        for (String hopeful : List.of("yes", "1", "on", "tru")) {
            assertThrows(BehaviorPropertySchema.BehaviorPropertyException.class,
                    () -> schema.validate(graphWith(Map.of("requiredText", "hello", "enabled", hopeful))),
                    "accepted '" + hopeful + "' as a boolean");
        }
        // Case-insensitive, matching Boolean.parseBoolean exactly.
        assertDoesNotThrow(() -> schema.validate(graphWith(Map.of("requiredText", "x", "enabled", "true"))));
        assertDoesNotThrow(() -> schema.validate(graphWith(Map.of("requiredText", "x", "enabled", "FALSE"))));
    }

    @Test
    void rejectsAPaddedTypedValueBecauseItWouldValidateAsOneThingAndExecuteAsAnother() {
        // The sharp case: Boolean.parseBoolean("TRUE ") is false. A validator that trimmed would
        // certify this as a valid *true* and the runtime would then read *false*, with nothing
        // reporting a problem. "  42" is the same defect for integers, because NodeProperties.string
        // does not trim while NodeProperties.required does.
        assertThrows(BehaviorPropertySchema.BehaviorPropertyException.class,
                () -> schema.validate(graphWith(Map.of("requiredText", "x", "enabled", "TRUE "))));
        assertThrows(BehaviorPropertySchema.BehaviorPropertyException.class,
                () -> schema.validate(graphWith(Map.of("requiredText", "x", "count", "  42"))));
        assertThrows(BehaviorPropertySchema.BehaviorPropertyException.class,
                () -> schema.validate(graphWith(Map.of("requiredText", "x", "count", "42\n"))));

        // Free text is unaffected: lexical whitespace there is meaningful and preserved.
        assertDoesNotThrow(() -> schema.validate(graphWith(Map.of("requiredText", "  padded on purpose  "))));
    }

    @Test
    void treatsABlankOptionalPropertyAsAbsentRatherThanInvalid() {
        // An absent optional property keeps its catalog default at execution; it is not a violation.
        assertDoesNotThrow(() -> schema.validate(graphWith(Map.of("requiredText", "hello", "enabled", ""))));
        assertDoesNotThrow(() -> schema.validate(graphWith(Map.of("requiredText", "hello", "count", "   "))));
    }

    @Test
    void rejectsARelativeUriBecauseAnEndpointWithoutASchemeIsNotAnEndpoint() {
        assertThrows(BehaviorPropertySchema.BehaviorPropertyException.class,
                () -> schema.validate(graphWith(Map.of("requiredText", "x", "endpoint", "/relative/path"))));
        assertThrows(BehaviorPropertySchema.BehaviorPropertyException.class,
                () -> schema.validate(graphWith(Map.of("requiredText", "x", "endpoint", "not a uri at all"))));
    }

    @Test
    void rejectsACredentialReferenceCarryingWhitespace() {
        // A reference is an identifier, and a padded one is a typo better met at admission than as an
        // unresolvable lookup at execution. This is an error-message property, not a safety one: the
        // readers of this type are verbatim by decision, so relaxing this refusal admits
        // references that fail to resolve, not references that resolve to somebody else's secret. What
        // pins that is SecretReferenceReaderFidelityTest, which drives the readers with the values
        // refused here.
        assertThrows(BehaviorPropertySchema.BehaviorPropertyException.class,
                () -> schema.validate(graphWith(Map.of("requiredText", "x", "credentialRef", " acme-main"))));
        assertThrows(BehaviorPropertySchema.BehaviorPropertyException.class,
                () -> schema.validate(graphWith(Map.of("requiredText", "x", "credentialRef", "acme main"))));
    }

    @Test
    void rejectsAValueOutsideTheCatalogsAllowedSet() {
        var failure = assertThrows(BehaviorPropertySchema.BehaviorPropertyException.class,
                () -> schema.validate(graphWith(Map.of("requiredText", "x", "mode", "turbo"))));
        assertTrue(failure.getMessage().contains("must be one of"), failure.getMessage());
    }

    @Test
    void rejectsAnAbsentOrBlankRequiredProperty() {
        assertThrows(BehaviorPropertySchema.BehaviorPropertyException.class,
                () -> schema.validate(graphWith(Map.of("count", "1"))));
        assertThrows(BehaviorPropertySchema.BehaviorPropertyException.class,
                () -> schema.validate(graphWith(Map.of("requiredText", "   "))));
    }

    // ------------------------------------------------------------------ preservation

    @Test
    void leavesEveryUndeclaredPropertyEntirelyAlone() {
        // The preservation half of SEC-09. These names are chosen to be exactly the shapes a schema
        // written carelessly would sweep up: a type-looking value, a name colliding with another
        // behavior's declared property, and a vendor extension.
        Map<String, Object> untouched = Map.of(
                "requiredText", "hello",
                "count", "7",
                "owner", "workflow-team",
                "custom.count", "not-a-number-and-nobody-cares",
                "provider", "openai",
                "viz.annotation", "preserve me");

        assertDoesNotThrow(() -> schema.validate(graphWith(untouched)));
    }

    @Test
    void doesNotValidateNodesWhoseBehaviorIsNotInTheCatalog() {
        // Unknown behaviors belong to SEC-09 rule 3 and are handled separately. The
        // pass-through path must be reached unchanged, so an unknown behavior with nonsense
        // properties must not be rejected here.
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                new GraphNode("probe", NodeKind.BEHAVIOR, "not-registered",
                        Map.of("count", "definitely-not-an-integer")),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "probe"), GraphEdge.to("probe", "end")));

        assertDoesNotThrow(() -> schema.validate(graph));
    }

    @Test
    void doesNotValidatePropertiesOnNonBehaviorNodes() {
        var graph = new GraphDefinition(List.of(
                new GraphNode("start", NodeKind.START, null, Map.of("count", "nonsense")),
                GraphNode.error("error"), GraphNode.end("end")), List.of(GraphEdge.to("start", "end")));

        assertDoesNotThrow(() -> schema.validate(graph));
    }

    // ------------------------------------------------------------------ reserved namespace

    @Test
    void refusesAReservedPropertyEvenOnAProgrammaticallyBuiltGraph() {
        // GraphManager refuses these at parse time, but a GraphDefinition can also be constructed
        // directly, which never passes a parser. The embedded path must not be the soft one.
        var graph = new GraphDefinition(List.of(
                GraphNode.start("start"),
                new GraphNode("probe", NodeKind.BEHAVIOR, BEHAVIOR,
                        Map.of("requiredText", "x", "ravenroot.security.tenantId", "tenant-victim")),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "probe"), GraphEdge.to("probe", "end")));

        var failure = assertThrows(BehaviorPropertySchema.BehaviorPropertyException.class,
                () -> schema.validate(graph));
        assertEquals("ravenroot.security.tenantId", failure.propertyName());
    }

    @Test
    void exposesTheDeclaredSchemaAndNothingForAnUnknownBehavior() {
        assertEquals(NodePropertyType.INTEGER, schema.declaredProperties(BEHAVIOR).get("count"));
        assertEquals(Map.of(), schema.declaredProperties("not-registered"));
    }

    // ------------------------------------------------------------------ wiring

    @Test
    void graphRunnerValidatesTheWholeGraphBeforeSpawningAnySingleNode() {
        // The unit tests above prove the schema decides correctly; this proves it is actually
        // consulted, and consulted early. "Early" is the load-bearing half: validating during or
        // after spawning would mean a graph with a malformed operative property had already created
        // actors, and — once the store path runs — been hashed and recorded, so the failure would
        // arrive as a mid-flight error rather than a refused submission.
        var engine = new SpawnCountingEngine();
        var graph = ai.ravenroot.core.graph.GraphManager.from(graphWith(
                Map.of("requiredText", "hello", "count", "not-an-integer")));

        var failure = assertThrows(BehaviorPropertySchema.BehaviorPropertyException.class,
                () -> new GraphRunner(graph, engine, registry(), new ExecutionMonitor()));

        assertEquals("count", failure.propertyName());
        assertEquals(0, engine.spawns, "no actor may be spawned for a graph that fails validation");
        graph.close();
        engine.close();
    }

    @Test
    void graphRunnerStillAcceptsAGraphCarryingOnlyInertUnknownProperties() {
        var engine = new SpawnCountingEngine();
        var graph = ai.ravenroot.core.graph.GraphManager.from(graphWith(
                Map.of("requiredText", "hello", "owner", "workflow-team", "custom.count", "nonsense")));

        try (var runner = new GraphRunner(graph, engine, registry(), new ExecutionMonitor())) {
            // Inverted under ADR 0024 §3, visibly rather than deleted. This used to assert 3 --
            // "start, probe and end must all be spawned" -- which was true of the transitional
            // resident-actor-per-node runtime ADR 0024 names as a known mismatch. Ordinary WORKER
            // nodes are now created when a traversal reaches them, so a runner that has run nothing
            // has spawned nothing. What this test is actually for is unchanged: the graph is ACCEPTED,
            // which the successful construction proves.
            assertEquals(0, engine.spawns,
                    "no ordinary worker node may be spawned merely because a runner was built");
            assertTrue(runner != null);
        }
        graph.close();
        engine.close();
    }

    private static final class SpawnCountingEngine implements ai.ravenroot.api.execution.ExecutionEngine {
        private int spawns;

        @Override
        public String id() {
            return "spawn-counting";
        }

        @Override
        public Set<ai.ravenroot.api.execution.EngineCapability> capabilities() {
            return Set.of();
        }

        @Override
        public ai.ravenroot.api.execution.Scheduler scheduler() {
            return (delay, task) -> () -> true;
        }

        @Override
        public ai.ravenroot.api.execution.NodeRef spawn(String logicalName,
                                                         ai.ravenroot.api.execution.RavenNode node) {
            spawns++;
            return new ai.ravenroot.api.execution.NodeRef(logicalName);
        }

        @Override
        public java.util.concurrent.CompletionStage<ai.ravenroot.api.execution.NodeResult> send(
                ai.ravenroot.api.execution.NodeRef target, ai.ravenroot.api.execution.NodeMessage message) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    ai.ravenroot.api.execution.NodeResult.continueWith(message.payload()));
        }

        @Override
        public java.util.concurrent.CompletionStage<Void> stop(ai.ravenroot.api.execution.NodeRef target) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }


        @Override
        public ai.ravenroot.api.execution.EngineState state() {
            return ai.ravenroot.api.execution.EngineState.RUNNING;
        }

        @Override
        public java.util.Optional<ai.ravenroot.api.execution.NodeStatus> status(
                ai.ravenroot.api.execution.NodeRef target) {
            return java.util.Optional.of(StubEngineLifecycle.running(target));
        }

        @Override
        public java.util.concurrent.CompletionStage<Void> cancel(ai.ravenroot.api.execution.NodeRef target) {
            return stop(target);
        }

        @Override
        public java.util.concurrent.CompletionStage<Void> drain() {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static GraphDefinition graphWith(Map<String, Object> properties) {
        return new GraphDefinition(List.of(
                GraphNode.start("start"),
                new GraphNode("probe", NodeKind.BEHAVIOR, BEHAVIOR, properties),
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "probe"), GraphEdge.to("probe", "end")));
    }

    private static BehaviorRegistry registry() {
        return new BehaviorRegistry().registerFactory(new NodeBehaviorFactory() {
            @Override
            public NodeTypeDescriptor descriptor() {
                return new NodeTypeDescriptor(BEHAVIOR, "Typed probe", "Test", "Schema fixture", "actor",
                        false, List.of(
                        NodePropertyDescriptor.required("requiredText", "Required text",
                                NodePropertyType.TEXT, "Mandatory free text."),
                        NodePropertyDescriptor.optional("count", "Count", NodePropertyType.INTEGER, "", "0"),
                        NodePropertyDescriptor.optional("ratio", "Ratio", NodePropertyType.DECIMAL, "", "1.0"),
                        NodePropertyDescriptor.optional("enabled", "Enabled", NodePropertyType.BOOLEAN, "", "true"),
                        NodePropertyDescriptor.optional("endpoint", "Endpoint", NodePropertyType.URI, "", ""),
                        NodePropertyDescriptor.optional("credentialRef", "Credential",
                                NodePropertyType.SECRET_REFERENCE, "", ""),
                        new NodePropertyDescriptor("mode", "Mode", NodePropertyType.STRING, false, "", "fast",
                                List.of("fast", "thorough"))),
                        Set.of("test-only"));
            }

            @Override
            public NodeHandler create(GraphNode node) {
                return message -> java.util.concurrent.CompletableFuture.completedFuture(
                        ai.ravenroot.api.execution.NodeResult.continueWith(message.payload()));
            }
        });
    }
}
