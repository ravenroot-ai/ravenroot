package ai.ravenroot.core.runtime;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.graph.ReservedGraphProperties;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * SEC-09's required property-fuzzing: arbitrary untrusted property maps against the trusted schema.
 *
 * <h2>What a fuzz test here is actually for</h2>
 * <p>Not "does it crash" — the interesting failures are quieter than that. Three invariants, each
 * chosen because violating it is plausible and would not be obvious from reading the validator:</p>
 * <ol>
 *   <li>Every rejection is the <em>declared</em> exception type. A validator that let a raw
 *   {@code NumberFormatException} or {@code URISyntaxException} escape would surface a parser
 *   internal to an HTTP caller, which is the disclosure SEC-08 spent effort closing.</li>
 *   <li>Undeclared properties never influence the outcome. The strong form: for any property map,
 *   adding arbitrary non-reserved keys to it must not change accept/reject. That is preservation
 *   stated as a property rather than as three hand-picked examples.</li>
 *   <li>Any reserved key is always refused, whatever else is present.</li>
 * </ol>
 */
class BehaviorPropertyFuzzTest {

    private static final String BEHAVIOR = "fuzz-probe";
    private static final BehaviorPropertySchema SCHEMA = new BehaviorPropertySchema(registry());

    /** Every declared property name the fixture behavior knows about. */
    private static final List<String> DECLARED =
            List.of("requiredText", "count", "ratio", "enabled", "endpoint", "credentialRef", "mode");

    @Property(tries = 500)
    void everyRejectionIsTheDeclaredTypeAndNeverALeakedParserInternal(
            @ForAll("propertyMaps") Map<String, Object> properties) {
        try {
            SCHEMA.validate(graphWith(properties));
        } catch (BehaviorPropertySchema.BehaviorPropertyException expected) {
            assertTrue(expected.getMessage().contains("Node '"), expected.getMessage());
        } catch (RuntimeException leaked) {
            fail("validation leaked " + leaked.getClass().getName() + " for " + properties + ": "
                    + leaked.getMessage());
        }
    }

    @Property(tries = 500)
    void undeclaredPropertiesNeverChangeTheOutcome(
            @ForAll("propertyMaps") Map<String, Object> declaredPart,
            @ForAll("undeclaredNoise") Map<String, Object> noise) {
        boolean baseline = accepts(declaredPart);

        var combined = new LinkedHashMap<>(declaredPart);
        combined.putAll(noise);

        assertEquals(baseline, accepts(combined),
                "adding undeclared properties " + noise.keySet() + " changed the verdict; unknown "
                        + "properties must be inert, not merely tolerated");
    }

    @Property(tries = 300)
    void anyReservedKeyIsRefusedWhateverElseIsPresent(
            @ForAll("propertyMaps") Map<String, Object> properties,
            @ForAll("reservedKeys") String reserved,
            @ForAll String value) {
        var poisoned = new LinkedHashMap<>(properties);
        poisoned.put(reserved, value);

        assertTrue(ReservedGraphProperties.isReserved(reserved), "fixture generated a non-reserved key");
        try {
            SCHEMA.validate(graphWith(poisoned));
            fail("reserved key '" + reserved + "' was accepted alongside " + properties.keySet());
        } catch (BehaviorPropertySchema.BehaviorPropertyException expected) {
            // A different declared property may fail first; either way the graph is refused, which is
            // the property under test. Asserting *which* violation is reported first would pin an
            // iteration order that carries no meaning.
            assertTrue(expected.getMessage().contains("Node '"), expected.getMessage());
        }
    }

    @Property(tries = 300)
    void aWellTypedValueIsAlwaysAcceptedRegardlessOfSurroundingNoise(
            @ForAll("undeclaredNoise") Map<String, Object> noise) {
        var valid = new LinkedHashMap<String, Object>();
        valid.put("requiredText", "present");
        valid.put("count", "1");
        valid.put("enabled", "true");
        valid.put("mode", "fast");
        valid.putAll(noise);

        assertTrue(accepts(valid), "a well-typed graph was refused because of inert properties "
                + noise.keySet());
    }

    // ------------------------------------------------------------------ generators

    @Provide
    Arbitrary<Map<String, Object>> propertyMaps() {
        Arbitrary<String> names = Arbitraries.of(DECLARED);
        Arbitrary<Object> values = Arbitraries.oneOf(
                Arbitraries.of("", "   ", "0", "-1", "12.5", "true", "false", "TRUE", "yes", "1",
                        "https://example.test/x", "/relative", "not a uri", "acme-main", " padded",
                        "with space", "fast", "thorough", "turbo", "\0control"),
                Arbitraries.strings().ofMaxLength(24).map(value -> (Object) value),
                Arbitraries.integers().map(String::valueOf).map(value -> (Object) value));
        return Arbitraries.maps(names, values).ofMaxSize(DECLARED.size());
    }

    @Provide
    Arbitrary<Map<String, Object>> undeclaredNoise() {
        // Deliberately includes names that collide with other behaviors' declared properties
        // ("provider", "prompt", "runtime") and names that look typed ("custom.count"). A validator
        // that consulted a global property table rather than the node's own behavior entry would
        // fail here.
        Arbitrary<String> names = Arbitraries.of("owner", "custom.count", "custom.flag", "provider",
                "prompt", "runtime", "viz.annotation", "note", "ravenrootish.retry", "Ravenroot",
                "tenantId", "capabilities", "toolPolicy");
        Arbitrary<Object> values = Arbitraries.oneOf(
                Arbitraries.strings().ofMaxLength(32).map(value -> (Object) value),
                Arbitraries.of("not-a-number", "", "   ", "\0", "false-ish"));
        return Arbitraries.maps(names, values).ofMaxSize(6);
    }

    @Provide
    Arbitrary<String> reservedKeys() {
        return Arbitraries.of("ravenroot.security.tenantId", "ravenroot.security.subject",
                "ravenroot.retryPolicy", "RAVENROOT.anything", "RavenRoot.Security.Subject",
                "ravenroot.", "  ravenroot.padded");
    }

    // ------------------------------------------------------------------ fixtures

    private static boolean accepts(Map<String, Object> properties) {
        try {
            SCHEMA.validate(graphWith(properties));
            return true;
        } catch (BehaviorPropertySchema.BehaviorPropertyException refused) {
            return false;
        }
    }

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
                return new NodeTypeDescriptor(BEHAVIOR, "Fuzz probe", "Test", "Fuzz fixture", "actor",
                        false, List.of(
                        NodePropertyDescriptor.required("requiredText", "Required text",
                                NodePropertyType.TEXT, ""),
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
