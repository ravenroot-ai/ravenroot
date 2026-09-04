package ai.ravenroot.server.payload;

import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.RavenrootServer;
import ai.ravenroot.server.security.DisabledLoopbackAuthenticator;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JSON payload decision contract at the only boundary that can settle it.
 *
 * <h2>Why this starts from the HTTP request and not from a {@code PayloadEnvelope}</h2>
 * <p>The engine could already navigate a payload that <em>arrives</em> as a map:
 * {@code PayloadValue.toJava()} projects a {@code MapValue} onto {@code Map<String,Object>} and CEL
 * reads it. A test that builds such an envelope by hand therefore proves nothing about the HTTP path —
 * it exercises the path that already worked. What did not work is the path the editor uses:
 * {@code ravenroot-ui/src/runtime-client.js} submits GraphML in the body with the payload as
 * {@code ?payload=<text>}, so the payload reaching the first node is a {@code String} and
 * {@code payload.status} was not writable anywhere downstream.</p>
 *
 * <h2>Why {@code run()} and not {@code start()}</h2>
 * <p>The editor has two submit buttons, Play and Run, and {@code app.js} dispatches between them with
 * {@code runtimeClient[mode === 'run' ? 'run' : 'start']}. They differ by one query parameter:
 * {@code start()} omits {@code mode}, {@code run()} sends {@code mode=run}. That parameter decides
 * whether any behaviour runs at all — {@code RavenrootServer} reads
 * {@code query(exchange).getOrDefault("mode", "test")} and maps {@code test} to
 * {@code ExecutionPolicy.TEST_PASSTHROUGH}. Submitting this graph in {@code start()}'s exact form
 * produces {@code "bypassedNodes":["decide","parse","start"]}: no
 * behaviour executed, {@code cel-decision} included. On that path the behavior cannot be shown by
 * construction, whatever this node does.</p>
 *
 * <p>So the request below is {@code run()}'s request byte for byte — same method, same path, same
 * query parameters, same {@code Content-Type} down to the {@code charset} — with the JSON document as
 * the payload text. Nothing here constructs a payload object, and nothing selects the structured
 * submission content type.</p>
 *
 * <h2>Why the graph is shaped the way it is</h2>
 * <p>{@code START → json-parse → cel-decision → log → END}, with the two branches rejoining at an
 * {@code END} carrying {@code joinPolicy=any} and an {@code ERROR} node present. That is the shape of
 * {@code ravenroot-ui/public/examples/ravenroot-programmable.graphml} — the editor's own corpus —
 * with one node substituted, so nothing in it is reachable only by writing GraphML by hand.</p>
 *
 * <h2>What is asserted, and why both directions are run</h2>
 * <p>{@code visitedNodes} is the observable form of "which edge was taken". One run would be
 * satisfied by a decision node that always takes the true branch, so the same graph is submitted
 * twice with {@code status} set to {@code OK} and to {@code KO}, and each run must visit its branch
 * and not the other. The result payload is asserted too: it comes back as a JSON object rather than
 * the submitted text, which is the structural change the decision rests on.</p>
 *
 * <p><strong>The double submission is what makes this test discriminating, and it is worth saying
 * which mutation proves that.</strong> Unregistering {@code json-parse}
 * turns this red with {@code visitedNodes:[]} — but so does leaving the node registered and making it
 * return the incoming text unchanged, so that signature cannot tell "node missing" from "node does
 * not decode". The mutation that discriminates is a node returning a <em>constant</em> map: the run
 * completes, {@code visitedNodes} is full, and what goes red is the second submission taking the
 * branch its field did not choose. A reader tempted to drop the {@code KO} case as redundant is
 * dropping the half that detects a node which parses nothing in particular.</p>
 */
class JsonPayloadDecisionHttpTest {

    /**
     * The GraphML an author would have after placing six nodes in the editor and wiring them.
     *
     * <p>{@code json-parse} declares no properties: its {@code source} defaults to {@code {{payload}}},
     * so the node is usable with nothing configured — which is what "built only from the editor"
     * has to mean for a node an author drops in without opening the inspector.</p>
     */
    private static final String DECISION_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="name" for="node" attr.name="name" attr.type="string"/>
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="expression" for="node" attr.name="expression" attr.type="string"/>
              <key id="trueOutcome" for="node" attr.name="trueOutcome" attr.type="string"/>
              <key id="falseOutcome" for="node" attr.name="falseOutcome" attr.type="string"/>
              <key id="message" for="node" attr.name="message" attr.type="string"/>
              <key id="joinPolicy" for="node" attr.name="joinPolicy" attr.type="string"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="json-decision-example" edgedefault="directed">
                <node id="start"><data key="name">Start</data><data key="kind">START</data></node>
                <node id="parse">
                  <data key="name">Parse JSON</data>
                  <data key="kind">BEHAVIOR</data>
                  <data key="behavior">json-parse</data>
                </node>
                <node id="decide">
                  <data key="name">Check status</data>
                  <data key="kind">BEHAVIOR</data>
                  <data key="behavior">cel-decision</data>
                  <data key="expression">payload.status == 'OK'</data>
                  <data key="trueOutcome">accepted</data>
                  <data key="falseOutcome">rejected</data>
                </node>
                <node id="accepted-log">
                  <data key="name">Accepted</data>
                  <data key="kind">BEHAVIOR</data>
                  <data key="behavior">log</data>
                  <data key="message">accepted {{payload.status}}</data>
                </node>
                <node id="rejected-log">
                  <data key="name">Rejected</data>
                  <data key="kind">BEHAVIOR</data>
                  <data key="behavior">log</data>
                  <data key="message">rejected {{payload.status}}</data>
                </node>
                <node id="end"><data key="name">End</data><data key="kind">END</data>
                  <data key="joinPolicy">any</data></node>
                <node id="error"><data key="name">Error</data><data key="kind">ERROR</data></node>
                <edge id="e1" source="start" target="parse"><data key="outcome">continue</data></edge>
                <edge id="e2" source="parse" target="decide"><data key="outcome">continue</data></edge>
                <edge id="e3" source="decide" target="accepted-log"><data key="outcome">accepted</data></edge>
                <edge id="e4" source="decide" target="rejected-log"><data key="outcome">rejected</data></edge>
                <edge id="e5" source="accepted-log" target="end"><data key="outcome">continue</data></edge>
                <edge id="e6" source="rejected-log" target="end"><data key="outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    @Test
    void aTextualJsonPayloadSubmittedTheWayTheEditorSubmitsItSelectsTheBranchItsFieldChooses() throws Exception {
        var monitor = new ExecutionMonitor();
        try (var engine = new PekkoExecutionEngine("ravenroot-json-parse-decision-" + UUID.randomUUID());
             var application = new DefaultRavenrootApplication(engine, monitor);
             var server = new RavenrootServer(application,
                     new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null,
                     new DisabledLoopbackAuthenticator())) {
            server.start();
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

            String accepted = readResult(client, server,
                    submitRunAndAwait(client, server, monitor, "{\"status\":\"OK\",\"n\":3}"));
            String rejected = readResult(client, server,
                    submitRunAndAwait(client, server, monitor, "{\"status\":\"KO\",\"n\":3}"));

            assertEquals("COMPLETED", jsonString(accepted, "status"), () -> accepted);
            assertEquals("COMPLETED", jsonString(rejected, "status"), () -> rejected);

            assertEquals(Set.of("start", "parse", "decide", "accepted-log", "end"),
                    jsonStringSet(accepted, "visitedNodes"),
                    () -> "payload.status == 'OK' must select the accepted branch: " + accepted);

            assertEquals(Set.of("start", "parse", "decide", "rejected-log", "end"),
                    jsonStringSet(rejected, "visitedNodes"),
                    () -> "the same graph must take the other branch when the field changes, or the "
                            + "decision is not reading the field at all: " + rejected);

            // The structural change the decision rests on: what reached END is an object, not the
            // submitted text. Result payloads are written with PayloadJson, whose map keys are sorted.
            assertTrue(accepted.contains("\"payload\":{\"n\":3,\"status\":\"OK\"}"),
                    () -> "the payload must have stopped being a string: " + accepted);

            assertTrue(accepted.contains("\"handledFailure\":false"), () -> accepted);
            assertTrue(accepted.contains("\"defaultedNodes\":[]"),
                    () -> "json-parse must be a registered behaviour, not an unknown one defaulted "
                            + "through: " + accepted);
        }
    }

    /**
     * {@code runtime-client.js} {@code run()}, reproduced: same URL shape, same header including the
     * {@code charset} parameter it sends. The header is copied rather than approximated so that a
     * later change to what the UI sends shows up here as a difference to reconcile, not as a detail
     * this test had quietly decided did not matter.
     */
    private static String submitRunAndAwait(HttpClient client, RavenrootServer server, ExecutionMonitor monitor,
                                            String jsonText) throws Exception {
        var submitted = client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.port() + "/v1/executions?mode=run&payload="
                                + URLEncoder.encode(jsonText, StandardCharsets.UTF_8)))
                .header("Content-Type", "application/graphml+xml; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(DECISION_GRAPH)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(202, submitted.statusCode(), submitted.body());
        // The submission itself still reports the legacy scalar: nothing about the request opts into
        // the structured representation, which is what makes this the editor's path and not another.
        assertTrue(submitted.body().contains("\"payloadKind\":\"SCALAR\""), submitted.body());
        String executionId = jsonString(submitted.body(), "executionId");
        awaitTerminal(monitor, executionId);
        return executionId;
    }

    private static void awaitTerminal(ExecutionMonitor monitor, String executionId) throws InterruptedException {
        UUID id = UUID.fromString(executionId);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (monitor.eventsAfter(0).stream().anyMatch(event -> id.equals(event.executionId())
                    && (event.type() == ai.ravenroot.api.application.ExecutionEventType.EXECUTION_COMPLETED
                    || event.type() == ai.ravenroot.api.application.ExecutionEventType.EXECUTION_FAILED))) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("the traversal never reached a terminal event: " + executionId);
    }

    /**
     * Polled slowly and a bounded number of times: this route is rate limited like every other, so a
     * tight loop would turn a missing result into a 429 that looks like an unrelated defect.
     */
    private static String readResult(HttpClient client, RavenrootServer server, String executionId)
            throws Exception {
        HttpResponse<String> response = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port()
                    + "/v1/executions/" + executionId)).GET().build(), HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> observed = response;
            assertEquals(200, observed.statusCode(), () -> observed.body());
            if (!response.body().contains("\"status\":\"RUNNING\"")) {
                return response.body();
            }
            Thread.sleep(250);
        }
        throw new AssertionError("the execution never left RUNNING: " + response.body());
    }

    private static String jsonString(String body, String field) {
        int start = body.indexOf("\"" + field + "\":\"");
        if (start < 0) {
            throw new AssertionError("no string field '" + field + "' in " + body);
        }
        start += field.length() + 4;
        return body.substring(start, body.indexOf('"', start));
    }

    private static Set<String> jsonStringSet(String body, String field) {
        String marker = "\"" + field + "\":[";
        int start = body.indexOf(marker);
        if (start < 0) {
            throw new AssertionError("no array field '" + field + "' in " + body);
        }
        start += marker.length();
        int end = body.indexOf(']', start);
        if (end < 0) {
            throw new AssertionError("unterminated array field '" + field + "' in " + body);
        }
        String values = body.substring(start, end);
        if (values.isEmpty()) return Set.of();
        return java.util.Arrays.stream(values.split(","))
                .map(value -> value.substring(1, value.length() - 1))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
