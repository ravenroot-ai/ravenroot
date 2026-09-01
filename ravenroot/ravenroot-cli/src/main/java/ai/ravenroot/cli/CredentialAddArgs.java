package ai.ravenroot.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Parses the flags for adding a credential through the CLI.
 *
 * <h2>No {@code --value} flag, ever</h2>
 * <p>Exactly the rule {@link ai.ravenroot.cli.remote.CliToken} states for the bearer token, and that
 * {@link GlobalOptions} enforces structurally by rejecting {@code --token} outright (Rule 29): a
 * command-line flag's value lands in shell history and in every other process's view of {@code ps}.
 * {@code --value-file <path>} is the only way to supply the secret text here -- a file,
 * permission-controlled by the operator -- with {@code --value-file -} meaning "read it from stdin"
 * for a caller who would rather pipe it than ever write it to disk. A caller who types {@code --value}
 * is refused by name below, naming the reason, rather than falling into the generic "unknown option"
 * branch -- the same courtesy {@code GlobalOptions} extends {@code --token}.</p>
 *
 * <h2>Scheme vocabulary duplicated, not imported</h2>
 * <p>{@code api-key}/{@code basic}/{@code oauth-token}, and "only {@code basic} carries a username",
 * mirror {@code ai.ravenroot.server.credential.CredentialScheme} exactly and are restated here rather
 * than imported: {@code ravenroot-cli} depends on {@code ravenroot-server} at test scope only (see
 * that dependency's own comment in this module's {@code pom.xml}), precisely so the shipped CLI never
 * carries the server on its runtime classpath. {@link CliBackend.CredentialView} is the same shape of
 * duplication for the identical reason -- as {@code CliBackend.ModelProviderView} was, until the
 * model-provider verbs left the CLI with the configuration plane they address.</p>
 *
 * <h2>Order: validate first, touch the filesystem last</h2>
 * <p>{@link #parse} checks every flag -- presence, the closed scheme set, the per-scheme username rule
 * -- before it ever reads {@code --value-file} or stdin. A caller who mistypes {@code --scheme} or
 * forgets {@code --username} on a {@code basic} credential is told so without this class needing a
 * real file to exist, and {@link RavenrootCli#run} never reaches {@link CliBackend#addCredential} for
 * a request that was already wrong.</p>
 */
record CredentialAddArgs(String label, String scheme, String username, String value) {

    static final Set<String> KNOWN_SCHEMES = Set.of("api-key", "basic", "oauth-token");

    /**
     * Overridden for the reason {@code ai.ravenroot.server.credential.UserCredentialWire.CreateRequest}
     * overrides its own: a record's generated {@code toString} would already keep {@code value} out
     * (it is a {@code String} component here, so the default form WOULD print it verbatim, unlike that
     * class's {@code char[]}) -- so this override is not redundant, it is the whole reason {@code value}
     * cannot leak through an accidental log line or assertion failure message that stringifies this
     * record.
     */
    @Override
    public String toString() {
        return "CredentialAddArgs[label=" + label + ", scheme=" + scheme + ", username=" + username
                + ", value=redacted]";
    }

    /**
     * @param stdin read only when {@code --value-file -} is given. Production wiring
     *              ({@link RavenrootCli}) passes {@code System.in}; a test passes a fixture stream, so
     *              this class never needs {@code System.setIn} to be exercised.
     * @throws IllegalArgumentException on any malformed or incomplete argument list -- caught nowhere
     *                                   in this class, so it reaches {@link RavenrootCli#run}'s own
     *                                   catch-all and is reported the same way every other refused
     *                                   command here is.
     */
    static CredentialAddArgs parse(String[] args, InputStream stdin) throws IOException {
        String label = null;
        String scheme = null;
        String username = null;
        String valueFile = null;
        for (int index = 0; index < args.length; index++) {
            switch (args[index]) {
                case "--label" -> {
                    requireValue(args, index, "--label");
                    label = args[++index];
                }
                case "--scheme" -> {
                    requireValue(args, index, "--scheme");
                    scheme = args[++index];
                }
                case "--username" -> {
                    requireValue(args, index, "--username");
                    username = args[++index];
                }
                case "--value-file" -> {
                    requireValue(args, index, "--value-file");
                    valueFile = args[++index];
                }
                case "--value" -> throw new IllegalArgumentException(
                        "--value is not supported: a flag value lands in shell history and in every "
                                + "other process's view of the command line. Pass --value-file <path> "
                                + "instead (or --value-file - to read the value from stdin).");
                default -> throw new IllegalArgumentException(
                        "Unknown option for 'credentials add': " + args[index]);
            }
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("'credentials add' requires --label <text>");
        }
        if (scheme == null) {
            throw new IllegalArgumentException(
                    "'credentials add' requires --scheme <api-key|basic|oauth-token>");
        }
        if (!KNOWN_SCHEMES.contains(scheme)) {
            throw new IllegalArgumentException("unknown --scheme '" + scheme
                    + "': must be one of api-key, basic, oauth-token");
        }
        boolean carriesUsername = "basic".equals(scheme);
        if (carriesUsername && (username == null || username.isBlank())) {
            throw new IllegalArgumentException("--scheme basic requires --username <text>");
        }
        if (!carriesUsername && username != null && !username.isBlank()) {
            throw new IllegalArgumentException("--scheme " + scheme + " does not take --username");
        }
        if (valueFile == null) {
            throw new IllegalArgumentException("'credentials add' requires --value-file <path> "
                    + "(or --value-file - to read the value from stdin); there is no --value flag "
                    + "(Rule 29)");
        }
        String value = readValue(valueFile, stdin);
        return new CredentialAddArgs(label.strip(), scheme, carriesUsername ? username.strip() : "", value);
    }

    /** Trailing whitespace stripped on both sources, the same convention {@code CliToken#resolve}
     * already applies to {@code --token-file}: an editor- or echo-appended newline must not become
     * part of the secret. */
    private static String readValue(String valueFile, InputStream stdin) throws IOException {
        String raw = "-".equals(valueFile)
                ? new String(stdin.readAllBytes(), StandardCharsets.UTF_8).strip()
                : Files.readString(Path.of(valueFile), StandardCharsets.UTF_8).strip();
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("--value-file '" + valueFile + "' is empty");
        }
        return raw;
    }

    private static void requireValue(String[] args, int index, String flag) {
        if (index + 1 >= args.length) {
            throw new IllegalArgumentException(flag + " requires a value");
        }
    }
}
