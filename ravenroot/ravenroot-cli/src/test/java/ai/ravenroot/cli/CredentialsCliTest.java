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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two {@code credentials} verbs the CLI exposes. The CLI and interface configure credentials
 * through the same API.
 *
 * <p>Driven through a stub {@link CliBackend}: what is under test is the command surface, not a live
 * {@code /v1/credentials} route. Deliberately not added to {@link CliBackendContract} either, because
 * the embedded transport has no credential store and refuses, so the two transports do not mean the
 * same thing here.</p>
 */
class CredentialsCliTest {

    @Test
    void listRendersReferenceLabelSchemeAndNeverAValueField() {
        var output = new ByteArrayOutputStream();
        var cli = new RavenrootCli(new StubBackend(List.of(
                new CliBackend.CredentialView("rrc_abcdef0123456789abcdef0123456789", "my openai key",
                        "api-key", "", "2026-08-27T10:00:00Z"),
                new CliBackend.CredentialView("rrc_11112222333344445555666677778888", "db login",
                        "basic", "admin", "2026-08-27T10:01:00Z"))),
                print(output), print(new ByteArrayOutputStream()));

        assertEquals(0, cli.run("credentials", "list"));
        String printed = output.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("reference=rrc_abcdef0123456789abcdef0123456789"), printed);
        assertTrue(printed.contains("label=my openai key"), printed);
        assertTrue(printed.contains("scheme=api-key"), printed);
        assertTrue(printed.contains("reference=rrc_11112222333344445555666677778888"), printed);
        assertTrue(printed.contains("scheme=basic"), printed);
        assertTrue(printed.contains("username=admin"), printed);
        // No response this route can produce ever carries a value, in any form -- and this rendering
        // has no field that could carry one either.
        assertFalse(printed.contains("value="), printed);
    }

    @Test
    void listWithNoCredentialsPrintsNoLines() {
        var output = new ByteArrayOutputStream();
        var cli = new RavenrootCli(new StubBackend(List.of()), print(output),
                print(new ByteArrayOutputStream()));
        assertEquals(0, cli.run("credentials", "list"));
        assertEquals("", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void addPrintsTheStoredCredentialShape(@TempDir Path tempDir) throws IOException {
        Path valueFile = tempDir.resolve("value.txt");
        Files.writeString(valueFile, "sk-not-a-real-secret", StandardCharsets.UTF_8);
        var output = new ByteArrayOutputStream();
        var backend = new StubBackend(List.of());
        var cli = new RavenrootCli(backend, print(output), print(new ByteArrayOutputStream()));

        assertEquals(0, cli.run("credentials", "add", "--label", "my key", "--scheme", "api-key",
                "--value-file", valueFile.toString()));

        String printed = output.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("reference=" + StubBackend.MINTED_REFERENCE), printed);
        assertTrue(printed.contains("label=my key"), printed);
        assertTrue(printed.contains("scheme=api-key"), printed);
        // The backend received exactly the value the file held -- proves the value made it from disk
        // to the call, not merely that the call happened.
        assertEquals("sk-not-a-real-secret", backend.lastValue);
    }

    /**
     * <b>The value never reaches this process's own printed output, on either stream.</b> The command
     * is built exactly as an operator would type it -- a file path, never a flag carrying the secret
     * text itself (see {@link CredentialAddArgs}, which refuses {@code --value} outright) -- and then
     * run against a stub backend that answers normally. Neither stdout nor stderr may contain the
     * secret string.
     *
     * <p>This assertion is only as strong as {@link RavenrootCli#addCredential}'s own restraint: it
     * never prints {@code parsed.value()}. That restraint was verified by mutation -- temporarily
     * adding {@code output.println("value=" + parsed.value())} to that method turned this test red,
     * confirming it actually detects the leak it exists to catch; the mutation was then reverted rather
     * than left in the tree. See this class's own PR/report for the mutation record, since JUnit itself
     * cannot self-report having been run in both states.</p>
     */
    @Test
    void theValueNeverReachesStdoutOrStderr(@TempDir Path tempDir) throws IOException {
        String secret = "s3cr3t-value-that-must-never-print-7f2a";
        Path valueFile = tempDir.resolve("value.txt");
        Files.writeString(valueFile, secret, StandardCharsets.UTF_8);

        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var cli = new RavenrootCli(new StubBackend(List.of()), print(out), print(err));

        int exitCode = cli.run("credentials", "add", "--label", "leak-check", "--scheme", "api-key",
                "--value-file", valueFile.toString());

        assertEquals(0, exitCode);
        String stdout = out.toString(StandardCharsets.UTF_8);
        String stderr = err.toString(StandardCharsets.UTF_8);
        assertFalse(stdout.contains(secret), () -> "value leaked on stdout: " + stdout);
        assertFalse(stderr.contains(secret), () -> "value leaked on stderr: " + stderr);
        // The command must still have actually run and reached the backend -- an empty output would
        // pass the two assertions above for the wrong reason.
        assertTrue(stdout.contains("reference="), stdout);
    }

    @Test
    void anUnknownSubcommandIsAUsageError() {
        var errors = new ByteArrayOutputStream();
        var cli = new RavenrootCli(new StubBackend(List.of()), print(new ByteArrayOutputStream()),
                print(errors));
        assertEquals(2, cli.run("credentials", "delete", "rrc_x"));
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("credentials <list|add"));
    }

    @Test
    void aMalformedAddIsReportedRatherThanCallingTheBackend() {
        var errors = new ByteArrayOutputStream();
        var backend = new StubBackend(List.of());
        var cli = new RavenrootCli(backend, print(new ByteArrayOutputStream()), print(errors));

        assertEquals(1, cli.run("credentials", "add", "--scheme", "api-key", "--value-file", "-"));
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("--label"),
                errors.toString(StandardCharsets.UTF_8));
        assertEquals(0, backend.addCalls, "a malformed request must never reach the backend");
    }

    /**
     * <b>Without {@code --server} both credential verbs fail, naming the flag, rather than answering
     * nothing.</b> An empty list would be indistinguishable from an author who has genuinely stored
     * nothing, so the refusal -- not a well-formed empty answer -- is the honest response.
     */
    @Test
    void theEmbeddedTransportRefusesAndNamesTheFlag() throws Exception {
        try (var engine = new ai.ravenroot.pekko.PekkoExecutionEngine("credential-cli")) {
            var application = new ai.ravenroot.api.application.AuthorizedRavenrootApplication(
                    new ai.ravenroot.core.runtime.DefaultRavenrootApplication(engine,
                            new ai.ravenroot.core.runtime.ExecutionMonitor()),
                    new ai.ravenroot.api.security.DefaultAuthorizationService(event -> { }),
                    event -> { }, true);
            var context = new ai.ravenroot.api.security.RequestContext("test", "test-cli",
                    ai.ravenroot.api.security.PrincipalType.USER, "test", "local",
                    java.util.Set.of(ai.ravenroot.api.security.Role.PLATFORM_ADMIN), java.util.Set.of());
            var backend = new EmbeddedBackend(application, context);
            for (var call : List.<org.junit.jupiter.api.function.Executable>of(
                    backend::credentials,
                    () -> backend.addCredential("x", "api-key", "", "value"))) {
                var refused = assertThrows(IOException.class, call);
                assertTrue(refused.getMessage().contains("--server"), refused.getMessage());
            }
        }
    }

    private static PrintStream print(ByteArrayOutputStream sink) {
        return new PrintStream(sink, true, StandardCharsets.UTF_8);
    }

    /** Answers the two credential calls this test drives and refuses everything else. */
    private static final class StubBackend implements CliBackend {
        static final String MINTED_REFERENCE = "rrc_stubbed0000000000000000000000";

        private final List<CredentialView> listing;
        private String lastValue;
        private int addCalls;

        private StubBackend(List<CredentialView> listing) {
            this.listing = new ArrayList<>(listing);
        }

        @Override
        public List<CredentialView> credentials() {
            return listing;
        }

        @Override
        public CredentialView addCredential(String label, String scheme, String username, String value) {
            addCalls++;
            lastValue = value;
            return new CredentialView(MINTED_REFERENCE, label, scheme, username, "2026-08-28T00:00:00Z");
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
        @Override public InventoryListing inventory() throws IOException { throw new IOException("unused"); }
        @Override public TraversalListing traversals(String processInstanceId) throws IOException {
            throw new IOException("unused");
        }
        @Override public CancelView cancel(String traversalId) throws IOException {
            throw new IOException("unused");
        }
        @Override public DrainView drain() throws IOException { throw new IOException("unused"); }
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
