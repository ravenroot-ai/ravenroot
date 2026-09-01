package ai.ravenroot.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code credentials add}'s own argument parser. Driven directly against
 * {@link CredentialAddArgs#parse}, the same "test the parser in isolation" shape
 * {@code ai.ravenroot.cli.remote.RemoteCliTokenNeverAppearsInOutputTest}'s own Javadoc points to for
 * the sibling rule -- this is that test for {@code --value}.
 *
 * <p><b>That Javadoc named a test class that does not exist.</b> The rule it pointed at is real and
 * lives in {@code ai.ravenroot.cli.GlobalOptions#parse}; what was missing was anything asserting it,
 * which is why {@link #theSiblingTokenRuleIsAssertedAndNotOnlyWritten} below now does. Both the false
 * pointer and the uncovered rule were found by the same mechanical sweep that
 * {@code TestCitationsResolveTest} now performs on every build.</p>
 */
class CredentialAddArgsTest {

    private static final InputStream EMPTY_STDIN = new ByteArrayInputStream(new byte[0]);

    /**
     * <b>The sibling rule this class was written after is now asserted, not merely described.</b>
     *
     * <p>{@code GlobalOptions#parse} refuses {@code --token} for exactly the reason {@code --value} is
     * refused above, and previously <b>nothing in the repository asserted it</b> —
     * two Javadocs claimed a test class covered it, and that class has never existed. Adding the
     * assertion here rather than in a new file keeps the two halves of one rule in one place, and
     * costs four lines.</p>
     */
    @Test
    void theSiblingTokenRuleIsAssertedAndNotOnlyWritten() {
        var refused = assertThrows(IllegalArgumentException.class,
                () -> ai.ravenroot.cli.GlobalOptions.parse(new String[] {"--token", "s3cr3t", "status"}));
        assertTrue(refused.getMessage().contains("--token"), refused.getMessage());
        assertTrue(refused.getMessage().contains("--token-file"),
                "must point the caller at the accepted flag: " + refused.getMessage());
        assertFalse(refused.getMessage().contains("s3cr3t"),
                "the refusal must not quote the token it declined: " + refused.getMessage());
    }

    /** Rule 29's own enforcement point for this verb: refused by name, not by the generic
     * "unknown option" branch, so the reason is visible without reading this class's source. */
    @Test
    void valueIsRefusedByNameWithAUsefulMessage() {
        var refused = assertThrows(IllegalArgumentException.class, () -> CredentialAddArgs.parse(
                new String[] {"--label", "x", "--scheme", "api-key", "--value", "s3cr3t"}, EMPTY_STDIN));
        assertTrue(refused.getMessage().contains("--value"), refused.getMessage());
        assertTrue(refused.getMessage().contains("--value-file"),
                "must point the caller at the accepted flag: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("shell history") || refused.getMessage().contains("ps"),
                "must name the reason, the same courtesy GlobalOptions extends --token: "
                        + refused.getMessage());
    }

    @Test
    void valueFileReadsTheFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("secret.txt");
        Files.writeString(file, "the-secret-value\n", StandardCharsets.UTF_8);

        var parsed = CredentialAddArgs.parse(new String[] {"--label", "my key", "--scheme", "api-key",
                "--value-file", file.toString()}, EMPTY_STDIN);

        assertEquals("my key", parsed.label());
        assertEquals("api-key", parsed.scheme());
        assertEquals("", parsed.username());
        // The trailing newline an editor or `echo` would add is stripped, the same convention
        // CliToken#resolve already applies to --token-file.
        assertEquals("the-secret-value", parsed.value());
    }

    @Test
    void valueFileDashReadsStdin() throws Exception {
        var stdin = new ByteArrayInputStream("piped-secret\n".getBytes(StandardCharsets.UTF_8));

        var parsed = CredentialAddArgs.parse(new String[] {"--label", "x", "--scheme", "oauth-token",
                "--value-file", "-"}, stdin);

        assertEquals("piped-secret", parsed.value());
    }

    @Test
    void aMissingLabelIsRefused() {
        var refused = assertThrows(IllegalArgumentException.class, () -> CredentialAddArgs.parse(
                new String[] {"--scheme", "api-key", "--value-file", "-"}, EMPTY_STDIN));
        assertTrue(refused.getMessage().contains("--label"), refused.getMessage());
    }

    @Test
    void aMissingSchemeIsRefused() {
        var refused = assertThrows(IllegalArgumentException.class, () -> CredentialAddArgs.parse(
                new String[] {"--label", "x", "--value-file", "-"}, EMPTY_STDIN));
        assertTrue(refused.getMessage().contains("--scheme"), refused.getMessage());
    }

    @Test
    void anUnknownSchemeIsRefused() {
        var refused = assertThrows(IllegalArgumentException.class, () -> CredentialAddArgs.parse(
                new String[] {"--label", "x", "--scheme", "bearer", "--value-file", "-"}, EMPTY_STDIN));
        assertTrue(refused.getMessage().contains("bearer"), refused.getMessage());
    }

    /**
     * <b>Refused before any request is made.</b> No {@code --value-file} is given at all in this call,
     * and the parser still refuses on the username rule rather than falling through to "requires
     * --value-file" or, worse, reading a value and letting {@link CliBackend#addCredential} reject it
     * over the network -- proving the ordering {@link CredentialAddArgs}'s own Javadoc claims: every
     * flag is validated before the filesystem or stdin is ever touched.
     */
    @Test
    void basicWithoutUsernameIsRefusedBeforeAnyRequestIsMade() {
        var refused = assertThrows(IllegalArgumentException.class, () -> CredentialAddArgs.parse(
                new String[] {"--label", "x", "--scheme", "basic"}, EMPTY_STDIN));
        assertTrue(refused.getMessage().contains("--username"), refused.getMessage());
        assertTrue(refused.getMessage().contains("basic"), refused.getMessage());
    }

    @Test
    void nonBasicSchemeWithAUsernameIsRefused() {
        var refused = assertThrows(IllegalArgumentException.class, () -> CredentialAddArgs.parse(
                new String[] {"--label", "x", "--scheme", "api-key", "--username", "someone",
                        "--value-file", "-"}, EMPTY_STDIN));
        assertTrue(refused.getMessage().contains("--username"), refused.getMessage());
    }

    @Test
    void basicWithAUsernameParsesCleanly() throws Exception {
        var stdin = new ByteArrayInputStream("hunter2".getBytes(StandardCharsets.UTF_8));
        var parsed = CredentialAddArgs.parse(new String[] {"--label", "db", "--scheme", "basic",
                "--username", "  admin  ", "--value-file", "-"}, stdin);
        assertEquals("admin", parsed.username());
        assertEquals("hunter2", parsed.value());
    }

    @Test
    void aMissingValueFileIsRefused() {
        var refused = assertThrows(IllegalArgumentException.class, () -> CredentialAddArgs.parse(
                new String[] {"--label", "x", "--scheme", "api-key"}, EMPTY_STDIN));
        assertTrue(refused.getMessage().contains("--value-file"), refused.getMessage());
    }

    @Test
    void anEmptyValueFileIsRefused(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("empty.txt");
        Files.writeString(file, "   \n", StandardCharsets.UTF_8);
        var refused = assertThrows(IllegalArgumentException.class, () -> CredentialAddArgs.parse(
                new String[] {"--label", "x", "--scheme", "api-key", "--value-file", file.toString()},
                EMPTY_STDIN));
        assertTrue(refused.getMessage().contains("empty"), refused.getMessage());
    }

    /** {@code toString} must never render the value -- see this record's own Javadoc for why the
     * override is not redundant here, unlike the server-side {@code char[]} form. */
    @Test
    void toStringNeverRendersTheValue() throws Exception {
        var parsed = CredentialAddArgs.parse(new String[] {"--label", "x", "--scheme", "api-key",
                "--value-file", "-"}, new ByteArrayInputStream("s3cr3t-value".getBytes(StandardCharsets.UTF_8)));
        assertTrue(parsed.toString().contains("redacted"), parsed.toString());
        assertTrue(!parsed.toString().contains("s3cr3t-value"), parsed.toString());
    }
}
