package ai.ravenroot.core.runtime;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.payload.PayloadException;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code json-parse}: the decisions this node had to make, each pinned by the case that would
 * be indistinguishable if it had been made the other way.
 *
 * <p>The end-to-end proof — an editor-shaped graph submitted over the HTTP route the UI actually uses
 * — is {@code JsonPayloadDecisionHttpTest} in {@code ravenroot-server}. These are the unit-level
 * statements that class cannot make cheaply: the classification of each refusal, and the depth case,
 * which needs a 100 000-bracket document.</p>
 */
class JsonParseNodeBehaviorTest {
    private static final UUID EXECUTION_ID = UUID.randomUUID();

    /**
     * The behavior-level gap: text in, addressable structure out, and a
     * {@code cel-decision} downstream that can read a field of it.
     */
    @Test
    void makesAFieldOfATextualJsonPayloadReadableByACelDecision() throws Exception {
        var registry = BehaviorRegistry.standard();

        NodeResult parsed = invoke(registry, node("json-parse", Map.of()), "{\"status\":\"OK\",\"n\":3}");
        NodeResult decided = invoke(registry, node("cel-decision", Map.of(
                "expression", "payload.status == 'OK'", "trueOutcome", "ok", "falseOutcome", "ko")),
                parsed.payload());

        assertEquals(Map.of("status", "OK", "n", 3L), parsed.payload(),
                "the parsed value must be the interior map shape the engine already carries");
        assertEquals("ok", decided.outcome(),
                "payload.<field> must be evaluable, which is the whole point of the node");
    }

    /**
     * An integral literal stays a 64-bit integer and is not widened to a double.
     *
     * <p>Asserted through CEL rather than only on the Java value, because the widening would be
     * invisible in a {@code toString} comparison and very visible in an equality expression an author
     * writes.</p>
     */
    @Test
    void keepsAnIntegralNumberIntegral() throws Exception {
        var registry = BehaviorRegistry.standard();

        NodeResult parsed = invoke(registry, node("json-parse", Map.of()), "{\"n\":3,\"ratio\":0.5}");

        assertEquals(3L, ((Map<?, ?>) parsed.payload()).get("n"));
        assertEquals(0.5d, ((Map<?, ?>) parsed.payload()).get("ratio"));
        assertEquals("ok", invoke(registry, node("cel-decision", Map.of(
                "expression", "payload.n == 3", "trueOutcome", "ok", "falseOutcome", "ko")),
                parsed.payload()).outcome());
    }

    /** A JSON document is any value, not only an object — the {@code http-request} body case. */
    @Test
    void acceptsATopLevelArrayAndATopLevelScalar() throws Exception {
        var registry = BehaviorRegistry.standard();

        assertEquals(List.of("a", "b"), invoke(registry, node("json-parse", Map.of()), "[\"a\",\"b\"]").payload());
        assertEquals(7L, invoke(registry, node("json-parse", Map.of()), "7").payload());
    }

    /**
     * {@code source} says where to look, so JSON formed <em>inside</em> the graph is reachable without
     * a {@code cel-transform} in between.
     */
    @Test
    void readsTheDocumentFromWhereTheSourceTemplatePointsRatherThanTheWholePayload() throws Exception {
        NodeResult parsed = invoke(BehaviorRegistry.standard(),
                node("json-parse", Map.of("source", "{{payload.body}}")),
                Map.of("status", 200, "body", "{\"status\":\"OK\"}"));

        assertEquals(Map.of("status", "OK"), parsed.payload());
    }

    /**
     * The substitution runs over the document, so an attribute can reach inside it. Pinned rather
     * than left implicit: the behaviour is inherited from {@code NodeProperties.render}, which
     * {@code template}, {@code log} and {@code http-request}'s body all share, and a later reader
     * finding this surprising should find it decided rather than accidental.
     *
     * <p>The three cases are one test because their difference is the point: only the token that
     * resolves is silent. An unresolved one leaves the document intact, and a value carrying a double
     * quote breaks well-formedness and fails the node.</p>
     */
    @Test
    void substitutesAttributeTokensInsideTheDocumentText() throws Exception {
        var registry = BehaviorRegistry.standard();
        String document = "{\"status\":\"{{attributes.status}}\"}";

        assertEquals(Map.of("status", "KO"),
                invoke(registry, node("json-parse", Map.of()), document, Map.of("status", "KO")).payload(),
                "an attribute in scope decides the field the graph branches on");
        assertEquals(Map.of("status", "{{attributes.status}}"),
                invoke(registry, node("json-parse", Map.of()), document, Map.of()).payload(),
                "with nothing to resolve to, the token survives and the document is untouched");
        assertEquals(PayloadException.Reason.MALFORMED,
                assertInstanceOf(PayloadException.class, assertThrows(ExecutionException.class,
                        () -> invoke(registry, node("json-parse", Map.of()), document,
                                Map.of("status", "a\"b"))).getCause()).reason(),
                "a substituted value carrying a quote breaks the document loudly, not silently");
    }

    /**
     * Text that is not JSON fails the node. It does not pass the payload through unchanged: a graph
     * whose decision node then compares a string against a field would take the false branch and
     * report success having decided on nothing.
     */
    @Test
    void refusesTextThatIsNotJsonInsteadOfPassingItThrough() {
        PayloadException rejection = refusal("not json at all");

        assertEquals(PayloadException.Reason.MALFORMED, rejection.reason());
        assertEquals("PAYLOAD_MALFORMED", rejection.code());
        assertTrue(rejection.getMessage().contains("well-formed"), rejection.getMessage());
    }

    /**
     * A hostile document must be refused in a way that can be
     * stated, not by exhausting the stack.
     *
     * <p>100 000 opening brackets, which is 100 000 levels of nesting against a 32-level budget. The
     * parser checks the depth on entry to each value, so it never descends past 33 frames and the
     * refusal is classified. Removing that check makes this test fail with {@code StackOverflowError}
     * — an {@code Error}, so it is not even caught by the handler and does not become a failed
     * future at all.</p>
     */
    @Test
    void refusesDeepNestingWithAStatedLimitRatherThanAStackOverflow() {
        PayloadException rejection = refusal("[".repeat(100_000));

        assertEquals(PayloadException.Reason.DEPTH_LIMIT_EXCEEDED, rejection.reason());
        assertTrue(rejection.getMessage().contains("32"),
                () -> "the refusal must name the budget the document exceeded: " + rejection.getMessage());
    }

    /**
     * The encoded-size budget, which is the one the interior budgets do not imply.
     *
     * <p>A thousand strings of one kilobyte each satisfies every per-element budget — 1001 values
     * against 10 000, one collection of 1000 against 1000, each text 1 KiB against 32 KiB — and still
     * encodes to roughly a megabyte. It is refused only because the size is checked before the parse,
     * which is what the {@code byte[]} overload does and the {@code String} one does not.</p>
     */
    @Test
    void refusesADocumentOverTheEncodedSizeBudgetThatEveryInteriorBudgetWouldHaveAdmitted() {
        String oneKibElement = "\"" + "a".repeat(1024) + "\"";
        String document = "[" + String.join(",", java.util.Collections.nCopies(1000, oneKibElement)) + "]";
        assertTrue(document.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 256 * 1024, document.length()
                + " bytes: the fixture must actually exceed the 256 KiB budget or it proves nothing");

        PayloadException rejection = refusal(document);

        assertEquals(PayloadException.Reason.TOO_LARGE, rejection.reason());
        assertTrue(rejection.getMessage().contains(Integer.toString(256 * 1024)), rejection.getMessage());
    }

    /** The per-value text budget, which bites below the document budget. */
    @Test
    void refusesASingleTextValueOverTheTextBudget() {
        PayloadException rejection = refusal("{\"note\":\"" + "a".repeat(40 * 1024) + "\"}");

        assertEquals(PayloadException.Reason.TEXT_TOO_LONG, rejection.reason());
    }

    /**
     * The ingress reserved-key walk cannot see inside text, so this node is where such a key would
     * first exist — and where it is refused.
     */
    @Test
    void refusesAReservedSecurityKeyThatWasHiddenInsideTheText() {
        PayloadException rejection = refusal("{\"ravenroot.security.tenantId\":\"tenant-b\"}");

        assertEquals(PayloadException.Reason.RESERVED_KEY, rejection.reason());
    }

    /** The catalog entry the editor reads, so a graph author can place the node at all. */
    @Test
    void isPublishedInTheCoreCatalogWithItsOneProperty() {
        var descriptor = BehaviorRegistry.standard().descriptors().stream()
                .filter(type -> type.behavior().equals("json-parse")).findFirst().orElseThrow();

        assertEquals("Transformations", descriptor.category());
        assertEquals(List.of("source"), descriptor.properties().stream()
                .map(ai.ravenroot.api.catalog.NodePropertyDescriptor::name).toList());
        assertEquals("{{payload}}", descriptor.properties().get(0).defaultValue());
        assertTrue(descriptor.properties().stream().noneMatch(
                ai.ravenroot.api.catalog.NodePropertyDescriptor::required),
                "the default source is the whole payload, so nothing has to be configured to use it");
    }

    private static PayloadException refusal(String text) {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> invoke(BehaviorRegistry.standard(), node("json-parse", Map.of()), text));
        return assertInstanceOf(PayloadException.class, failure.getCause());
    }

    private static GraphNode node(String behavior, Map<String, Object> properties) {
        return new GraphNode(behavior + "-node", NodeKind.BEHAVIOR, behavior, properties);
    }

    private static NodeResult invoke(BehaviorRegistry registry, GraphNode node, Object payload) throws Exception {
        return invoke(registry, node, payload, Map.of());
    }

    private static NodeResult invoke(BehaviorRegistry registry, GraphNode node, Object payload,
                                     Map<String, Object> attributes) throws Exception {
        return registry.create(node).orElseThrow()
                .handle(new NodeMessage(TestIdentities.TENANT_A, EXECUTION_ID, UUID.randomUUID(), node.id(),
                        payload, attributes))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
}
