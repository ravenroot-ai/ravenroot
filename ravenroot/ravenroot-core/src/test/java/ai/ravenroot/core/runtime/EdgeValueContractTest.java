package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.graph.GraphManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What flows along an edge between two <em>registered</em> behaviors.
 *
 * <p>The gap this closes is an absence, not a wrong assertion: <strong>no test anywhere ran two
 * registered built-ins wired by a real edge and asserted the value that crossed it.</strong> The
 * nearest existing test chains {@code template} and {@code cel-transform} by hand, feeding one
 * result into the next call outside any graph, which cannot observe what the runner actually
 * delivers. That is how it stayed true for this long that two nodes could pass a value but not a
 * value one of them could address a field of.</p>
 *
 * <p>These graphs therefore use the real {@link BehaviorRegistry#standard} catalog and real edges,
 * and assert the composed result rather than each node in isolation.</p>
 */
class EdgeValueContractTest {

    private final JoinTestEngine engine = new JoinTestEngine();

    private static String graph(String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
                  <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
                  <key id="expression" for="node" attr.name="expression" attr.type="string"/>
                  <key id="template" for="node" attr.name="template" attr.type="string"/>
                  <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
                  <graph id="edge-contract" edgedefault="directed">
                    <node id="error"><data key="kind">ERROR</data></node>
                """ + body + """
                  </graph>
                </graphml>
                """;
    }

    private Object run(String graphMl, Object payload) throws Exception {
        try (var manager = GraphManager.readGraphMl(
                new ByteArrayInputStream(graphMl.getBytes(StandardCharsets.UTF_8)))) {
            var runner = new GraphRunner(manager, engine,
                    BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()),
                    new ExecutionMonitor(), ExecutionIdentitySource.randomUuids());
            var security = new SecurityContext("req-" + UUID.randomUUID(), "tenant-334", "caller",
                    PrincipalType.USER, "urn:ravenroot:test");
            return runner.execute(security, payload).toCompletableFuture()
                    .get(20, TimeUnit.SECONDS).payload();
        }
    }

    /**
     * Three nodes pass a value and produce a predictable result.
     *
     * <p>Deliberately composed rather than trivial. {@code cel-transform} builds a <em>map</em> and
     * {@code template} addresses one of its fields, exercising structured production and field access together. A
     * three-node graph passing a bare string would prove much less -- the value has to be one the
     * middle node had to look inside.</p>
     */
    @Test
    @Timeout(30)
    void threeNodesComposeAValueTheLastOneAddressesAFieldOf() throws Exception {
        Object result = run(graph("""
                    <node id="start"><data key="kind">START</data></node>
                    <node id="build"><data key="kind">BEHAVIOR</data><data key="behavior">cel-transform</data>
                      <data key="expression">{"name": payload, "count": 2}</data></node>
                    <node id="render"><data key="kind">BEHAVIOR</data><data key="behavior">template</data>
                      <data key="template">Hello {{payload.name}}, you have {{payload.count}}</data></node>
                    <node id="end"><data key="kind">END</data></node>
                    <edge id="e1" source="start" target="build"><data key="outcome">continue</data></edge>
                    <edge id="e2" source="build" target="render"><data key="outcome">continue</data></edge>
                    <edge id="e3" source="render" target="end"><data key="outcome">continue</data></edge>
                """), "Ravenroot");

        assertEquals("Hello Ravenroot, you have 2", result,
                "a map built by cel-transform must be addressable, field by field, by the template "
                        + "downstream of it -- that is what composing a result means");
    }

    /** A list is addressable by index, at depth, mixed with keys. */
    @Test
    @Timeout(30)
    void aTemplateAddressesListElementsByIndex() throws Exception {
        Object result = run(graph("""
                    <node id="start"><data key="kind">START</data></node>
                    <node id="build"><data key="kind">BEHAVIOR</data><data key="behavior">cel-transform</data>
                      <data key="expression">{"items": [payload, "second"]}</data></node>
                    <node id="render"><data key="kind">BEHAVIOR</data><data key="behavior">template</data>
                      <data key="template">first={{payload.items.0}} second={{payload.items.1}}</data></node>
                    <node id="end"><data key="kind">END</data></node>
                    <edge id="e1" source="start" target="build"><data key="outcome">continue</data></edge>
                    <edge id="e2" source="build" target="render"><data key="outcome">continue</data></edge>
                    <edge id="e3" source="render" target="end"><data key="outcome">continue</data></edge>
                """), "one");

        assertEquals("first=one second=second", result);
    }

    /**
     * {@code toString()} is the documented fallback for a value with no addressable structure, and
     * only that. A bare token over a scalar is the case it legitimately serves.
     */
    @Test
    @Timeout(30)
    void theBareTokenStillRendersAScalarThroughTheDocumentedFallback() throws Exception {
        Object result = run(graph("""
                    <node id="start"><data key="kind">START</data></node>
                    <node id="render"><data key="kind">BEHAVIOR</data><data key="behavior">template</data>
                      <data key="template">value is {{payload}}</data></node>
                    <node id="end"><data key="kind">END</data></node>
                    <edge id="e1" source="start" target="render"><data key="outcome">continue</data></edge>
                    <edge id="e2" source="render" target="end"><data key="outcome">continue</data></edge>
                """), "plain");

        assertEquals("value is plain", result);
    }

    /**
     * Absence is explicit. A field the value does not have fails the run and names the node, the
     * token and the missing entry -- it does not render a blank, and it does not leave the token in
     * the output. Both of those are silent, and a template that quietly emits "Hello " where the
     * author wrote "Hello {{payload.name}}" is this project's own defect one layer down.
     */
    @Test
    @Timeout(30)
    void addressingAFieldTheValueDoesNotHaveFailsAndSaysWhich() {
        var failure = assertThrows(ExecutionException.class, () -> run(graph("""
                    <node id="start"><data key="kind">START</data></node>
                    <node id="build"><data key="kind">BEHAVIOR</data><data key="behavior">cel-transform</data>
                      <data key="expression">{"name": payload}</data></node>
                    <node id="render"><data key="kind">BEHAVIOR</data><data key="behavior">template</data>
                      <data key="template">Hello {{payload.absent}}</data></node>
                    <node id="end"><data key="kind">END</data></node>
                    <edge id="e1" source="start" target="build"><data key="outcome">continue</data></edge>
                    <edge id="e2" source="build" target="render"><data key="outcome">continue</data></edge>
                    <edge id="e3" source="render" target="end"><data key="outcome">continue</data></edge>
                """), "Ravenroot"));

        String message = rootCause(failure).getMessage();
        assertTrue(message.contains("render"), () -> "must name the node: " + message);
        assertTrue(message.contains("absent"), () -> "must name the missing entry: " + message);
    }

    /** Addressing a field of something that has no fields says so, rather than rendering a blank. */
    @Test
    @Timeout(30)
    void addressingAFieldOfAScalarSaysItHasNoFields() {
        var failure = assertThrows(ExecutionException.class, () -> run(graph("""
                    <node id="start"><data key="kind">START</data></node>
                    <node id="render"><data key="kind">BEHAVIOR</data><data key="behavior">template</data>
                      <data key="template">Hello {{payload.name}}</data></node>
                    <node id="end"><data key="kind">END</data></node>
                    <edge id="e1" source="start" target="render"><data key="outcome">continue</data></edge>
                    <edge id="e2" source="render" target="end"><data key="outcome">continue</data></edge>
                """), "a plain string"));

        String message = rootCause(failure).getMessage();
        assertTrue(message.contains("no fields"), () -> message);
    }

    /**
     * The first of the three distinct absent-value failures: a template over no value at all.
     *
     * <p>Previously this rendered the four-character string {@code "null"} into the result, which
     * reads as data and is not.</p>
     */
    @Test
    @Timeout(30)
    void aTemplateOverNoValueFailsInsteadOfRenderingTheWordNull() {
        var failure = assertThrows(ExecutionException.class, () -> run(graph("""
                    <node id="start"><data key="kind">START</data></node>
                    <node id="render"><data key="kind">BEHAVIOR</data><data key="behavior">template</data>
                      <data key="template">Hello {{payload}}</data></node>
                    <node id="end"><data key="kind">END</data></node>
                    <edge id="e1" source="start" target="render"><data key="outcome">continue</data></edge>
                    <edge id="e2" source="render" target="end"><data key="outcome">continue</data></edge>
                """), null));

        String message = rootCause(failure).getMessage();
        assertTrue(message.contains("no value arrived"), () -> message);
        assertTrue(!message.contains("Hello null"), () -> message);
    }

    /**
     * The second: a CEL node over no value. This used to be a {@link NullPointerException} thrown by
     * {@code Map.of} before the script ran, escaping the handler's own catch, which only covers
     * script failures.
     */
    @Test
    @Timeout(30)
    void aCelTransformOverNoValueRefusesExplicitlyRatherThanThrowingFromAMapFactory() {
        var failure = assertThrows(ExecutionException.class, () -> run(graph("""
                    <node id="start"><data key="kind">START</data></node>
                    <node id="build"><data key="kind">BEHAVIOR</data><data key="behavior">cel-transform</data>
                      <data key="expression">{"name": payload}</data></node>
                    <node id="end"><data key="kind">END</data></node>
                    <edge id="e1" source="start" target="build"><data key="outcome">continue</data></edge>
                    <edge id="e2" source="build" target="end"><data key="outcome">continue</data></edge>
                """), null));

        Throwable cause = rootCause(failure);
        assertInstanceOf(IllegalArgumentException.class, cause,
                () -> "must not be the NullPointerException Map.of used to throw: " + cause);
        assertTrue(cause.getMessage().contains("no value on its incoming edge"), cause::getMessage);
        assertTrue(cause.getMessage().contains("build"), () -> "must name the node: " + cause.getMessage());
    }

    private static Throwable rootCause(Throwable thrown) {
        Throwable current = thrown;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
