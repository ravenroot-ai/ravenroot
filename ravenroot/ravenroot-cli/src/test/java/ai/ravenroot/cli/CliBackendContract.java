package ai.ravenroot.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * API-05: one command-behavior suite run against both the embedded and the remote
 * {@link CliBackend} -- the compatibility evidence the documented requirements asks for, in the same shape this codebase
 * already uses for "one contract, several adapters" ({@code GraphDeploymentContract},
 * {@code InboundSourceLifecycleContract}): an abstract class with the assertions, and one concrete
 * subclass per transport supplying {@link #backend}.
 *
 * <p>{@code cancel} and {@code drain} (API-02) are exercised here too, now that {@code
 * EXECUTION_CONTROL} is available. Pause/resume and the handler trigger remain outside this suite's
 * scope -- see {@code RavenrootCli}'s own comment at its dispatch switch -- so this contract does not
 * cover them.</p>
 *
 * <h2>What "compatibility evidence" covers</h2>
 * <p>Every fixture here except {@link #EXECUTING_GRAPH} exercises <em>shape</em>: status codes,
 * identifiers, counts, violations, error vocabulary, cancel outcomes. Shape agreement is real evidence
 * and it is not evidence of behavioural agreement. Previously this suite had nothing but shape --
 * {@link #GRAPH} contains no {@code BEHAVIOR} node, so a transport that never asked for real execution
 * was indistinguishable here from one that did, and the remote transport was exactly that.
 * {@link #bothTransportsExecuteBehaviorsAndReachTheTerminal} is the assertion that closes it, and the
 * only one in this class whose failure means the two transports <em>do different things</em> rather
 * than <em>say them differently</em>. Anything added here that matters to an operator belongs in that
 * category or is not covered by this suite's claim.</p>
 */
abstract class CliBackendContract {
    protected CliBackend backend;

    private static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="node-kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="wiring" edgedefault="directed">
                <node id="error"><data key="node-kind">ERROR</data></node>
                <node id="start"><data key="node-kind">START</data></node>
                <node id="end"><data key="node-kind">END</data></node>
                <edge id="start-end" source="start" target="end">
                  <data key="edge-outcome">continue</data>
                </edge>
              </graph>
            </graphml>
            """;

    /**
     * The fixture {@link #GRAPH} structurally cannot be a graph whose visited set
     * and payload both depend on behaviors having actually been constructed and run.
     *
     * <p>It reproduces {@code ravenroot-ui/public/examples/ravenroot-programmable.graphml}, the example
     * shipped with the product, because that document exposed the transport divergence and a new user
     * following the documentation against a remote server runs exactly it.</p>
     *
     * <p><b>Why a decision node and not merely a behavior node.</b> A behavior node that is never
     * constructed still gets visited and still forwards its input, so the only difference it makes is
     * in the payload — visible only to a reader who already knows what the graph should have produced.
     * {@code is-ravenroot} is a {@code cel-decision}: unconstructed, it evaluates no expression, so
     * <em>no outcome edge is taken at all</em> and the traversal stops there. The difference is then in
     * the set of nodes reached — three of eight instead of five — which is observable without knowing
     * the intended output, and is what {@link #bothTransportsExecuteBehaviorsAndReachTheTerminal}
     * asserts on.</p>
     */
    private static final String EXECUTING_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="template" for="node" attr.name="template" attr.type="string"/>
              <key id="expression" for="node" attr.name="expression" attr.type="string"/>
              <key id="trueOutcome" for="node" attr.name="trueOutcome" attr.type="string"/>
              <key id="falseOutcome" for="node" attr.name="falseOutcome" attr.type="string"/>
              <key id="message" for="node" attr.name="message" attr.type="string"/>
              <key id="joinPolicy" for="node" attr.name="joinPolicy" attr.type="string"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="cli-transport-parity" edgedefault="directed">
                <node id="start"><data key="kind">START</data></node>
                <node id="greeting">
                  <data key="kind">BEHAVIOR</data>
                  <data key="behavior">template</data>
                  <data key="template">Hello {{payload}}</data>
                </node>
                <node id="is-ravenroot">
                  <data key="kind">BEHAVIOR</data>
                  <data key="behavior">cel-decision</data>
                  <data key="expression">payload == 'Hello Ravenroot'</data>
                  <data key="trueOutcome">accepted</data>
                  <data key="falseOutcome">rejected</data>
                </node>
                <node id="accepted-log">
                  <data key="kind">BEHAVIOR</data>
                  <data key="behavior">log</data>
                  <data key="message">Accepted payload: {{payload}}</data>
                </node>
                <node id="rejected-log">
                  <data key="kind">BEHAVIOR</data>
                  <data key="behavior">log</data>
                  <data key="message">Rejected payload: {{payload}}</data>
                </node>
                <node id="end"><data key="kind">END</data><data key="joinPolicy">any</data></node>
                <node id="error"><data key="kind">ERROR</data></node>
                <node id="error-log">
                  <data key="kind">BEHAVIOR</data>
                  <data key="behavior">log</data>
                  <data key="message">Failed to build greeting for payload: {{payload}}</data>
                </node>
                <edge id="e1" source="start" target="greeting"><data key="outcome">continue</data></edge>
                <edge id="e2" source="greeting" target="is-ravenroot"><data key="outcome">continue</data></edge>
                <edge id="e3" source="is-ravenroot" target="accepted-log"><data key="outcome">accepted</data></edge>
                <edge id="e4" source="is-ravenroot" target="rejected-log"><data key="outcome">rejected</data></edge>
                <edge id="e5" source="accepted-log" target="end"><data key="outcome">continue</data></edge>
                <edge id="e6" source="rejected-log" target="end"><data key="outcome">continue</data></edge>
                <edge id="e7" source="greeting" target="error"><data key="outcome">failed</data></edge>
                <edge id="e8" source="error" target="error-log"><data key="outcome">continue</data></edge>
                <edge id="e9" source="error-log" target="end"><data key="outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    @Test
    final void statusReportsAnExecutionEngine() throws Exception {
        var status = backend.status();
        assertFalse(status.state().isBlank());
        assertFalse(status.executionEngine().isBlank());
    }

    @Test
    final void runtimeReportsNonNegativeActiveExecutions() throws Exception {
        assertTrue(backend.runtime().activeExecutions() >= 0);
    }

    @Test
    final void nodeTypesIncludesTheStandardCatalog() throws Exception {
        assertFalse(backend.nodeTypes().isEmpty());
    }

    @Test
    final void inspectReportsExactNodeAndEdgeCounts() throws Exception {
        var summary = backend.inspect(GRAPH.getBytes(StandardCharsets.UTF_8));
        // Three: start, end, and the error terminal this fixture happens to declare. Not three
        // because the minimal structure requires it -- the error terminal is optional (zero-or-one,
        // not exactly-one). GRAPH simply still declares one.
        assertEquals(3, summary.nodes());
        assertEquals(1, summary.edges());
        assertEquals(1, summary.startNodes());
        assertEquals(1, summary.endNodes());
        // InspectView now carries the same valid()/violations() pair
        // GraphSummary does, on both transports this contract runs against.
        assertTrue(summary.valid());
        assertEquals(List.of(), summary.violations());
    }

    /**
     * Previously, {@code inspect}'s output -- on both transports -- was
     * byte-identical whether the document was a valid graph or not: {@code CliBackend.InspectView}
     * carried only the four counts {@link #inspectReportsExactNodeAndEdgeCounts} checks above, and an
     * unknown node kind never showed up in them (see the measured example on {@code GraphSummary}'s
     * own Javadoc). This is the same document {@link #GRAPH} constant would otherwise use, with its
     * error terminal's kind corrupted to something Ravenroot does not know.
     */
    @Test
    final void inspectNamesASemanticViolationOnBothTransports() throws Exception {
        String invalidGraph = GRAPH.replace("ERROR", "SUBGRAPH").replace("error", "mystery");
        var summary = backend.inspect(invalidGraph.getBytes(StandardCharsets.UTF_8));

        assertFalse(summary.valid());
        assertEquals(List.of("Node 'mystery' declares an unknown kind 'SUBGRAPH'; "
                        + "the known kinds are START, PASSTHROUGH, BEHAVIOR, END, ERROR"),
                summary.violations());
        // The counts still print: an unrunnable document remains inspectable.
        assertEquals(3, summary.nodes());
        assertEquals(1, summary.edges());
    }

    @Test
    final void runStartsATraversalAndReturnsARealIdentity() throws Exception {
        var submission = backend.run(GRAPH.getBytes(StandardCharsets.UTF_8), "dual-transport-payload");
        assertFalse(submission.processInstanceId().isBlank());
        assertFalse(submission.traversalId().isBlank());
        assertFalse(submission.executionId().isBlank());
        assertFalse(submission.graphVersion().isBlank());
        assertFalse(submission.executionPolicy().isBlank(),
                "a submission must state which policy it ran under, on either transport");
    }

    /**
     * This is the first automated proof the product produces anything: it runs a real graph
     * through a real backend and asserts the read-back result carries a non-empty payload, not merely
     * that the API compiles or that a status code comes back.
     */
    @Test
    final void runThenReadTheResultReturnsANonEmptyPayload() throws Exception {
        var submission = backend.run(GRAPH.getBytes(StandardCharsets.UTF_8), "ravenroot-330");

        var view = awaitTerminalResult(submission.executionId());

        assertEquals("COMPLETED", view.status());
        assertNotNull(view.payload());
        assertFalse(view.payload().isBlank());
        assertFalse(view.visitedNodes().isEmpty());
        assertNotNull(view.defaultedNodes());
    }

    /**
     * The assertion the rest of this suite was structurally unable to make:
     * the two transports, given the same graph and the same input, reach the same nodes and produce
     * the same payload.
     *
     * <p><b>This was red on {@code RemoteCliBackendContractTest} and green on
     * {@code EmbeddedCliBackendContractTest} before the fix</b>, which is the whole point of it
     * existing: {@code RemoteBackend.run} submitted to {@code POST /v1/executions} without a
     * {@code mode} parameter, so the server applied its documented default of {@code mode=test}
     * ({@code ExecutionPolicy.TEST_PASSTHROUGH}) and constructed no behavior. Measured, remote
     * answered {@code COMPLETED}, {@code degraded=false}, {@code visitedNodes=[greeting,
     * is-ravenroot, start]} and no payload at all, while embedded — which submits under
     * {@code ExecutionPolicy.STANDARD} — answered the five nodes and {@code "Hello Ravenroot"}
     * asserted below.</p>
     *
     * <p>Note what the status and degraded flag do <em>not</em> do here, and why this test asserts on
     * neither: a truncated traversal reports {@code COMPLETED} and {@code degraded=false} on both
     * transports. Those two fields are identical in the broken case and the correct one.</p>
     */
    @Test
    final void bothTransportsExecuteBehaviorsAndReachTheTerminal() throws Exception {
        var submission = backend.run(EXECUTING_GRAPH.getBytes(StandardCharsets.UTF_8), "Ravenroot");

        var view = awaitTerminalResult(submission.executionId());

        assertEquals("COMPLETED", view.status());
        assertEquals(List.of("accepted-log", "end", "greeting", "is-ravenroot", "start"),
                view.visitedNodes(),
                "the traversal must reach the decision's accepted branch and the terminal");
        assertEquals("\"Hello Ravenroot\"", view.payload(),
                "the template and decision behaviors must have actually run");
        // Not a restatement of the two assertions above: those say the run did the
        // right thing, this says the transport would have TOLD us had it not. Before the fix this list
        // was absent from ResultView entirely on both transports, so the one signal the server
        // published about nodes it had not executed could not reach a caller at all.
        assertEquals(List.of(), view.bypassedNodes(),
                "a real run bypasses nothing, and the caller must be able to see that rather than assume it");
        assertFalse(view.handledFailure());
        assertEquals(List.of(), view.handledFailureNodes());
        // Same argument as bypassedNodes above, proven on both transports by this one shared
        // contract test: nothing was bypassed, so nothing can be untaken either.
        assertEquals(List.of(), view.untakenEdges());
        // The policy each transport reports for the same command must agree, since the command is the
        // same command. This is what makes EmbeddedBackend's named STANDARD constant checkable rather
        // than merely asserted in its own Javadoc.
        assertEquals("STANDARD", submission.executionPolicy(),
                "both transports must submit -- and report -- the standard execution policy");
    }

    /**
     * Bounded by attempt count, not wall-clock deadline, and spaced 500ms apart rather than hammered
     * every 50ms: {@code GET /v1/executions/{id}} is rate-limited like every other route on the remote
     * transport, and a tight loop can turn a real, fast result into a spurious 429 -- a failure about
     * this suite's own polling behavior, not about the feature under test. 12 attempts at 500ms is
     * 6 seconds, comfortably more than any fixture here needs to complete.
     */
    private CliBackend.ResultView awaitTerminalResult(String executionId) throws Exception {
        int maxAttempts = 12;
        CliBackend.ResultView view = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            view = backend.result(executionId);
            if ("COMPLETED".equals(view.status()) || "FAILED".equals(view.status())) {
                return view;
            }
            if (attempt == maxAttempts) {
                fail("Execution " + executionId + " did not reach a terminal status within "
                        + maxAttempts + " attempts; last observed status was " + view.status());
            }
            Thread.sleep(500);
        }
        return view;
    }

    /**
     * Shape/wire-format evidence on both transports, and one real assertion: a traversal that
     * has reached a terminal status must not still be reported live. {@link #GRAPH} has no
     * {@code BEHAVIOR} node, so by the time {@link #awaitTerminalResult} returns COMPLETED the
     * traversal is certainly finished on both transports, which is what makes "must be absent" a
     * meaningful check here rather than a race. The complementary property -- a <em>stalled</em>
     * execution stays listed while it is genuinely still running -- is proved deterministically, with
     * a latch rather than a timing assumption, by {@code LiveExecutionsCliTest}: an embedded-only test,
     * because it needs a custom {@code BehaviorRegistry} neither this contract's generic
     * {@code @BeforeEach} nor the remote transport's server process expose a seam for.
     */
    @Test
    final void liveDoesNotReportATerminalExecution() throws Exception {
        var submission = backend.run(GRAPH.getBytes(StandardCharsets.UTF_8), "live-contract-payload");
        awaitTerminalResult(submission.executionId());

        var live = backend.live();
        assertNotNull(live);
        for (var execution : live) {
            assertFalse(execution.traversalId().isBlank());
            assertFalse(execution.processInstanceId().isBlank());
            assertFalse(execution.executionId().isBlank());
            assertNotNull(execution.startedAt());
        }
        assertTrue(live.stream().noneMatch(e -> e.traversalId().equals(submission.traversalId())),
                "a traversal that has already completed must not still be reported live");
    }

    /** Proves the two transports agree on failure, not just on success. */
    @Test
    final void readingAnUnknownExecutionIdFailsExplicitly() {
        String unknownId = UUID.randomUUID().toString();
        IOException failure = assertThrows(IOException.class, () -> backend.result(unknownId));
        assertTrue(failure.getMessage().contains("UNKNOWN_EXECUTION"),
                "expected UNKNOWN_EXECUTION in message, got: " + failure.getMessage());
    }

    /**
     * API-02: REST and CLI, local and remote, must produce the same application
     * result. The graph completes near-instantly, so which of the three outcomes lands is a race this
     * test does not control -- what it proves identically for both transports is the shape: one of the
     * contract-defined distinguishable outcomes, the same traversal id echoed back, and a non-blank note
     * (the "effects already issued may persist" statement, carried as data -- see
     * {@code CliBackend.CancelView}'s own Javadoc).
     */
    @Test
    final void cancelReturnsAWellFormedDistinguishableOutcome() throws Exception {
        var submission = backend.run(GRAPH.getBytes(StandardCharsets.UTF_8), "cancel-contract-payload");
        var result = backend.cancel(submission.traversalId());
        assertTrue(Set.of("CANCELLED", "ALREADY_CANCELLED", "ALREADY_COMPLETED").contains(result.outcome()),
                "unexpected outcome: " + result.outcome());
        assertEquals(submission.traversalId(), result.traversalId());
        assertFalse(result.note().isBlank());
    }

    /** A repeated cancel of the same traversal must never report a fresh {@code CANCELLED} again. */
    @Test
    final void cancelIsIdempotentOnASecondCall() throws Exception {
        var submission = backend.run(GRAPH.getBytes(StandardCharsets.UTF_8), "cancel-contract-payload-2");
        backend.cancel(submission.traversalId());
        var second = backend.cancel(submission.traversalId());
        assertTrue(Set.of("ALREADY_CANCELLED", "ALREADY_COMPLETED").contains(second.outcome()),
                "a second cancel of the same traversal must not report a fresh CANCELLED: " + second.outcome());
    }

    /** Own test method, deliberately: draining is engine-wide and each subclass's {@code @BeforeEach}
     * gives every test its own fresh engine, but nothing else in this suite should run after one. */
    @Test
    final void drainReturnsAWellFormedOutcome() throws Exception {
        var result = backend.drain();
        assertTrue(Set.of("DRAINED", "TIMED_OUT").contains(result.outcome()),
                "unexpected outcome: " + result.outcome());
    }

    /**
     * Whole lifecycle in one pass: register (starts nothing), list, inspect, start (reaches
     * READY, not merely "requested"), restart (a completed stop then start, never overlapping), stop
     * (leaves the registration intact and re-startable), and undeploy (stops and then removes it). Runs
     * identically on both transports, which is the compatibility evidence this class exists to give --
     * see its own Javadoc. {@link #GRAPH} has no effective SOURCE, so {@code sourceCount} is asserted
     * at {@code 0} throughout: a source-less graph is registrable and controllable as a deployment, and
     * that is the capability this surface exposes.
     */
    @Test
    final void deploymentLifecycleRegistersStartsRestartsStopsAndUndeploys() throws Exception {
        String deploymentId = "cli-deployment-lifecycle";
        var registered = backend.registerDeployment(deploymentId, GRAPH.getBytes(StandardCharsets.UTF_8));
        assertEquals(deploymentId, registered.deploymentId());
        assertEquals("REGISTERED", registered.state(), "register must start nothing");
        assertEquals(0, registered.sourceCount());
        assertEquals("LOCAL_PROCESS", registered.scope(),
                "every deployment response must carry the LOCAL_PROCESS scope, on both transports");
        assertNull(registered.diagnostic(), "a REGISTERED deployment has nothing to explain");

        assertTrue(backend.deployments().stream().anyMatch(d -> deploymentId.equals(d.deploymentId())),
                "a freshly registered deployment must appear in this tenant's own listing");

        assertEquals("REGISTERED", backend.deployment(deploymentId).state());

        var started = backend.startDeployment(deploymentId);
        assertEquals("READY", started.state(), "start must answer at readiness, not merely accept the request");
        assertEquals("LOCAL_PROCESS", started.scope());

        var restarted = backend.restartDeployment(deploymentId);
        assertEquals("READY", restarted.state());

        var stopped = backend.stopDeployment(deploymentId);
        assertEquals("STOPPED", stopped.state(), "stop must answer the authoritative reached state");

        // Stop leaves the registration intact and re-startable -- the property that distinguishes it
        // from undeploy below.
        assertEquals("STOPPED", backend.deployment(deploymentId).state());
        var restartedAfterStop = backend.startDeployment(deploymentId);
        assertEquals("READY", restartedAfterStop.state(), "a stopped deployment must still be startable");
        backend.stopDeployment(deploymentId);

        var undeployed = backend.undeployDeployment(deploymentId);
        assertEquals("STOPPED", undeployed.state(), "undeploy reports the final state observed before removal");

        // Once undeployed, every read and every command reports the identical nondisclosing failure an
        // id never registered would, preserving the non-disclosure guarantee.
        assertUnknownDeployment(() -> backend.deployment(deploymentId));
        assertUnknownDeployment(() -> backend.stopDeployment(deploymentId));
    }

    /**
     * Re-registering the same id with the identical graph is a no-op that returns the current
     * status, never a fresh {@code REGISTERED} that pretends nothing existed before; re-registering it
     * with a different graph is refused on both transports, because a registered graph version is
     * immutable.
     */
    @Test
    final void registerIsIdempotentWithTheSameGraphAndRefusesADifferentOne() throws Exception {
        String deploymentId = "cli-deployment-register-conflict";
        backend.registerDeployment(deploymentId, GRAPH.getBytes(StandardCharsets.UTF_8));

        var again = backend.registerDeployment(deploymentId, GRAPH.getBytes(StandardCharsets.UTF_8));
        assertEquals(deploymentId, again.deploymentId());
        assertEquals("REGISTERED", again.state(), "re-registering the identical graph must be a no-op");

        String differentGraph = GRAPH.replace("wiring", "different-wiring");
        Exception refusal = assertThrows(Exception.class, () -> backend.registerDeployment(deploymentId,
                differentGraph.getBytes(StandardCharsets.UTF_8)),
                "a different graph under an already-registered id must be refused, on both transports");
        assertFalse(refusal.getMessage() == null || refusal.getMessage().isBlank());
    }

    /** An id this tenant never registered fails exactly like one already undeployed -- see
     * {@link #deploymentLifecycleRegistersStartsRestartsStopsAndUndeploys}'s final assertions. */
    @Test
    final void anUnregisteredDeploymentIdFailsExplicitlyOnEveryVerb() {
        String neverRegistered = "cli-deployment-never-registered-" + UUID.randomUUID();
        assertUnknownDeployment(() -> backend.deployment(neverRegistered));
        assertUnknownDeployment(() -> backend.startDeployment(neverRegistered));
        assertUnknownDeployment(() -> backend.stopDeployment(neverRegistered));
        assertUnknownDeployment(() -> backend.restartDeployment(neverRegistered));
        assertUnknownDeployment(() -> backend.undeployDeployment(neverRegistered));
    }

    private static void assertUnknownDeployment(org.junit.jupiter.api.function.Executable call) {
        IOException failure = assertThrows(IOException.class, call);
        assertTrue(failure.getMessage().contains("UNKNOWN_RESOURCE") || failure.getMessage().contains("404"),
                "expected an unknown-resource failure, got: " + failure.getMessage());
    }
}
