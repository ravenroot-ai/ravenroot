package ai.ravenroot.cli;

import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.api.execution.ExecutionEngines;
import ai.ravenroot.api.application.AuthorizedRavenrootApplication;
import ai.ravenroot.api.programming.ArtifactLifecycleAuditSink;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.DefaultAuthorizationService;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;

public final class RavenrootCliMain {
    private RavenrootCliMain() {
    }

    public static void main(String[] args) {
        int exitCode;
        // API-05: --server/--token-file are global options, parsed and stripped before any
        // command dispatch -- including before the backup/restore interception below, since a global
        // option's presence must not depend on which command follows it.
        var parsed = GlobalOptions.parse(args);
        args = parsed.remainingArgs();
        // Backup/restore are intercepted here, before any engine or
        // RavenrootApplication is constructed, because they are not a RavenrootApplication use
        // case at all -- they operate directly on SqliteExecutionStore/SqliteStoreLocation
        // (adapter-local administration, see SqliteStoreLocation's own Javadoc) and restore
        // specifically requires the store to be offline, which starting an engine here would work
        // against rather than for.
        if (args.length >= 1 && ("backup".equals(args[0]) || "restore".equals(args[0])
                || "verify".equals(args[0]))) {
            System.exit(runBackupRestore(args));
            return;
        }
        // Validate is intercepted for the same reason and at the same
        // point: it is not a RavenrootApplication use case. It reads one local file against the
        // GraphML profile this binary implements, so an execution engine started underneath it could
        // only add ways for the command to fail for reasons that have nothing to do with the
        // document -- a validator that cannot run because Pekko did not start is not a validator.
        // It sits above the --server branch too: the document is local either way, and requiring a
        // token to check a file on disk would be a demand with nothing behind it.
        if (args.length >= 1 && "validate".equals(args[0])) {
            System.exit(GraphMlValidateCommand.run(args, System.out, System.err));
            return;
        }
        // Intercepted here for the same reason backup/restore are: provisioning and revoking an
        // embed registration is not a RavenrootApplication use case. It operates directly on the
        // durable registration store (adapter-local administration, see
        // SqliteEmbedRegistrationStore's own Javadoc) and there is deliberately no HTTP route for it
        // -- an administrative endpoint is excluded from the browser boundary. It sits above the
        // --server branch too: the store is a local file, and demanding
        // a server token to write it would be a demand with nothing behind it.
        if (args.length >= 1 && "embed-registration".equals(args[0])) {
            System.exit(EmbedRegistrationCommand.run(args, System.out, System.err));
            return;
        }
        // --server present means remote, by construction -- the embedded path below (engine,
        // DefaultRavenrootApplication, AuthorizedRavenrootApplication, telemetry) is entirely skipped
        // rather than merely unused, so it cannot silently run alongside a remote call by accident.
        if (parsed.serverUrl() != null) {
            System.exit(runRemote(parsed, args));
            return;
        }
        String engineId = System.getenv().getOrDefault("RAVENROOT_ENGINE", "pekko");
        try (var engine = ExecutionEngines.create(engineId, "ravenroot-cli")) {
            // Same operator-named node packages as the server, same prohibition — the
            // allowlist is deployment configuration, never graph content. Unset means the standard
            // catalog, unchanged.
            var monitor = new ExecutionMonitor();
            var application = embeddedApplication(engine, monitor, System.getenv());
            // Stated the way the server states it, but on stderr: the CLI's stdout is the command's
            // machine-readable output, and a diagnostic injected there would corrupt it for anyone
            // piping `ravenroot result <id>`. Same line, same spelling, appropriate stream.
            System.err.println("{\"event\":\"unknown-behavior-policy\",\"mode\":\""
                    + ai.ravenroot.core.runtime.UnknownBehaviorPolicy.describe(System.getenv()) + "\"}");
            var authorized = new AuthorizedRavenrootApplication(application,
                    new DefaultAuthorizationService(event -> System.err.printf(
                            "authorization action=%s allowed=%s request=%s%n",
                            event.action(), event.allowed(), event.requestId())),
                    lifecycleAudit(), true, AuthorizedRavenrootApplication.DEFAULT_EXECUTION_OWNERSHIP_LIMIT,
                    controlAudit());
            // The tenant asserted here is now the tenant executions are stored under. Before SEC-07 the
            // CLI authorized as "local" while DefaultRavenrootApplication persisted under "default", so
            // an execution was scoped to one tenant and recorded against another. Removing the
            // application-level constant is what makes the two agree by construction.
            var localBootstrap = new RequestContext("cli-" + java.util.UUID.randomUUID(), "local-cli",
                    PrincipalType.USER, "urn:ravenroot:cli", "local", java.util.Set.of(Role.PLATFORM_ADMIN),
                    java.util.Arrays.stream(AuthorizationAction.values()).filter(AuthorizationAction::available)
                            .map(AuthorizationAction::requiredScope)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()));
            // Disabled unless RAVENROOT_OTEL_ENABLED=true. Deliberately
            // NOT a shutdown hook here, unlike RavenrootServerMain: the CLI is one short-lived
            // process per invocation, and an OpenTelemetry batch span/metric processor's default
            // export interval (tens of seconds) would outlive the process before ever flushing --
            // installing the same way the long-running server does would silently lose every CLI
            // invocation's telemetry. close() below runs synchronously, in this thread, before
            // System.exit, and its shutdown() calls flush the batch processors before this method
            // returns.
            var telemetry = ai.ravenroot.observability.otel.TelemetrySupport.install(
                    ai.ravenroot.observability.otel.TelemetryConfiguration.fromEnvironment(System.getenv()),
                    monitor);
            try {
                exitCode = new RavenrootCli(authorized, localBootstrap, System.out, System.err).run(args);
            } finally {
                if (telemetry.isPresent()) {
                    try {
                        telemetry.get().close();
                    } catch (Exception ignored) {
                        // Best-effort: a failed flush must not fail the CLI invocation it is
                        // reporting on.
                    }
                }
            }
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * API-05. The embedded path (engine, node packages, {@code DefaultRavenrootApplication},
     * telemetry) is entirely absent from this method -- not merely unused -- so a remote invocation
     * can never accidentally also stand up a local engine.
     */
    /**
     * Composes the embedded application exactly as {@link #main} does.
     *
     * <p>Extracted so the wiring can be <em>tested</em> rather than asserted. A test that built its
     * own {@code DefaultRavenrootApplication} would prove the application honours a policy -- which it
     * already did -- while this method could quietly stop passing one and stay green. That is the same
     * vacuity that made the server's first selection test meaningless, one layer up, so it is closed
     * the same way: production and the test call the identical code.</p>
     *
     * <p>{@code environmentVariables} is a parameter rather than a {@code System.getenv()} call for
     * the same reason: a seam that reads the real environment is a seam a test cannot drive.</p>
     */
    static DefaultRavenrootApplication embeddedApplication(
            ai.ravenroot.api.execution.ExecutionEngine engine, ExecutionMonitor monitor,
            java.util.Map<String, String> environmentVariables) {
        // Same operator-named node packages as the server, same prohibition -- the allowlist
        // is deployment configuration, never graph content. Unset means the standard catalog.
        var environment = ai.ravenroot.core.runtime.BehaviorEnvironment.safeDefaults();
        var behaviors = ai.ravenroot.core.runtime.NodePackages.registerAll(
                ai.ravenroot.core.runtime.BehaviorRegistry.standard(environment),
                ai.ravenroot.core.runtime.NodePackageLoader.fromEnvironment(environmentVariables));
        // The CLI is a composition root too, and running a graph from it is the shortest path
        // anyone takes to try this product -- shorter than starting a server. A mode reachable only
        // through the server would be a mode a first-time user never finds. Same variable and same
        // parser as RavenrootServerMain, both owned by core, so the two roots cannot drift into two
        // spellings of one setting.
        return new DefaultRavenrootApplication(engine, monitor, behaviors, environment.artifacts(),
                environment.programRuntime(),
                ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids(), null, 0,
                ai.ravenroot.core.runtime.UnknownBehaviorPolicy.fromEnvironment(environmentVariables));
    }

    private static int runRemote(GlobalOptions options, String[] commandArgs) {
        try {
            String token = ai.ravenroot.cli.remote.CliToken.resolve(System.getenv(), options.tokenFile());
            // 10s connect/request timeout: generous enough for a cold TLS handshake to a remote host,
            // bounded enough that a CLI invocation never hangs indefinitely on a stalled connection.
            var backend = new ai.ravenroot.cli.remote.RemoteBackend(
                    java.net.URI.create(options.serverUrl()).normalize(), token, java.time.Duration.ofSeconds(10));
            return new RavenrootCli(backend, System.out, System.err).run(commandArgs);
        } catch (RuntimeException | java.io.IOException failure) {
            System.err.println("Error: " + RavenrootCli.sanitizeForConsole(failure.getMessage()));
            return 1;
        }
    }

    private static int runBackupRestore(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: ravenroot " + args[0] + " <directory>");
            return 2;
        }
        try {
            var command = new BackupRestoreCommand(System.out, System.err);
            var directory = java.nio.file.Path.of(args[1]);
            if ("verify".equals(args[0])) {
                // Verification is bundle-local and must not depend on ambient live-store paths.
                return command.verify(directory);
            }
            var configuration = BackupRestoreConfiguration.fromEnvironment(System.getenv());
            return switch (args[0]) {
                case "backup" -> command.backup(configuration, directory);
                default -> command.restore(configuration, directory);
            };
        } catch (RuntimeException invalidConfiguration) {
            // Path parser/provider diagnostics can echo the raw argument or environment value.
            System.err.println("Error: recovery command refused: INVALID_CONFIGURATION");
            return 2;
        }
    }

    private static ArtifactLifecycleAuditSink lifecycleAudit() {
        return event -> {
            System.err.printf("artifact-lifecycle action=%s disposition=%s request=%s%n",
                    event.action(), event.disposition(), event.requestId());
            if (System.err.checkError()) {
                throw new IllegalStateException("artifact lifecycle audit output failed");
            }
        };
    }

    /** API-02: the embedded CLI's own stderr control-plane audit, matching {@link #lifecycleAudit()}'s
     * shape and its fail-closed check (a write that failed must not read back as an unaudited success). */
    private static ai.ravenroot.api.application.ExecutionControlAuditSink controlAudit() {
        return event -> {
            System.err.printf("execution-control action=%s disposition=%s resource=%s/%s request=%s%n",
                    event.action(), event.disposition(), event.resourceType(), event.resourceId(),
                    event.requestId());
            if (System.err.checkError()) {
                throw new IllegalStateException("execution control audit output failed");
            }
        };
    }
}
