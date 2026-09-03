package ai.ravenroot.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code run} command's own printing path, for the reason {@link ResultCommandTest} exists
 * for {@code result} — "nothing proved that the CLI actually shows a caller what is in it".
 *
 * <p>That reasoning applies with full force to {@code execution-policy}, and applying it everywhere
 * except the one new operator-facing line would have been the same omission one layer up:
 * {@code CliBackendContract} proves both transports <em>report</em> the policy, and proved nothing
 * about whether {@link RavenrootCli} prints it. Before this class, no test in this module invoked the
 * {@code run} command at all.</p>
 *
 * <p>Driven through a stub backend rather than a live engine because the assertion is about what the
 * command prints, not about what a submission computes — and because the interesting case,
 * {@code TEST_PASSTHROUGH}, is one the CLI can no longer produce by itself now that
 * {@code RemoteBackend.run} states {@code mode=run}. A stub is the only way to check that the line
 * carries what the backend said rather than a constant this command decided.</p>
 */
class RunCommandTest {

    @TempDir
    Path workingDirectory;

    private static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <graph id="run-command" edgedefault="directed">
                <node id="start"><data key="kind">START</data></node>
                <node id="end"><data key="kind">END</data></node>
              </graph>
            </graphml>
            """;

    private Path graphFile() throws IOException {
        Path file = workingDirectory.resolve("graph.graphml");
        Files.writeString(file, GRAPH, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void printsTheIdentifiersAndTheExecutionPolicy() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var view = new CliBackend.RunView("p1", "t1", "e1", "v1", "STANDARD");

        assertEquals(0, new RavenrootCli(new StubBackend(view), new PrintStream(bytes), new PrintStream(bytes))
                .run("run", graphFile().toString(), "payload"));

        String printed = bytes.toString();
        assertTrue(printed.contains("process-instance-id=p1"), printed);
        assertTrue(printed.contains("traversal-id=t1"), printed);
        assertTrue(printed.contains("execution-id=e1"), printed);
        assertTrue(printed.contains("graph-version=v1"), printed);
        assertTrue(printed.contains("execution-policy=STANDARD"),
                () -> "the policy the submission ran under must reach the operator: " + printed);
    }

    /**
     * The case the line exists for. A caller reaching a server that applied the Play policy — because
     * a future default changed, a proxy stripped the query, or someone pointed this CLI at something
     * that is not {@code RavenrootServer} — must see that word, not infer it from a payload they would
     * have to already know the right value of.
     */
    @Test
    void printsTheSurprisingPolicyRatherThanAssumingTheStandardOne() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var view = new CliBackend.RunView("p2", "t2", "e2", "v2", "TEST_PASSTHROUGH");

        assertEquals(0, new RavenrootCli(new StubBackend(view), new PrintStream(bytes), new PrintStream(bytes))
                .run("run", graphFile().toString(), "payload"));

        String printed = bytes.toString();
        assertTrue(printed.contains("execution-policy=TEST_PASSTHROUGH"),
                () -> "a run that constructed no behavior must say so: " + printed);
    }

    private static final class StubBackend implements CliBackend {
        private final RunView view;

        private StubBackend(RunView view) {
            this.view = view;
        }

        @Override
        public RunView run(byte[] graphMl, String payload) {
            return view;
        }

        @Override public StatusView status() throws IOException { throw new IOException("unused"); }
        @Override public RuntimeView runtime() throws IOException { throw new IOException("unused"); }
        @Override public List<NodeTypeView> nodeTypes() throws IOException { throw new IOException("unused"); }
        @Override public InspectView inspect(byte[] graphMl) throws IOException { throw new IOException("unused"); }
        @Override public ResultView result(String executionId) throws IOException {
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
