package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.persistence.HumanTaskPolicy;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HumanTaskPolicyCatalogTest {
    @Test
    void catalogProjectsCustomDefaultsAndEveryAuthoringBound() {
        HumanTaskPolicy d = HumanTaskPolicy.DEFAULTS;
        var policy = new HumanTaskPolicy(300_000, 600_000, 17, 5_183_999,
                5_184_000, 6_000_000, 512, 8192, 128, 37, 513,
                700_000, 125, 250, 77, 2000, 8000, 32768, 512, 5);
        var properties = new HumanTaskNodeBehaviorFactory(null, policy).descriptor().properties()
                .stream().collect(Collectors.toMap(NodePropertyDescriptor::name, Function.identity()));
        assertEquals("300000", properties.get("maxResponseBytes").defaultValue());
        assertEquals("600000", properties.get("maxResponseBytes").maximumValue());
        assertEquals("5184000", properties.get("expiresAfterSeconds").defaultValue());
        assertEquals("6000000", properties.get("expiresAfterSeconds").maximumValue());
        assertEquals(512, properties.get("title").maximumUtf8Bytes());
        assertEquals(8192, properties.get("description").maximumUtf8Bytes());
        assertEquals(128, properties.get("responseSchema").maximumUtf8Bytes());
        assertEquals(128, properties.get("responseSchemaVersion").maximumUtf8Bytes());
        assertEquals(37, properties.get("authorizedRoles").maximumItems());
        assertEquals(513, properties.get("authorizedScopes").maximumItemUtf8Bytes());
        assertEquals(d.defaultResponseBytes() < policy.defaultResponseBytes(), true,
                "the test must prove a configured default above the former 64 KiB value");
    }

    @Test
    void payloadSchemaAndVersionWireGrammarAreEnforcedAtGraphAdmission() {
        var registry = BehaviorRegistry.standard();
        for (String value : List.of("schema with spaces", "schema!", "s".repeat(129))) {
            var invalidSchema = new GraphNode("review", NodeKind.BEHAVIOR, "human-task",
                    Map.of("title", "Review", "responseSchema", value));
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> registry.create(invalidSchema));
            assertEquals(true, failure.getMessage().contains("responseSchema"));
        }
        for (String value : List.of("version with spaces", "version!", "v".repeat(129))) {
            var invalidVersion = new GraphNode("review", NodeKind.BEHAVIOR, "human-task",
                    Map.of("title", "Review", "responseSchema", "response",
                            "responseSchemaVersion", value));
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> registry.create(invalidVersion));
            assertEquals(true, failure.getMessage().contains("responseSchemaVersion"));
        }

        var boundary = new GraphNode("review", NodeKind.BEHAVIOR, "human-task",
                Map.of("title", "Review", "responseSchema", "s".repeat(128),
                        "responseSchemaVersion", "v".repeat(128)));
        assertEquals(true, registry.create(boundary).isPresent());
    }
}
