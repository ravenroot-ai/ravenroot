package ai.ravenroot.cli.remote;

import ai.ravenroot.api.security.Role;
import ai.ravenroot.cli.RavenrootCli;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.RavenrootServer;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rule 29, made testable rather than merely observed (API-05): a real secret token must never
 * reach stdout, stderr, or a rendered error, whether the remote call succeeds or is refused. Shaped
 * after {@code RavenrootCliConsoleSanitizationTest} -- the same "capture, then assert the secret's
 * absence" pattern, applied to the new remote path rather than to {@code sanitizeForConsole} in
 * isolation.
 *
 * <p>Does not test {@code --token} itself, because there is no such flag to test the absence of
 * failing to work -- its outright rejection lives in {@code ai.ravenroot.cli.GlobalOptions#parse} and
 * is asserted by {@code CredentialAddArgsTest#theSiblingTokenRuleIsAssertedAndNotOnlyWritten}. <b>This
 * sentence used to name a test class that has never existed</b>, and the rule it claimed as covered
 * was in fact covered by nothing. {@code TestCitationsResolveTest} now fails the build on a pointer
 * like it. This class is
 * about the token that DOES flow through the accepted paths ({@code RAVENROOT_TOKEN}/{@code --token-file},
 * both of which resolve to the same in-memory string {@link RemoteBackend} carries) never leaking back
 * out.</p>
 */
class RemoteCliTokenNeverAppearsInOutputTest {
    private static final String SECRET_TOKEN = "s3cr3t-token-4f9a2b17-do-not-print-me";

    @TempDir
    Path uiDirectory;

    @Test
    void theTokenNeverAppearsInOutputOnASuccessfulCall() throws Exception {
        try (var engine = new PekkoExecutionEngine("rule29-success");
             var server = tokenServer(engine)) {
            server.start();
            var captured = run(server, SECRET_TOKEN, "status");
            assertFalse(captured.contains(SECRET_TOKEN), () -> "token leaked on success: " + captured);
            assertTrue(captured.contains("state="), "the command must still have actually run: " + captured);
        }
    }

    @Test
    void theTokenNeverAppearsInOutputWhenTheServerRefusesIt() throws Exception {
        try (var engine = new PekkoExecutionEngine("rule29-refused");
             var server = tokenServer(engine)) {
            server.start();
            // A wrong token forces the 401 path -- exactly where a careless implementation might echo
            // the Authorization header it just failed to validate back into an error message.
            var captured = run(server, "wrong-" + SECRET_TOKEN, "status");
            assertFalse(captured.contains(SECRET_TOKEN), () -> "token leaked on refusal: " + captured);
            assertTrue(captured.contains("Error:"), () -> "a refusal must still be reported: " + captured);
        }
    }

    /** Runs the CLI's remote path directly (bypassing {@code RavenrootCliMain}, which calls System.exit). */
    private static String run(RavenrootServer server, String token, String... commandArgs) throws Exception {
        var outBytes = new ByteArrayOutputStream();
        var errBytes = new ByteArrayOutputStream();
        var backend = new RemoteBackend(URI.create("http://localhost:" + server.port() + "/"), token,
                Duration.ofSeconds(10));
        try (var out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
             var err = new PrintStream(errBytes, true, StandardCharsets.UTF_8)) {
            new RavenrootCli(backend, out, err).run(commandArgs);
        }
        return outBytes.toString(StandardCharsets.UTF_8) + errBytes.toString(StandardCharsets.UTF_8);
    }

    private RavenrootServer tokenServer(PekkoExecutionEngine engine) {
        return new RavenrootServer(new DefaultRavenrootApplication(engine, new ExecutionMonitor()),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), uiDirectory,
                headers -> {
                    String header = headers.getFirst("Authorization");
                    if (!("Bearer " + SECRET_TOKEN).equals(header)) {
                        throw new ai.ravenroot.server.security.AuthenticationException("invalid token");
                    }
                    return new AuthenticatedPrincipal("token-cli-caller", AuthenticatedPrincipal.Type.USER,
                            "urn:ravenroot:rule29-test", "local", Set.of(Role.PLATFORM_ADMIN),
                            java.util.Arrays.stream(ai.ravenroot.api.security.AuthorizationAction.values())
                                    .filter(ai.ravenroot.api.security.AuthorizationAction::available)
                                    .map(ai.ravenroot.api.security.AuthorizationAction::requiredScope)
                                    .collect(java.util.stream.Collectors.toUnmodifiableSet()));
                });
    }
}
