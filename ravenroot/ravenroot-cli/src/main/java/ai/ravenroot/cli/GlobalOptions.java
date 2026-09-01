package ai.ravenroot.cli;

import java.util.ArrayList;

/**
 * {@code --server <url>} and {@code --token-file <path>}, parsed out of the raw argument list before
 * any command dispatch (API-05). {@code --server}'s presence, and nothing else, decides embedded
 * vs. remote (see {@link CliBackend}) -- structurally, because {@link RavenrootCliMain} branches on
 * {@link #serverUrl()} before constructing anything embedded-only.
 *
 * <p>Deliberately no {@code --token}: see {@code ai.ravenroot.cli.remote.CliToken}'s own Javadoc for
 * why (Rule 29).</p>
 */
record GlobalOptions(String serverUrl, String tokenFile, String[] remainingArgs) {
    static GlobalOptions parse(String[] args) {
        String server = null;
        String tokenFile = null;
        var remaining = new ArrayList<String>();
        for (int index = 0; index < args.length; index++) {
            switch (args[index]) {
                case "--server" -> {
                    requireValue(args, index, "--server");
                    server = args[++index];
                }
                case "--token-file" -> {
                    requireValue(args, index, "--token-file");
                    tokenFile = args[++index];
                }
                case "--token" -> throw new IllegalArgumentException(
                        "--token is not supported (Rule 29): a flag value lands in shell history and in "
                                + "every process's view of the command line. Set RAVENROOT_TOKEN or pass "
                                + "--token-file <path> instead.");
                default -> remaining.add(args[index]);
            }
        }
        return new GlobalOptions(server, tokenFile, remaining.toArray(new String[0]));
    }

    private static void requireValue(String[] args, int index, String flag) {
        if (index + 1 >= args.length) {
            throw new IllegalArgumentException(flag + " requires a value");
        }
    }
}
