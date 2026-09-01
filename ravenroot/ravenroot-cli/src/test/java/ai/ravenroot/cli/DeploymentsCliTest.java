package ai.ravenroot.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code deployments} verb's own printing path, driven through a stub {@link CliBackend} the
 * same way {@link CredentialsCliTest} drives {@code credentials} -- what is under test here is the
 * command surface (dispatch, argument handling, the printed line shape), not a live
 * {@code /v1/deployments} route. {@link CliBackendContract} already proves the lifecycle itself agrees
 * between the embedded and remote transports; this class proves {@link RavenrootCli} shows an operator
 * what a {@link CliBackend.DeploymentView} carries, unconditionally naming the {@code LOCAL_PROCESS}
 * scope on every line.
 */
class DeploymentsCliTest {

    @Test
    void listPrintsOneLinePerDeploymentWithScope() {
        var output = new ByteArrayOutputStream();
        var backend = new StubBackend(List.of(
                new CliBackend.DeploymentView("orders", "READY", 2, "LOCAL_PROCESS", null),
                new CliBackend.DeploymentView("billing", "DEGRADED", 1, "LOCAL_PROCESS", "one inbound source unhealthy")));
        var cli = new RavenrootCli(backend, print(output), print(new ByteArrayOutputStream()));

        assertEquals(0, cli.run("deployments", "list"));
        String printed = output.toString(StandardCharsets.UTF_8);
        String[] lines = printed.split("\n");
        assertEquals("deployment-id=orders\tstate=READY\tsource-count=2\tscope=LOCAL_PROCESS", lines[0].strip(),
                "a deployment with no diagnostic must not print a diagnostic field at all");
        assertTrue(printed.contains("deployment-id=billing\tstate=DEGRADED\tsource-count=1"
                + "\tscope=LOCAL_PROCESS\tdiagnostic=one inbound source unhealthy"), printed);
    }

    @Test
    void listWithNoDeploymentsPrintsNoLines() {
        var output = new ByteArrayOutputStream();
        var cli = new RavenrootCli(new StubBackend(List.of()), print(output), print(new ByteArrayOutputStream()));
        assertEquals(0, cli.run("deployments", "list"));
        assertEquals("", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void registerReadsTheGraphFileAndPrintsTheRegisteredView(@TempDir Path tempDir) throws IOException {
        Path graphFile = tempDir.resolve("graph.graphml");
        Files.writeString(graphFile, "<graphml/>", StandardCharsets.UTF_8);
        var output = new ByteArrayOutputStream();
        var backend = new StubBackend(List.of());
        var cli = new RavenrootCli(backend, print(output), print(new ByteArrayOutputStream()));

        assertEquals(0, cli.run("deployments", "register", "orders", graphFile.toString()));

        String printed = output.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("deployment-id=orders\tstate=REGISTERED\tsource-count=0"
                + "\tscope=LOCAL_PROCESS"), printed);
        assertEquals("orders", backend.lastRegisteredId);
        assertEquals("<graphml/>", new String(backend.lastRegisteredGraph, StandardCharsets.UTF_8));
    }

    @Test
    void inspectStartStopRestartAndUndeployAllPrintTheReturnedView() throws IOException {
        var output = new ByteArrayOutputStream();
        var backend = new StubBackend(List.of());
        var cli = new RavenrootCli(backend, print(output), print(new ByteArrayOutputStream()));

        backend.view = new CliBackend.DeploymentView("orders", "REGISTERED", 0, "LOCAL_PROCESS", null);
        assertEquals(0, cli.run("deployments", "inspect", "orders"));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("state=REGISTERED"));

        output.reset();
        backend.view = new CliBackend.DeploymentView("orders", "READY", 0, "LOCAL_PROCESS", null);
        assertEquals(0, cli.run("deployments", "start", "orders"));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("state=READY"));

        output.reset();
        backend.view = new CliBackend.DeploymentView("orders", "STOPPED", 0, "LOCAL_PROCESS", null);
        assertEquals(0, cli.run("deployments", "stop", "orders"));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("state=STOPPED"));

        output.reset();
        backend.view = new CliBackend.DeploymentView("orders", "READY", 0, "LOCAL_PROCESS", null);
        assertEquals(0, cli.run("deployments", "restart", "orders"));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("state=READY"));

        output.reset();
        backend.view = new CliBackend.DeploymentView("orders", "STOPPED", 0, "LOCAL_PROCESS", null);
        assertEquals(0, cli.run("deployments", "undeploy", "orders"));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("state=STOPPED"));

        assertEquals(List.of("inspect:orders", "start:orders", "stop:orders", "restart:orders",
                "undeploy:orders"), backend.calls);
    }

    @Test
    void anUnknownSubcommandIsAUsageError() {
        var errors = new ByteArrayOutputStream();
        var cli = new RavenrootCli(new StubBackend(List.of()), print(new ByteArrayOutputStream()),
                print(errors));
        assertEquals(2, cli.run("deployments", "delete", "orders"));
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("deployments <list"),
                errors.toString(StandardCharsets.UTF_8));
    }

    @Test
    void missingArgumentsAreRejectedRatherThanGuessed() {
        var errors = new ByteArrayOutputStream();
        var backend = new StubBackend(List.of());
        var cli = new RavenrootCli(backend, print(new ByteArrayOutputStream()), print(errors));

        assertEquals(2, cli.run("deployments", "start"));
        assertTrue(backend.calls.isEmpty(), "a malformed command must never reach the backend");
        assertEquals(2, cli.run("deployments", "register", "orders"));
        assertTrue(backend.calls.isEmpty());
    }

    private static PrintStream print(ByteArrayOutputStream sink) {
        return new PrintStream(sink, true, StandardCharsets.UTF_8);
    }

    /** Answers every deployment call this test drives and refuses everything else. */
    private static final class StubBackend implements CliBackend {
        private final List<DeploymentView> listing;
        private final List<String> calls = new ArrayList<>();
        private DeploymentView view;
        private String lastRegisteredId;
        private byte[] lastRegisteredGraph;

        private StubBackend(List<DeploymentView> listing) {
            this.listing = new ArrayList<>(listing);
        }

        @Override
        public List<DeploymentView> deployments() {
            return listing;
        }

        @Override
        public DeploymentView registerDeployment(String deploymentId, byte[] graphMl) {
            lastRegisteredId = deploymentId;
            lastRegisteredGraph = graphMl;
            return new DeploymentView(deploymentId, "REGISTERED", 0, "LOCAL_PROCESS", null);
        }

        @Override
        public DeploymentView deployment(String deploymentId) {
            calls.add("inspect:" + deploymentId);
            return view;
        }

        @Override
        public DeploymentView startDeployment(String deploymentId) {
            calls.add("start:" + deploymentId);
            return view;
        }

        @Override
        public DeploymentView stopDeployment(String deploymentId) {
            calls.add("stop:" + deploymentId);
            return view;
        }

        @Override
        public DeploymentView restartDeployment(String deploymentId) {
            calls.add("restart:" + deploymentId);
            return view;
        }

        @Override
        public DeploymentView undeployDeployment(String deploymentId) {
            calls.add("undeploy:" + deploymentId);
            return view;
        }

        @Override public StatusView status() throws IOException { throw new IOException("unused"); }
        @Override public RuntimeView runtime() throws IOException { throw new IOException("unused"); }
        @Override public List<NodeTypeView> nodeTypes() throws IOException { throw new IOException("unused"); }
        @Override public InspectView inspect(byte[] graphMl) throws IOException { throw new IOException("unused"); }
        @Override public RunView run(byte[] graphMl, String payload) throws IOException {
            throw new IOException("unused");
        }
        @Override public ResultView result(String executionId) throws IOException {
            throw new IOException("unused");
        }
        @Override public List<LiveView> live() throws IOException { throw new IOException("unused"); }
        @Override public CancelView cancel(String traversalId) throws IOException {
            throw new IOException("unused");
        }
        @Override public DrainView drain() throws IOException { throw new IOException("unused"); }
        @Override public List<CredentialView> credentials() throws IOException { throw new IOException("unused"); }
        @Override public CredentialView addCredential(String label, String scheme, String username, String value)
                throws IOException {
            throw new IOException("unused");
        }
    }
}
