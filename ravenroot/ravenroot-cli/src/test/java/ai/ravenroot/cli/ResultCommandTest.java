package ai.ravenroot.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code result} command's own printing path.
 *
 * <p>{@link CliBackendContract} exercises both transports' {@code ResultView}; nothing proved that the
 * CLI actually shows a caller what is in it. That gap matters most for the degraded case: a run whose
 * nodes silently defaulted reports a plain {@code COMPLETED} status, so if the CLI omitted
 * {@code defaulted-nodes} the operator would read "success" and stop looking — the exact failure this
 * command exists to prevent, reintroduced one layer above the backend fix.</p>
 *
 * <p><b>The compatibility evidence must cover behavior, not merely response shape.</b> "Proves both
 * transports return the same {@code ResultView}" was not true when it was written: the contract's execution fixture had
 * no behavior node, so the two transports could not have been told apart there even in principle, and
 * they in fact differed. {@code CliBackendContract#bothTransportsExecuteBehaviorsAndReachTheTerminal}
 * is what makes an equality claim checkable now, and it is that test — not this class — that carries
 * it. And "the degraded case" was one of three cases with this shape: a fully bypassed run and a
 * handled failure also report {@code COMPLETED} with {@code degraded=false}, and are covered below.</p>
 *
 * <p>Driven through a stub backend rather than a live engine because the assertion is about what the
 * command prints, not about what the runtime computes; a real engine would make the degraded case
 * depend on an unresolved-behavior fixture and test two things at once.</p>
 */
class ResultCommandTest {

    private static RavenrootCli cli(CliBackend backend, PrintStream out) {
        return new RavenrootCli(backend, out, out);
    }

    @Test
    void printsStatusPayloadAndVisitedNodes() {
        var bytes = new ByteArrayOutputStream();
        var view = new CliBackend.ResultView("e1", "COMPLETED", false, List.of("start", "end"),
                List.of(), List.of(), false, List.of(), List.of(), "\"hello\"");

        assertEquals(0, cli(new StubBackend(view), new PrintStream(bytes)).run("result", "e1"));

        String printed = bytes.toString();
        assertTrue(printed.contains("execution-id=e1"), printed);
        assertTrue(printed.contains("status=COMPLETED"), printed);
        assertTrue(printed.contains("visited-nodes=start,end"), printed);
        assertTrue(printed.contains("payload=\"hello\""), printed);
        assertTrue(printed.contains("degraded=false"), printed);
        assertFalse(printed.contains("defaulted-nodes="),
                "a clean run must not print an empty defaulted-nodes line");
    }

    /** The case the endpoint exists for: success that is not actually clean. */
    @Test
    void makesADegradedRunVisibleEvenThoughItsStatusIsPlainSuccess() {
        var bytes = new ByteArrayOutputStream();
        var view = new CliBackend.ResultView("e2", "COMPLETED", true, List.of("start", "future", "end"),
                List.of("future"), List.of(), false, List.of(), List.of(), "\"hello\"");

        assertEquals(0, cli(new StubBackend(view), new PrintStream(bytes)).run("result", "e2"));

        String printed = bytes.toString();
        assertTrue(printed.contains("status=COMPLETED"), printed);
        assertTrue(printed.contains("degraded=true"),
                () -> "a run with defaulted nodes must be reported as degraded: " + printed);
        assertTrue(printed.contains("defaulted-nodes=future"),
                () -> "the defaulted node ids must be named, not merely counted: " + printed);
    }

    /**
     * The case the comment above {@code defaulted-nodes} promised to cover and did not: a run in
     * which nothing was executed at all. {@code status=COMPLETED}, {@code degraded=false} and a full
     * {@code visited-nodes} list are byte-identical to a clean run, so {@code bypassed-nodes} is the
     * only line that distinguishes them.
     */
    @Test
    void makesARunThatExecutedNothingVisibleThoughItLooksExactlyLikeACleanOne() {
        var bytes = new ByteArrayOutputStream();
        var view = new CliBackend.ResultView("e4", "COMPLETED", false, List.of("start", "effect", "end"),
                List.of(), List.of("start", "effect", "end"), false, List.of(), List.of(),
                "\"OPERATOR-PAYLOAD\"");

        assertEquals(0, cli(new StubBackend(view), new PrintStream(bytes)).run("result", "e4"));

        String printed = bytes.toString();
        assertTrue(printed.contains("status=COMPLETED"), printed);
        assertTrue(printed.contains("degraded=false"), printed);
        assertTrue(printed.contains("bypassed-nodes=start,effect,end"),
                () -> "a run whose nodes were all bypassed must say so: " + printed);
    }

    /** The same shape for the other signal the CLI was discarding: a handled failure. */
    @Test
    void makesAHandledFailureVisibleThoughTheRunCompleted() {
        var bytes = new ByteArrayOutputStream();
        var view = new CliBackend.ResultView("e5", "COMPLETED", false, List.of("start", "flaky", "end"),
                List.of(), List.of(), true, List.of("flaky"), List.of(), "\"hello\"");

        assertEquals(0, cli(new StubBackend(view), new PrintStream(bytes)).run("result", "e5"));

        String printed = bytes.toString();
        assertTrue(printed.contains("handled-failure=true"), printed);
        assertTrue(printed.contains("handled-failure-nodes=flaky"),
                () -> "the node that failed must be named, not merely counted: " + printed);
    }

    /** A clean run prints neither of the two lines above, for the reason the empty-defaulted case does. */
    @Test
    void aCleanRunPrintsNeitherBypassedNorHandledFailureLines() {
        var bytes = new ByteArrayOutputStream();
        var view = new CliBackend.ResultView("e6", "COMPLETED", false, List.of("start", "end"),
                List.of(), List.of(), false, List.of(), List.of(), "\"hello\"");

        assertEquals(0, cli(new StubBackend(view), new PrintStream(bytes)).run("result", "e6"));

        String printed = bytes.toString();
        assertFalse(printed.contains("bypassed-nodes="), printed);
        assertFalse(printed.contains("handled-failure"), printed);
        assertFalse(printed.contains("untaken-edges="),
                "a run with nothing bypassed must not print an empty untaken-edges line");
    }

    /**
     * Not a restatement of {@code bypassed-nodes=start,effect,end} above: that line says WHICH
     * node was bypassed, this one says what its bypass cost in edges its own hardcoded {@code
     * "continue"} outcome could never select -- {@code bypassedNodes} alone cannot tell a bypassed
     * node with a plain single {@code continue} edge apart from one with a branch point behind only custom
     * outcomes, and only this field does.
     */
    @Test
    void makesUntakenEdgesVisibleForAnIndividuallyBypassedBranchPoint() {
        var bytes = new ByteArrayOutputStream();
        var view = new CliBackend.ResultView("e7", "COMPLETED", false, List.of("start", "gate"),
                List.of(), List.of("start", "gate"), false, List.of(),
                List.of("gate->approvedNode [outcome=approved]", "gate->rejectedNode [outcome=rejected]"),
                "\"in\"");

        assertEquals(0, cli(new StubBackend(view), new PrintStream(bytes)).run("result", "e7"));

        String printed = bytes.toString();
        assertTrue(printed.contains("bypassed-nodes=start,gate"), printed);
        assertTrue(printed.contains("untaken-edges=gate->approvedNode [outcome=approved],"
                + "gate->rejectedNode [outcome=rejected]"),
                () -> "both untaken edges must be named, exactly, not merely counted: " + printed);
    }

    @Test
    void aRunningExecutionPrintsNoPayloadLineRatherThanAnEmptyOne() {
        var bytes = new ByteArrayOutputStream();
        var view = new CliBackend.ResultView("e3", "RUNNING", false, List.of(), List.of(),
                List.of(), false, List.of(), List.of(), null);

        assertEquals(0, cli(new StubBackend(view), new PrintStream(bytes)).run("result", "e3"));

        String printed = bytes.toString();
        assertTrue(printed.contains("status=RUNNING"), printed);
        assertFalse(printed.contains("payload="), "no payload yet must mean no payload line: " + printed);
    }

    @Test
    void missingOrExtraArgumentsAreRejectedRatherThanGuessed() {
        var bytes = new ByteArrayOutputStream();
        var backend = new StubBackend(new CliBackend.ResultView("e", "COMPLETED", false,
                List.of(), List.of(), List.of(), false, List.of(), List.of(), "1"));

        assertFalse(cli(backend, new PrintStream(bytes)).run("result") == 0,
                "a missing execution id must not be treated as a valid request");
        assertEquals(0, backend.calls, "the backend must not be called for a malformed command");
    }

    private static final class StubBackend implements CliBackend {
        private final ResultView view;
        private int calls;

        private StubBackend(ResultView view) {
            this.view = view;
        }

        @Override
        public ResultView result(String executionId) {
            calls++;
            return view;
        }

        @Override public StatusView status() throws IOException { throw new IOException("unused"); }
        @Override public RuntimeView runtime() throws IOException { throw new IOException("unused"); }
        @Override public List<NodeTypeView> nodeTypes() throws IOException { throw new IOException("unused"); }
        @Override public InspectView inspect(byte[] graphMl) throws IOException { throw new IOException("unused"); }
        @Override public RunView run(byte[] graphMl, String payload) throws IOException {
            throw new IOException("unused");
        }
        @Override public List<LiveView> live() throws IOException { throw new IOException("unused"); }
        @Override public List<InventoryView> inventory() throws IOException { throw new IOException("unused"); }
        @Override public List<TraversalInventoryView> traversals(String processInstanceId) throws IOException {
            throw new IOException("unused");
        }
        @Override public CancelView cancel(String traversalId) throws IOException {
            throw new IOException("unused");
        }
        @Override public DrainView drain() throws IOException { throw new IOException("unused"); }
        // Unused here, refusing like every other method this stub does not answer.
        @Override public List<CredentialView> credentials() throws IOException { throw new IOException("unused"); }
        @Override public CredentialView addCredential(String label, String scheme, String username, String value)
                throws IOException {
            throw new IOException("unused");
        }
        // Unused here, refusing like every other method this stub does not answer.
        @Override public List<DeploymentView> deployments() throws IOException { throw new IOException("unused"); }
        @Override public DeploymentView registerDeployment(String deploymentId, byte[] graphMl) throws IOException {
            throw new IOException("unused");
        }
        @Override public DeploymentView deployment(String deploymentId) throws IOException {
            throw new IOException("unused");
        }
        @Override public DeploymentView startDeployment(String deploymentId) throws IOException {
            throw new IOException("unused");
        }
        @Override public DeploymentView stopDeployment(String deploymentId) throws IOException {
            throw new IOException("unused");
        }
        @Override public DeploymentView restartDeployment(String deploymentId) throws IOException {
            throw new IOException("unused");
        }
        @Override public DeploymentView undeployDeployment(String deploymentId) throws IOException {
            throw new IOException("unused");
        }
    }
}
