package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.persistence.HumanTaskPolicy;
import ai.ravenroot.api.payload.PayloadKind;
import ai.ravenroot.api.persistence.HumanTaskConfirmationAction;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        var unavailable = assertThrows(IllegalStateException.class, () -> registry.create(boundary));
        assertEquals(true, unavailable.getMessage().contains("durable human-task"));
    }

    @Test
    void uiShapedVersionOneBridgesOnlyTheCompleteClassicPlaceholderTuple() {
        var factory = new HumanTaskNodeBehaviorFactory(null, HumanTaskPolicy.DEFAULTS);
        var uiNode = new GraphNode("review", NodeKind.BEHAVIOR, "human-task", Map.ofEntries(
                Map.entry("title", "Review"),
                Map.entry("responseContentType", "application/vnd.ravenroot.payload+json"),
                Map.entry("responseSchema", "ravenroot.human-task.response"),
                Map.entry("responseSchemaVersion", "1"),
                Map.entry("responseKind", "MAP"),
                Map.entry("maxResponseBytes", Integer.toString(HumanTaskPolicy.DEFAULTS.defaultResponseBytes())),
                Map.entry("confirmationPresentationVersion", "1"),
                Map.entry("confirmationPrompt", "Publish the release?"),
                Map.entry("confirmationComment", "REQUIRED"),
                Map.entry("confirmationActions", "CANCEL,RESOLVE,DENY"),
                Map.entry("confirmationResolveLabel", "Publish"),
                Map.entry("confirmationDenyLabel", "Reject"),
                Map.entry("confirmationCancelLabel", "Later")));
        var definition = factory.definition(uiNode);
        assertEquals("application/json", definition.responseSchema().contentType());
        assertEquals("ravenroot.human-task.confirmation", definition.responseSchema().schema());
        assertEquals(PayloadKind.SCALAR, definition.responseSchema().kind());
        assertEquals(List.of(HumanTaskConfirmationAction.CANCEL,
                HumanTaskConfirmationAction.RESOLVE, HumanTaskConfirmationAction.DENY),
                definition.confirmationPresentation().actions());
        assertEquals("Publish the release?", definition.confirmationPresentation().prompt());

        for (Map.Entry<String, String> mixed : Map.of(
                "responseContentType", "application/problem+json",
                "responseSchema", "custom.response",
                "responseSchemaVersion", "2",
                "responseKind", "LIST",
                "maxResponseBytes", "1024").entrySet()) {
            var values = new java.util.HashMap<>(uiNode.properties());
            values.put(mixed.getKey(), mixed.getValue());
            assertThrows(IllegalArgumentException.class, () -> factory.definition(
                    new GraphNode("review", NodeKind.BEHAVIOR, "human-task", values)), mixed.getKey());
        }
    }

    @Test
    void explicitBuiltinTupleIsAcceptedAndPartialPresentationIsRefusedBeforeTraversal() {
        var factory = new HumanTaskNodeBehaviorFactory(null, HumanTaskPolicy.DEFAULTS);
        var incapable = factory.descriptor();
        assertTrue(incapable.capabilities().stream().noneMatch("embedded-confirmation-v1"::equals));
        assertTrue(incapable.properties().stream()
                .noneMatch(property -> property.name().startsWith("confirmation")),
                "an incapable catalog must not offer v1 authoring controls");
        var explicit = new GraphNode("review", NodeKind.BEHAVIOR, "human-task", Map.of(
                "title", "Review", "responseContentType", "application/json",
                "responseSchema", "ravenroot.human-task.confirmation",
                "responseSchemaVersion", "1", "responseKind", "SCALAR",
                "maxResponseBytes", Integer.toString(HumanTaskPolicy.DEFAULTS.defaultResponseBytes()),
                "confirmationPresentationVersion", "1"));
        assertTrue(factory.definition(explicit).confirmationPresentation().embedded());

        var duplicate = new java.util.HashMap<>(explicit.properties());
        duplicate.put("confirmationActions", "RESOLVE,DENY,RESOLVE");
        assertThrows(IllegalArgumentException.class, () -> factory.definition(
                new GraphNode("review", NodeKind.BEHAVIOR, "human-task", duplicate)));
        var unsupported = new java.util.HashMap<>(explicit.properties());
        unsupported.put("confirmationPresentationVersion", "2");
        assertThrows(IllegalArgumentException.class, () -> factory.definition(
                new GraphNode("review", NodeKind.BEHAVIOR, "human-task", unsupported)));
        assertThrows(IllegalStateException.class, () -> factory.create(explicit),
                "missing durable confirmation capability must fail while materializing the graph");
    }
}
