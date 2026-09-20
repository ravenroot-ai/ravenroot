package ai.ravenroot.cli;

import ai.ravenroot.cli.remote.RemoteBackend;
import java.io.PrintStream;
import java.util.Map;
import java.util.UUID;

/** Tenant-scoped operator commands; never exposes worker claim/report authority. */
final class RunnerPlaneCommand {
    private RunnerPlaneCommand() { }
    static int run(String[] arguments, RemoteBackend backend, PrintStream output, PrintStream errors) {
        try {
            if (arguments.length < 2) throw new IllegalArgumentException(usage());
            String operation = arguments[1];
            String path;
            String method = "GET";
            Map<String, Object> body = Map.of();
            switch (operation) {
                case "catalog" -> {
                    if (arguments.length == 2) path = "catalog";
                    else if (arguments.length == 3 && arguments[2].matches("(AGENT_DEFINITION|RUNNER|WORKSPACE_PROFILE):[a-z][a-z0-9._-]{0,63}:[1-9][0-9]*"))
                        path = "catalog/" + arguments[2];
                    else throw new IllegalArgumentException(usage());
                }
                case "publish" -> {
                    if (arguments.length != 3) throw new IllegalArgumentException(usage());
                    var limits = ai.ravenroot.api.payload.PayloadLimits.DEFAULTS;
                    try (var input = java.nio.file.Files.newInputStream(java.nio.file.Path.of(arguments[2]))) {
                        byte[] bytes = input.readNBytes(limits.maxEncodedBytes() + 1);
                        var payload = ai.ravenroot.api.payload.PayloadJson.read(bytes, limits);
                        if (!(payload instanceof ai.ravenroot.api.payload.PayloadValue.MapValue object)) throw new IllegalArgumentException("catalog object required");
                        @SuppressWarnings("unchecked") var parsed = (Map<String, Object>) object.toJava();
                        body = parsed;
                    }
                    if (!body.containsKey("expectedRevision")) throw new IllegalArgumentException("catalog publication requires expectedRevision");
                    path = "catalog"; method = "PUT";
                }
                case "artifact" -> {
                    if (arguments.length != 5) throw new IllegalArgumentException(usage());
                    path = "workspaces/" + UUID.fromString(arguments[2]) + "/jobs/" + UUID.fromString(arguments[3]) + "/artifacts/" + UUID.fromString(arguments[4]);
                }
                case "availability", "health", "audit" -> {
                    if (arguments.length != 2) throw new IllegalArgumentException(usage());
                    path = operation;
                }
                case "workspace" -> {
                    if (arguments.length != 3) throw new IllegalArgumentException(usage());
                    path = "workspaces/" + UUID.fromString(arguments[2]);
                }
                case "stop-workspace" -> {
                    if (arguments.length != 5 || !arguments[3].matches("[A-Za-z_][A-Za-z0-9_.-]*"))
                        throw new IllegalArgumentException(usage());
                    long revision = Long.parseLong(arguments[4]);
                    if (revision < 1) throw new IllegalArgumentException("positive observed process revision required");
                    path = "workspaces/" + UUID.fromString(arguments[2]) + "/resources/" + arguments[3] + "/abort";
                    method = "POST"; body = Map.of("expectedRevision", revision);
                }
                case "cancel-job", "reconcile-job" -> {
                    if (arguments.length != 4) throw new IllegalArgumentException(usage());
                    path = "workspaces/" + UUID.fromString(arguments[2]) + "/jobs/" + UUID.fromString(arguments[3])
                            + (operation.equals("cancel-job") ? "/cancel" : "/reconcile");
                    method = "POST";
                }
                case "resolve-job" -> {
                    if (arguments.length != 6 || !java.util.Set.of("RESUME", "ACKNOWLEDGE", "ABANDON").contains(arguments[5]))
                        throw new IllegalArgumentException(usage());
                    long revision = Long.parseLong(arguments[4]);
                    if (revision < 1) throw new IllegalArgumentException("positive observed process revision required");
                    path = "workspaces/" + UUID.fromString(arguments[2]) + "/jobs/" + UUID.fromString(arguments[3]) + "/resolve-continuation";
                    method = "POST"; body = Map.of("expectedRevision", revision, "resolution", arguments[5]);
                }
                default -> throw new IllegalArgumentException(usage());
            }
            output.println(backend.runnerOperation(method, path, body));
            return 0;
        } catch (RuntimeException | java.io.IOException failure) {
            errors.println("Error: " + RavenrootCli.sanitizeForConsole(failure.getMessage()));
            return 1;
        }
    }
    private static String usage() {
        return "runner catalog [KIND:name:version]|availability|health|audit; runner publish <catalog-json-file>; "
                + "runner artifact <process> <job> <artifact>; runner workspace <process>; "
                + "runner stop-workspace <process> <workspace-node> <revision>; "
                + "runner cancel-job|reconcile-job <process> <job>; "
                + "runner resolve-job <process> <job> <revision> RESUME|ACKNOWLEDGE|ABANDON (requires reviewed graph state)";
    }
}
