package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.payload.PayloadException;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonPathNodeBehaviorFactoryTest {
    private static final SecurityContext IDENTITY = new SecurityContext(
            "json-path-test", "tenant-a", "tester", PrincipalType.USER, "urn:ravenroot:test");

    @Test
    void publishesAndRegistersTheSingleScalarRfc9535Contract() {
        var descriptor = new JsonPathNodeBehaviorFactory().descriptor();

        assertEquals("json-path", descriptor.behavior());
        assertEquals("Transformations", descriptor.category());
        assertEquals(List.of("path"), descriptor.properties().stream().map(property -> property.name()).toList());
        assertTrue(descriptor.properties().getFirst().required());
        assertTrue(descriptor.capabilities().contains("rfc-9535"));
        assertEquals(java.util.Set.of("continue"), descriptor.resolveOutcomes(ignored -> null));
        assertTrue(BehaviorRegistry.standard().descriptor("json-path").isPresent());
    }

    @Test
    void selectsDefiniteIndefiniteWildcardAndNoMatchAsOrderedArrays() throws Exception {
        Object document = Map.of("store", Map.of("books", List.of(
                Map.of("title", "A", "price", 8),
                Map.of("title", "B", "price", 12),
                Map.of("title", "C", "price", 5))));

        assertEquals(List.of("A"), invoke("$.store.books[0].title", document).payload());
        assertEquals(List.of("A", "B", "C"), invoke("$.store.books[*].title", document).payload());
        assertEquals(List.of("A", "C"), invoke("$.store.books[?@.price < 10].title", document).payload());
        assertEquals(List.of("A", "B", "C"),
                invoke("$.store.books[?length(@.title) == 1].title", document).payload());
        assertEquals(List.of(), invoke("$.store.magazines[*]", document).payload());
    }

    @Test
    void acceptsBoundedJsonTextAndUsesRfcSelectors() throws Exception {
        String json = "{\"groups\":[{\"names\":[\"Ada\",\"Lin\"]},{\"names\":[\"Kai\"]}]}";

        assertEquals(List.of("Ada", "Lin", "Kai"), invoke("$..names[*]", json).payload());
        assertEquals(List.of("Ada", "Lin"), invoke("$.groups[0].names[0:2]", json).payload());
    }

    @Test
    void objectTraversalIsDeterministicRatherThanMapIterationDependent() throws Exception {
        var unordered = new HashMap<String, Object>();
        unordered.put("z", "last");
        unordered.put("a", "first");

        assertEquals(List.of("first", "last"), invoke("$.*", unordered).payload());
    }

    @Test
    void malformedDeepOversizedAndUnsupportedInputsFailWithTypedSanitizedErrors() {
        PayloadException malformed = assertFailure("$", "{not-json", PayloadException.class);
        assertEquals(PayloadException.Reason.MALFORMED, malformed.reason());

        String deep = "[".repeat(33) + "0" + "]".repeat(33);
        PayloadException tooDeep = assertFailure("$", deep, PayloadException.class);
        assertEquals(PayloadException.Reason.DEPTH_LIMIT_EXCEEDED, tooDeep.reason());

        PayloadException tooLarge = assertFailure("$", "\"" + "x".repeat(300_000) + "\"",
                PayloadException.class);
        assertEquals(PayloadException.Reason.TOO_LARGE, tooLarge.reason());

        PayloadException unsupported = assertFailure("$", new Object(), PayloadException.class);
        assertEquals(PayloadException.Reason.UNSUPPORTED_TYPE, unsupported.reason());

        String outputAmplifier = "$[" + String.join(",", java.util.Collections.nCopies(1_001, "0")) + "]";
        JsonPathNodeException oversizedOutput = assertFailure(outputAmplifier, List.of("duplicate"),
                JsonPathNodeException.class);
        assertEquals(JsonPathNodeException.Reason.RESOURCE_LIMIT, oversizedOutput.reason());
    }

    @Test
    void rejectsEvaluatorExtensionsAndDoesNotEchoTheAuthoredPath() {
        String hostile = "$.secret.first()";
        JsonPathNodeException failure = assertFailure(hostile, Map.of("secret", List.of("value")),
                JsonPathNodeException.class);

        assertEquals(JsonPathNodeException.Reason.INVALID_PATH, failure.reason());
        assertTrue(!failure.getMessage().contains(hostile));
        assertFailure("$.items[?@.name =~ /secret/]", Map.of("items", List.of()), JsonPathNodeException.class);
        assertFailure("$.items[?@.kind in ['a']]", Map.of("items", List.of()), JsonPathNodeException.class);
    }

    @Test
    void validatesThePositiveRfcGrammarAndTypesWithoutConsultingInput() throws Exception {
        assertEquals(List.of(11L), invoke("$ [0]", List.of(11)).payload());
        assertEquals(List.of("second", "first"), invoke("$[::-1]", List.of("first", "second")).payload());
        assertEquals(List.of("quoted"), invoke("$['a\\u002Fb']", Map.of("a/b", "quoted")).payload());

        Object functions = List.of(Map.of("name", "Ada", "tags", List.of("a", "b")),
                Map.of("name", "Lin", "tags", List.of("a")));
        assertEquals(List.of(Map.of("name", "Ada", "tags", List.of("a", "b"))),
                invoke("$[?count(@.tags[*]) == 2 && match(@.name, 'A..')]", functions).payload());
        assertEquals(List.of(Map.of("name", "Lin", "tags", List.of("a"))),
                invoke("$[?search(@.name, 'in') && value(@.name) == 'Lin']", functions).payload());

        List<String> invalid = List.of(
                " $", "$ ", "$[01]", "$[-0]", "$[9007199254740992]", "$['\\q']", "$['\\uD800']",
                "$['raw\nline']", "$[?length(@.*) == 1]", "$[?length(@)]",
                "$[?value(@)]", "$[?count(1) == 1]", "$[?match(@.x)]", "$[?unknown(@)]");
        for (String path : invalid) {
            JsonPathNodeException failure = assertFailure(path, Map.of("x", "anything"),
                    JsonPathNodeException.class);
            assertEquals(JsonPathNodeException.Reason.INVALID_PATH, failure.reason(), path);
            assertTrue(!failure.getMessage().contains(path));
        }
    }

    @Test
    void amplificationStopsAtTheBoundedSinkAndSnack4IsNotCoLoaded() {
        var wide = java.util.stream.IntStream.range(0, 1_000).boxed().toList();
        Object nested = Map.of("left", wide, "right", wide);

        JsonPathNodeException failure = assertFailure("$..*", nested, JsonPathNodeException.class);
        assertEquals(JsonPathNodeException.Reason.RESOURCE_LIMIT, failure.reason());

        String expensiveFilter = "$[?" + String.join("&&",
                java.util.Collections.nCopies(200, "@==@")) + "]";
        JsonPathNodeException workLimit = assertFailure(expensiveFilter, wide, JsonPathNodeException.class);
        assertEquals(JsonPathNodeException.Reason.RESOURCE_LIMIT, workLimit.reason());

        List<String> largeValue = java.util.Collections.nCopies(8, "x".repeat(30_000));
        JsonPathNodeException byteAmplification = assertFailure("$[0,0]",
                List.of(largeValue), JsonPathNodeException.class);
        assertEquals(JsonPathNodeException.Reason.RESOURCE_LIMIT, byteAmplification.reason());

        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("org.noear.snack4.jsonpath.OperatorLib"));
    }

    @Test
    void matchAndSearchCheckIRegexpAndUseUnicodeSafeLinearMatching() throws Exception {
        assertEquals(List.of("Ravenroot"),
                invoke("$[?match(@, '\\\\p{L}+')]", List.of("Ravenroot", "123")).payload());
        assertEquals(List.of("line one"),
                invoke("$[?search(@, 'one')]", List.of("line one", "two")).payload());
        assertEquals(List.of(), invoke("$[?match(@, '.')]", List.of("\n")).payload());
        assertEquals(List.of(), invoke("$[?match(@, '\\\\d+')]", List.of("123")).payload());
        assertEquals(List.of(), invoke("$[?match(@, '[^]')]", List.of("x")).payload());
    }

    @Test
    void expressionConstructionAndEvaluationStopAtTheirExactBoundaries() throws Exception {
        String nestedFunctionsAtLimit = "$[?" + "length(".repeat(62) + "@.name" + ")".repeat(62)
                + " == 1]";
        assertEquals(List.of(), invoke(nestedFunctionsAtLimit, List.of(Map.of("name", "x"))).payload());
        JsonPathNodeException nestedFunctionOverflow = assertFailure(
                "$[?" + "length(".repeat(63) + "@.name" + ")".repeat(63) + " == 1]",
                List.of(Map.of("name", "x")), JsonPathNodeException.class);
        assertEquals(JsonPathNodeException.Reason.RESOURCE_LIMIT, nestedFunctionOverflow.reason());

        String logicalAtLimit = "$[?" + String.join("&&", java.util.Collections.nCopies(62, "@")) + "]";
        assertEquals(List.of(1L), invoke(logicalAtLimit, List.of(1)).payload());
        JsonPathNodeException logicalOverflow = assertFailure(
                "$[?" + String.join("&&", java.util.Collections.nCopies(63, "@")) + "]",
                List.of(1), JsonPathNodeException.class);
        assertEquals(JsonPathNodeException.Reason.RESOURCE_LIMIT, logicalOverflow.reason());

        String nodeBudgetAtLimit = "$" + "[?@]".repeat(128);
        assertEquals(List.of(), invoke(nodeBudgetAtLimit, List.of(1)).payload());
        JsonPathNodeException nodeOverflow = assertFailure("$" + "[?@]".repeat(129), List.of(1),
                JsonPathNodeException.class);
        assertEquals(JsonPathNodeException.Reason.RESOURCE_LIMIT, nodeOverflow.reason());

        var oneThousand = IntStream.range(0, 1_000).boxed().toList();
        assertEquals(List.of(), invoke("$[?!(@&&@)]", oneThousand).payload());
        JsonPathNodeException evaluationOverflow = assertFailure("$[?!(!(@&&@))]", oneThousand,
                JsonPathNodeException.class);
        assertEquals(JsonPathNodeException.Reason.RESOURCE_LIMIT, evaluationOverflow.reason());
    }

    @Test
    void iRegexpDepthIsBoundedForLiteralAndDocumentProvidedPatterns() throws Exception {
        String patternAtLimit = "(".repeat(63) + "a" + ")".repeat(63);
        String patternOverflow = "(".repeat(64) + "a" + ")".repeat(64);

        assertEquals(List.of("a"), invoke("$[?match(@, '" + patternAtLimit + "')]", List.of("a")).payload());
        JsonPathNodeException literalOverflow = assertFailure("$[?match(@, '" + patternOverflow + "')]",
                List.of("a"), JsonPathNodeException.class);
        assertEquals(JsonPathNodeException.Reason.RESOURCE_LIMIT, literalOverflow.reason());

        Map<String, Object> accepted = Map.of("subject", "a", "pattern", patternAtLimit);
        assertEquals(List.of(accepted), invoke("$[?match(@.subject, @.pattern)]", List.of(accepted)).payload());
        Map<String, Object> refused = Map.of("subject", "a", "pattern", patternOverflow);
        JsonPathNodeException dynamicOverflow = assertFailure("$[?match(@.subject, @.pattern)]",
                List.of(refused), JsonPathNodeException.class);
        assertEquals(JsonPathNodeException.Reason.RESOURCE_LIMIT, dynamicOverflow.reason());
    }

    @Test
    void theCompiledHandlerIsSafeForConcurrentTraversals() throws Exception {
        var handler = new JsonPathNodeBehaviorFactory().create(node("$.value"));
        assertTrue(java.util.Arrays.stream(Rfc9535JsonPath.class.getDeclaredFields())
                .filter(field -> java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .allMatch(field -> java.lang.reflect.Modifier.isFinal(field.getModifiers())),
                "the evaluator exposes no mutable static registry for a co-loaded package to replace");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = IntStream.range(0, 100)
                    .mapToObj(value -> executor.submit(() -> handler.handle(message(Map.of("value", value)))
                            .toCompletableFuture().get(2, TimeUnit.SECONDS).payload()))
                    .toList();
            for (int value = 0; value < futures.size(); value++) {
                assertEquals(List.of((long) value), futures.get(value).get(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void graphMlPersistsPathAsOneOrdinaryNodeProperty() {
        String graphMl = """
                <?xml version="1.0" encoding="UTF-8"?>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
                  <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
                  <key id="path" for="node" attr.name="path" attr.type="string"/>
                  <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
                  <graph id="json-path" edgedefault="directed">
                    <node id="start"><data key="kind">START</data></node>
                    <node id="select">
                      <data key="kind">BEHAVIOR</data>
                      <data key="behavior">json-path</data>
                      <data key="path">$.items[*]</data>
                    </node>
                    <node id="end"><data key="kind">END</data></node>
                    <edge id="e1" source="start" target="select"><data key="outcome">continue</data></edge>
                    <edge id="e2" source="select" target="end"><data key="outcome">continue</data></edge>
                  </graph>
                </graphml>
                """;

        try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(
                graphMl.getBytes(StandardCharsets.UTF_8)))) {
            assertEquals("$.items[*]", manager.definition().node("select").properties().get("path"));
            assertEquals(1, manager.definition().node("select").properties().entrySet().stream()
                    .filter(entry -> entry.getKey().equals("path")).count());
        }
    }

    private static NodeResult invoke(String path, Object payload) throws Exception {
        return new JsonPathNodeBehaviorFactory().create(node(path)).handle(message(payload))
                .toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    private static <T extends Throwable> T assertFailure(String path, Object payload, Class<T> type) {
        var stage = new JsonPathNodeBehaviorFactory().create(node(path)).handle(message(payload));
        CompletionException completion = assertThrows(CompletionException.class,
                () -> stage.toCompletableFuture().join());
        return assertInstanceOf(type, completion.getCause());
    }

    private static GraphNode node(String path) {
        return new GraphNode("select", NodeKind.BEHAVIOR, "json-path", Map.of("path", path));
    }

    private static NodeMessage message(Object payload) {
        return new NodeMessage(IDENTITY, UUID.randomUUID(), UUID.randomUUID(), "select", payload, Map.of());
    }
}
