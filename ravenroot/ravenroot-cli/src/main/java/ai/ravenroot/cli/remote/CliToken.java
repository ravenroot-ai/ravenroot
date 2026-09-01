package ai.ravenroot.cli.remote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Resolves the bearer token for the remote CLI backend (API-05, Rule 29).
 *
 * <h2>Never a {@code --token} flag</h2>
 * <p>A command-line flag's value lands in shell history and in every other process's view of
 * {@code ps}. This class deliberately offers no way to supply one: {@code RAVENROOT_TOKEN} (an
 * environment variable, not persisted the way history and process listings are) or
 * {@code --token-file &lt;path&gt;} (a file, permission-controlled by the operator) are the only two
 * sources. There is no third parameter this class accepts that could become a flag later without
 * changing this type's own signature — the constraint is structural, not a convention documented
 * elsewhere and easy to violate by adding one more {@code case} to an argument switch.
 */
public final class CliToken {
    /** The only environment variable this reads. No {@code --token} flag exists anywhere in this CLI. */
    public static final String ENV_VARIABLE = "RAVENROOT_TOKEN";

    private CliToken() {
    }

    /**
     * @param environment  typically {@code System.getenv()}
     * @param tokenFilePath the {@code --token-file} argument's value, or {@code null} if absent — takes
     *                      priority over the environment variable when both are given, since an explicit
     *                      flag is a more specific instruction than ambient environment
     */
    public static String resolve(Map<String, String> environment, String tokenFilePath) throws IOException {
        if (tokenFilePath != null) {
            String fromFile = Files.readString(Path.of(tokenFilePath), StandardCharsets.UTF_8).strip();
            if (fromFile.isEmpty()) {
                throw new IllegalArgumentException("--token-file '" + tokenFilePath + "' is empty");
            }
            return fromFile;
        }
        String fromEnvironment = environment.get(ENV_VARIABLE);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment;
        }
        throw new IllegalStateException("--server requires a token: set " + ENV_VARIABLE + " or pass "
                + "--token-file <path>. There is no --token flag (Rule 29) -- a flag value lands in shell "
                + "history and in every process's view of the command line.");
    }
}
