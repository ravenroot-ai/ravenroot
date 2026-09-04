package ai.ravenroot.server;

import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.ai.AgentRuntimeRegistry;
import ai.ravenroot.core.ai.ModelProviderRegistry;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.api.programming.ArtifactProvenanceVerifier;
import ai.ravenroot.api.programming.ArtifactRegistry;
import ai.ravenroot.persistence.sqlite.SqliteArtifactRegistry;
import ai.ravenroot.core.security.AllowlistToolPolicy;
import ai.ravenroot.core.security.EnvironmentCredentialResolver;
import ai.ravenroot.core.security.OutboundHttpPolicy;
import ai.ravenroot.core.security.egress.EgressAddressGuard;
import ai.ravenroot.core.security.egress.ReservedNetworkPolicy;
import ai.ravenroot.core.security.ProviderCredentialResolver;
import ai.ravenroot.api.execution.ExecutionEngines;
import ai.ravenroot.api.programming.ProgramRuntime;
import ai.ravenroot.core.audit.FileAuditTrail;
import ai.ravenroot.programming.graalvm.GraalVmProgramRuntime;
import ai.ravenroot.api.plugin.PluginActivationEvent;
import ai.ravenroot.plugin.bundle.PluginBundleException;
import ai.ravenroot.plugin.bundle.PluginManifest;
import ai.ravenroot.server.audit.AuditTrailArtifactLifecycleSink;
import ai.ravenroot.core.audit.AuditTrailAuthorizationSink;
import ai.ravenroot.server.audit.AuditTrailExecutionControlSink;
import ai.ravenroot.server.audit.AuditTrailPluginActivationSink;
import ai.ravenroot.server.audit.AuditTrailRateLimitSink;
import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.server.plugin.DeploymentGlobalTenantCredentials;
import ai.ravenroot.server.plugin.EnvironmentNodePackageServiceGrants;
import ai.ravenroot.server.plugin.PluginActivationDiagnostics;
import ai.ravenroot.server.plugin.PluginActivationOrchestrator;
import ai.ravenroot.server.ratelimit.RateLimitConfiguration;
import ai.ravenroot.server.ratelimit.RateLimiter;
import ai.ravenroot.server.ratelimit.TrustedProxyConfiguration;
import ai.ravenroot.server.security.AuthenticationConfiguration;
import ai.ravenroot.server.security.HttpSecurityConfiguration;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.function.IntConsumer;
import java.util.Map;

public final class RavenrootServerMain {
    private RavenrootServerMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        launch(() -> run(args), System::exit);
    }

    static void launch(Startup startup, IntConsumer exit) throws InterruptedException {
        try {
            startup.run();
        } catch (PluginStartupRefused refused) {
            // The refusal was already rendered and audited. This boundary is deliberately outside
            // run's audit/store ownership scopes, so System.exit cannot skip their close/checkpoint.
            exit.accept(1);
        } catch (PackagedEmbedStartupRefused refused) {
            exit.accept(1);
        }
    }

    private static void run(String[] args) throws InterruptedException {
        refuseUnsupportablePackagedEmbed(System.getenv());
        int port = Integer.parseInt(System.getenv().getOrDefault("RAVENROOT_PORT", "8080"));
        String engineId = System.getenv().getOrDefault("RAVENROOT_ENGINE", "pekko");
        String uiPath = System.getenv().getOrDefault("RAVENROOT_UI_DIR", "").trim();
        // The reserved-network exception list is operator-only and read exactly here,
        // at the composition root, with the same shape and the same authority as the host allowlist
        // above it. No graph, plugin, payload or request can widen it. Installed before anything
        // resolves a name, because the JVM-wide resolver filters from its first lookup onward.
        EgressAddressGuard.configure(ReservedNetworkPolicy.fromCommaSeparatedExceptions(
                System.getenv("RAVENROOT_EGRESS_RESERVED_EXCEPTIONS")));
        var authentication = AuthenticationConfiguration.fromEnvironment(System.getenv(), port);
        var httpSecurity = HttpSecurityConfiguration.fromEnvironment(System.getenv(), port);
        var artifactLifecycle = ArtifactLifecycleConfiguration.fromEnvironment(System.getenv());
        // This lease is the offline-maintenance authority shared with backup/restore. It is
        // acquired before the audit trail is opened and retained until both stores are closed.
        var executionStoreConfiguration = ai.ravenroot.server.persistence.ExecutionStoreConfiguration
                .fromEnvironment(System.getenv());
        var executionStoreOwner = ai.ravenroot.server.persistence.ExecutionStoreBootstrap.openOwned(
                executionStoreConfiguration, java.time.Clock.systemUTC());
        try (var startupGuard = executionStoreOwner.startupGuard()) {
        var engine = ExecutionEngines.create(engineId, "ravenroot-server");
        ProgramRuntime programRuntime = switch (System.getenv().getOrDefault("RAVENROOT_PROGRAM_RUNTIME", "graalvm")) {
            case "graalvm" -> GraalVmProgramRuntime.fromEnvironment();
            case "disabled" -> new DisabledProgramRuntime();
            default -> throw new IllegalArgumentException("Unknown program runtime: "
                    + System.getenv("RAVENROOT_PROGRAM_RUNTIME"));
        };
        // Reading the mode and announcing it are the same call, deliberately -- see
        // artifactProvenance. Refused values throw from here, before the HTTP listener exists and
        // before any artifact can be submitted, which is the whole point of validating the opt-in at
        // startup instead of at redemption.
        var artifactProvenance = artifactProvenance(System.getenv());
        // Credentials an author entered from the interface. Opened before the behavior
        // environment because the environment's resolver reads it -- that is the whole of "no restart
        // for a new credential": a value written through POST /v1/credentials is in this database, and
        // the next execution resolves it without the process being told anything.
        var userCredentials =
                ai.ravenroot.server.credential.SqliteUserCredentialStore.fromEnvironment(System.getenv());
        // The author-entered store first, then the operator's environment bindings. The two
        // namespaces cannot collide -- a minted reference has a shape an operator's never has
        // -- so this order decides nothing about precedence; see CredentialResolverChain.
        //
        // Hoisted out of the BehaviorEnvironment argument list because a second consumer now
        // exists. A node package's managed HTTP channel resolves credentials through THIS chain,
        // adapted by DeploymentGlobalTenantCredentials, and not through a second path of its own.
        var credentialResolver = new ai.ravenroot.server.credential.CredentialResolverChain(
                userCredentials::resolve,
                new ProviderCredentialResolver(new EnvironmentCredentialResolver()));
        // Program source is a deployable capability, not ephemeral editor state. The server
        // therefore has no in-memory fallback: a missing/unwritable SQLite directory is a startup
        // failure instead of a process that appears healthy then forgets approvals on restart.
        var durableArtifacts = SqliteArtifactRegistry.openUnder(Path.of(
                System.getenv().getOrDefault("RAVENROOT_ARTIFACT_STORE_DIR",
                        "/opt/ravenroot/data/artifact-store")),
                artifactProvenance.verifier());
        var environment = new BehaviorEnvironment(new ModelProviderRegistry(), new AgentRuntimeRegistry(),
                durableArtifacts, programRuntime,
                credentialResolver,
                AllowlistToolPolicy.fromCommaSeparated(System.getenv("RAVENROOT_ALLOWED_TOOLS")),
                // Host, port and response ceiling are all operator-only, all read here.
                OutboundHttpPolicy.fromCommaSeparated(
                        System.getenv("RAVENROOT_HTTP_ALLOWED_HOSTS"),
                        System.getenv("RAVENROOT_HTTP_ALLOWED_PORTS"),
                        byteCeiling("RAVENROOT_HTTP_MAX_RESPONSE_BYTES"),
                        byteCeiling("RAVENROOT_HTTP_MAX_REQUEST_BYTES")));
        // Security audit (access, decisions, artifact/version lifecycle) is durable and
        // tamper-evident, separate from the operational stdout logs the server also writes. See ADR
        // 0013 and ai.ravenroot.api.audit.AuditTrail for what this does and does not defend against.
        String auditDir = System.getenv().getOrDefault("RAVENROOT_AUDIT_DIR", "./data/audit");
        var auditTrail = new FileAuditTrail(Path.of(auditDir), java.time.Clock.systemUTC(),
                Duration.ofHours(24));
        try (auditTrail) {
        var rateLimiter = new RateLimiter(RateLimitConfiguration.fromEnvironment(System.getenv()),
                TrustedProxyConfiguration.fromEnvironment(System.getenv()),
                new AuditTrailRateLimitSink(auditTrail));
        // Third-party node types, trusted because THIS deployment named them. The class
        // names come from the operator's environment and from nowhere else — no graph, payload or
        // request can name a class to load, and there is no classpath scanning. With the variable
        // unset this is exactly the standard catalog, which is the shipped default.
        //
        // Plugin bundles baked into the image and named in RAVENROOT_ENABLED_PLUGINS
        // are merged into the same catalog here. Presence alone never activates one:
        // PluginActivationOrchestrator.register only ever touches a bundle whose id is in that
        // allowlist. Every failure it can throw — an unknown id, an invalid or tampered installed
        // bundle, an incompatible SDK contract, a missing class or dependency, a duplicate behavior
        // or package id — must prevent startup with a diagnostic that names the actionable thing.
        // The console message is written first, unconditionally; the audit write is attempted
        // afterward in its own try/catch, because FileAuditTrail.append() itself can throw and must
        // never be allowed to replace the real diagnosis with an unrelated audit failure. See
        // ravenroot-plugin-bundle's DESIGN.md, "Where detail goes".
        var pluginActivationAuditSink = new AuditTrailPluginActivationSink(auditTrail);
        ai.ravenroot.api.persistence.ExecutionStore approvalStore = executionStoreOwner.store();
        ai.ravenroot.core.approval.ToolApprovalService toolApprovals = approvalStore != null
                && approvalStore.supports(ai.ravenroot.api.persistence.StoreCapability.TOOL_APPROVALS)
                ? new ai.ravenroot.core.approval.ToolApprovalService(
                        approvalStore, java.time.Clock.systemUTC()) : null;
        ai.ravenroot.core.humantask.HumanTaskService humanTasks = approvalStore != null
                && approvalStore.supports(ai.ravenroot.api.persistence.StoreCapability.DURABLE)
                && approvalStore.supports(ai.ravenroot.api.persistence.StoreCapability.HUMAN_TASKS)
                ? new ai.ravenroot.core.humantask.HumanTaskService(
                        approvalStore, java.time.Clock.systemUTC()) : null;
        ai.ravenroot.core.approval.ToolApprovalSettings toolApprovalSettings = toolApprovals == null
                ? null : ai.ravenroot.server.approval.ToolApprovalConfiguration
                        .fromEnvironment(System.getenv());
        PluginActivationOrchestrator.Registration registration = registerNodePackagesOrRefuse(
                environment, credentialResolver, pluginActivationAuditSink,
                new ai.ravenroot.server.audit.AuditTrailToolCallSink(auditTrail),
                toolApprovals, toolApprovalSettings, humanTasks);
        PluginActivationOrchestrator.Registered registered = registration.registered();
        var behaviors = registered.registry();
        // Validate all enabled package declarations before either application deployment state or the
        // HTTP listener exists. This process has no distributed lease coordinator, so replicas >1
        // are refused by configuration instead of pretending process memory coordinates pods.
        RavenrootServerStartup.Prepared serverStartup;
        try {
            serverStartup = RavenrootServerStartup.prepare(registration.packages(), System.getenv());
        } catch (RuntimeException validationFailure) {
            registered.activation().close();
            throw validationFailure;
        }
        for (PluginManifest manifest : registered.activation().manifests()) {
            // Startup inventory (id/version/digest, no secrets) is audited as ACTIVATED for the same
            // reason a REFUSED outcome is: the durable record must identify what actually ran, not
            // only what was attempted.
            System.out.println("plugin activated: id=" + manifest.id() + " version=" + manifest.version()
                    + " sdkContract=" + manifest.sdkContract() + " mainArtifactSha256="
                    + manifest.mainArtifact().sha256Hex());
            try {
                pluginActivationAuditSink.record(new PluginActivationEvent(Instant.now(), manifest.id(),
                        PluginActivationEvent.Outcome.ACTIVATED, "ACTIVATED",
                        "version=" + manifest.version() + ";sdkContract=" + manifest.sdkContract()
                                + ";mainArtifactSha256=" + manifest.mainArtifact().sha256Hex(),
                        java.util.UUID.randomUUID().toString()));
            } catch (RuntimeException auditFailed) {
                // A successful activation whose audit record could not be written is still a
                // successful activation: the inventory line above already reached the console.
                System.err.println("(plugin activation audit record could not be written: "
                        + PluginActivationDiagnostics.neutralize(String.valueOf(auditFailed.getMessage())) + ")");
            }
        }
        var monitor = new ExecutionMonitor();
        // The deployment-admission contract: the per-pod cap on active deployments. ravenroot-server reads the
        // environment (ai.ravenroot.server.deployment.DeploymentCapConfiguration); core receives the
        // plain int and never reads it itself -- see that class's Javadoc for why the split is
        // deliberate.
        var deploymentCap = ai.ravenroot.server.deployment.DeploymentCapConfiguration.fromEnvironment(
                System.getenv());
        // The composition root chooses the adapter. Core names only the port; the concrete adapter
        // appears here and nowhere else so the primary path has a store to write through.
        ai.ravenroot.api.persistence.ExecutionStore executionStore =
                executionStoreOwner.store();
        // The composition root also chooses the SEC-09 mode. Core holds the seam and deliberately no
        // configuration channel, so the variable is read here and the decision travels inward as a
        // parameter. Pass-through remains the default for the reasons in UnknownBehaviorConfiguration.
        var unknownBehavior = UnknownBehaviorConfiguration.fromEnvironment(System.getenv());
        var graphExecutionLimits = ai.ravenroot.core.runtime.GraphExecutionLimits.fromEnvironment(System.getenv());
        var executionIdentities = ai.ravenroot.api.application.ExecutionIdentitySource.randomUuids();
        var application = new DefaultRavenrootApplication(engine, monitor,
                behaviors, environment.artifacts(), environment.programRuntime(),
                executionIdentities, executionStore,
                deploymentCap.maxActiveDeployments(), unknownBehavior.policy(),
                executionStoreOwner.graphDefinitionStore(), toolApprovals, humanTasks,
                graphExecutionLimits);
        final ai.ravenroot.server.approval.ToolApprovalRecoveryDriver approvalRecovery;
        if (toolApprovals == null && humanTasks == null) {
            approvalRecovery = null;
        } else {
            var recoveryConfiguration = ai.ravenroot.server.approval.ToolApprovalRecoveryConfiguration
                    .fromEnvironment(System.getenv());
            String recoveryWorker = "ravenroot-durable-decision-" + java.util.UUID.randomUUID();
            var dispatchers = new java.util.ArrayList<ai.ravenroot.core.recovery.RecoveryDispatcher>();
            if (toolApprovals != null) {
                toolApprovals.restrictRecoveryTenants(java.util.Set.copyOf(recoveryConfiguration.tenantIds()));
                var continuationExecutor = new ai.ravenroot.core.approval.PinnedGraphToolApprovalContinuationExecutor(
                        executionStoreOwner.graphDefinitionStore(), executionStore, toolApprovals, humanTasks,
                        engine, behaviors, monitor, executionIdentities, recoveryWorker,
                        recoveryConfiguration.leaseTtl(), graphExecutionLimits);
                dispatchers.add(new ai.ravenroot.core.approval.ToolApprovalHandlerDispatcher(
                        executionStore, toolApprovals, environment.toolPolicy(), continuationExecutor));
            }
            if (humanTasks != null) {
                humanTasks.restrictRecoveryTenants(java.util.Set.copyOf(recoveryConfiguration.tenantIds()));
                var continuationExecutor = new ai.ravenroot.core.humantask.PinnedGraphHumanTaskContinuationExecutor(
                        executionStoreOwner.graphDefinitionStore(), executionStore, humanTasks, toolApprovals,
                        engine, behaviors, monitor, executionIdentities, recoveryWorker,
                        recoveryConfiguration.leaseTtl(), graphExecutionLimits);
                dispatchers.add(new ai.ravenroot.core.humantask.HumanTaskHandlerDispatcher(
                        executionStore, humanTasks, continuationExecutor));
            }
            var recoveryService = new ai.ravenroot.core.recovery.ExecutionRecoveryService(
                    executionStore, recoveryConfiguration.tenantIds(), recoveryWorker,
                    recoveryConfiguration.batchLimit(), recoveryConfiguration.leaseTtl(),
                    ai.ravenroot.core.recovery.RepeatabilityDeclarations.NONE_DECLARED,
                    new ai.ravenroot.core.recovery.CompositeRecoveryDispatcher(dispatchers),
                    graphExecutionLimits.maxRecoveryDeliveriesPerAttempt());
            approvalRecovery = new ai.ravenroot.server.approval.ToolApprovalRecoveryDriver(
                    recoveryService, recoveryConfiguration.interval());
        }
        application.configureArtifactDualControl(artifactLifecycle.dualControl());
        serverStartup.installInto(application::installManagedIngress);
        // Stated at startup rather than left to be discovered from a run's outcome: an operator who
        // selected fail-closed must be able to confirm it took effect without submitting a graph.
        System.out.println("{\"event\":\"unknown-behavior-policy\",\"mode\":\""
                + unknownBehavior.describe() + "\"}");
        // One configuration object for every readiness/drain knob (store-check
        // timeout, drain grace period, HTTP stop delay), read once and shared by the gate below and
        // by the shutdown sequence further down.
        var readinessConfiguration = ai.ravenroot.server.readiness.ReadinessConfiguration.fromEnvironment(
                System.getenv());
        // Both required durable dependencies contribute to readiness. The execution-store probe is
        // omitted only under the explicit disabled configuration, preserving the supported ephemeral
        // mode without pretending a nonexistent store can be checked.
        var storeLiveness = executionStore == null
                ? ai.ravenroot.server.readiness.StoreLivenessCheck.none()
                : ai.ravenroot.server.readiness.StoreLivenessCheck.executionStore(executionStore);
        var readinessGate = new ai.ravenroot.server.readiness.ReadinessGate(
                () -> application.status().state(),
                ai.ravenroot.server.readiness.StoreLivenessCheck.all(
                        () -> auditTrail.head("__ravenroot_readiness_probe__"), storeLiveness,
                        durableArtifacts::checkHealth),
                () -> serverStartup.managedIngressEnabled()
                        ? java.util.List.of(new ai.ravenroot.server.readiness.DependencyStatus("managed-ingress", true,
                                serverStartup.readinessDetail()))
                        : java.util.List.of(),
                readinessConfiguration);
        // The two rejection-detail sinks route into the same durable audit trail as
        // everything else here rather than defaulting to RavenrootServer's stdout loggers -- see
        // AuditTrailGraphMlRejectionSink's Javadoc for why this satisfies rather than reopens FIX-03
        // and API-01's "server-side sink only" constraint on GraphMlRejectionDetail/PayloadException.
        // AuditTrailExecutionSink is a fifth subscriber alongside the stdout logger
        // and whatever TelemetrySupport installs below -- see its own Javadoc for exactly which four
        // ExecutionEventTypes it admits and why the other six stay off the chain.
        var authorization = new ai.ravenroot.api.security.DefaultAuthorizationService(
                new AuditTrailAuthorizationSink(auditTrail));
        // The durable operator authority the packaged process was missing under the relevant contract. It is a
        // local SQLite file opened here and nowhere else, and provision/revoke reach it only through
        // the operator CLI's reference monitor -- there is no HTTP administration route. Default-off:
        // with the embed disabled this opens
        // nothing and registers no route. The supportability of the configuration was already decided
        // by EmbedStartupCheck at the top of run(), so reaching this line with the embed enabled means
        // the directory is set, the replica count is one and the acknowledgement is present.
        // Effectively final, and null when the embed is off, so the shutdown hook below can close it
        // on the same line userCredentials is closed on. Both are their own database with their own
        // lifecycle, neither is a table in the execution store, and neither closes inside its scope.
        final var embedRegistrations = "true".equals(System.getenv("RAVENROOT_EMBED_ENABLED"))
                ? openEmbedRegistrationStore()
                : null;
        var embedConfiguration = embedRegistrations == null
                ? ai.ravenroot.server.embed.EmbedBrowserConfiguration.disabled()
                : ai.ravenroot.server.embed.EmbedBrowserConfiguration.fromEnvironment(
                        System.getenv(),
                        new ai.ravenroot.api.embed.AuthorizedEmbedSessionCreation(authorization,
                                embedRegistrations),
                        embedRegistrations,
                        new ai.ravenroot.api.embed.AuthorizedEmbedGraphProjection(authorization,
                                embedRegistrations),
                        new ai.ravenroot.server.audit.AuditTrailEmbedSecuritySink(auditTrail),
                        java.time.Clock.systemUTC());
        if (embedRegistrations != null) {
            // Stated at startup rather than left to be discovered from a refused session, the same
            // reason the unknown-behavior policy is announced above: an operator who enabled the
            // embed must be able to confirm which file the authority is reading.
            System.out.println("{\"event\":\"embed-browser\",\"enabled\":true,\"registrationStore\":\""
                    + embedRegistrations.databaseFile() + "\"}");
        }
        // The assistant's real stores, chosen here and nowhere else. Before this line every
        // path reached AssistantService.fromEnvironment(System.getenv()), whose one-argument overload
        // passes null for both the consent register and the token store -- so a deployment naming a
        // network provider refused to start, and an OAuth deployment had nowhere to put a redeemed
        // token. See AssistantComposition for which token-store shape this composition may bind and
        // why that is not a free choice.
        ai.ravenroot.server.assistant.AssistantComposition assistantComposition;
        try {
            assistantComposition =
                    ai.ravenroot.server.assistant.AssistantComposition.fromEnvironment(System.getenv());
        } catch (RuntimeException | Error assistantFailure) {
            userCredentials.close();
            application.close();
            registered.activation().close();
            throw assistantFailure;
        }
        assistantComposition.startupNotes().forEach(note ->
                System.err.println("(assistant: " + note + ")"));
        // CORE-INGRESS-01: collect only declarations from packages the operator already
        // enabled and validate the whole set before this listener factory is invoked. The handle is
        // package-private and hands only an attenuated route authority to graph sources: an installed
        // jar or graph can neither obtain the server nor widen its listener or authentication policy.
        RavenrootServerStartup.Handle startupHandle;
        try {
            startupHandle = serverStartup.bind(() -> {
                var server = new RavenrootServer(application,
                        authentication.bindAddress(), uiPath.isEmpty() ? null : Path.of(uiPath),
                        artifactLifecycle.dualControl(), authentication.authenticator(), httpSecurity,
                        java.time.Clock.systemUTC(), authorization, rateLimiter,
                        new AuditTrailArtifactLifecycleSink(auditTrail), readinessGate,
                        readinessConfiguration.httpStopDelay(),
                        new ai.ravenroot.server.audit.AuditTrailGraphMlRejectionSink(auditTrail),
                        new ai.ravenroot.server.audit.AuditTrailPayloadRejectionSink(auditTrail),
                        new ai.ravenroot.server.audit.AuditTrailExecutionSink(auditTrail),
                        readinessConfiguration.drainGracePeriod(),
                        new AuditTrailExecutionControlSink(auditTrail),
                        assistantComposition.service(),
                        embedConfiguration, userCredentials);
                if (toolApprovals != null) {
                    server.installToolApprovals(toolApprovals, approvalRecovery::sweepTenant);
                }
                if (humanTasks != null) {
                    server.installHumanTasks(humanTasks, approvalRecovery::sweepTenant);
                }
                return new RavenrootServerStartup.Listener() {
                    @Override public void install(
                            ai.ravenroot.server.ingress.ManagedIngressRegistry ingress) {
                        server.installManagedIngress(ingress);
                    }

                    @Override public void start() {
                        server.start();
                    }

                    @Override public void gracefulShutdown() {
                        GracefulShutdown.run(engine, server, readinessConfiguration.drainGracePeriod());
                    }

                    @Override public void close() {
                        server.close();
                    }
                };
            });
        } catch (RuntimeException | Error startupFailure) {
            userCredentials.close();
            closeEmbedRegistrations(embedRegistrations);
            assistantComposition.close();
            application.close();
            registered.activation().close();
            throw startupFailure;
        }
        // Disabled unless RAVENROOT_OTEL_ENABLED=true: install() returns
        // empty before constructing any OpenTelemetry SDK type or calling monitor.subscribe at all.
        java.util.Optional<AutoCloseable> telemetry;
        try {
            telemetry = ai.ravenroot.observability.otel.TelemetrySupport.install(
                    ai.ravenroot.observability.otel.TelemetryConfiguration.fromEnvironment(System.getenv()), monitor);
        } catch (RuntimeException | Error telemetryFailure) {
            startupHandle.close();
            userCredentials.close();
            assistantComposition.close();
            registered.activation().close();
            throw telemetryFailure;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // GracefulShutdown.run replaces the previous
            // server.close(); ...; engine.close() ordering: engine.drain() now runs first (DRAINING
            // becomes observable through /ready immediately, while the listener is still fully up),
            // then a grace period, then server.close() with a real stop delay (in-flight HTTP work
            // is given time to finish), then engine.close() to finalize. See GracefulShutdown's own
            // Javadoc for the full reasoning.
            try {
                startupHandle.gracefulShutdown();
            } finally {
                if (approvalRecovery != null) approvalRecovery.close();
                try {
                    registered.activation().close();
                } finally {
                    try {
                        auditTrail.close();
                    } finally {
                        try {
                            telemetry.ifPresent(closeable -> {
                                try {
                                    closeable.close();
                                } catch (Exception ignored) {
                                    // Best-effort on shutdown: the process is exiting either way.
                                }
                            });
                        } finally {
                            try {
                                // After gracefulShutdown, so no request thread can still be
                                // reading the consent register when its connection closes. It is
                                // its own database with its own lifecycle -- see
                                // SqliteAssistantConsentStore on why it is not a table in the
                                // execution store -- so it closes on its own line rather than
                                // inside that owner's scope.
                                assistantComposition.close();
                            } finally {
                                try {
                                    // After the drain, for the same reason the consent register
                                    // is -- no request thread may still be reading it -- and on its
                                    // own line because it is its own database with its own lifecycle.
                                    userCredentials.close();
                                } finally {
                                    try {
                                        // Same placement and same reason. Every commit was
                                        // already fsynced, so this is tidiness rather than
                                        // durability -- but a connection left open past the drain
                                        // is one a later reader has to reason about.
                                        closeEmbedRegistrations(embedRegistrations);
                                    } finally {
                                    // Application/server drain completes inside GracefulShutdown and
                                    // the audit trail closes before the store is checkpointed and its
                                    // lease is released.
                                    executionStoreOwner.close();
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }));
        try {
            startupHandle.start();
            if (approvalRecovery != null) approvalRecovery.start();
        } catch (RuntimeException | Error startFailure) {
            if (approvalRecovery != null) approvalRecovery.close();
            userCredentials.close();
            closeEmbedRegistrations(embedRegistrations);
            assistantComposition.close();
            registered.activation().close();
            telemetry.ifPresent(closeable -> {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    // Preserve the listener startup failure as the primary diagnosis.
                }
            });
            throw startFailure;
        }
        startupGuard.transferToShutdownHook();
        System.out.println("Ravenroot server and UI listening on http://"
                + authentication.bindAddress().getAddress().getHostAddress() + ":" + port
                + " (program runtime: " + programRuntime.id() + ", authentication: "
                + authentication.mode() + ")");
        new CountDownLatch(1).await();
        }
        }
    }

    /**
     * Deployment-provided replica count; malformed values fail closed instead of silently assuming one.
     *
     * @deprecated The parser lives in {@link ReplicaCount} because two parsers were reading
     *     two different variable names, and the embed guard was reading the one no deployment sets.
     *     Retained as a delegating alias only so callers outside this class keep compiling.
     */
    @Deprecated
    static int replicaCount(Map<String, String> environment) {
        return ReplicaCount.fromEnvironment(environment);
    }

    /**
     * Refuses the packaged embed before the listener binds when its configuration is unsupportable.
     *
     * <p>This replaced an unconditional refusal. Under the relevant contract every {@code RAVENROOT_EMBED_ENABLED=true}
     * was refused with one fixed code because no operator authority existed at all; now the answer
     * depends on the configuration in front of it, and the case that still refuses — no durable
     * authority configured — refuses with the same code and a detail saying which variable to set.</p>
     */
    static void refuseUnsupportablePackagedEmbed(Map<String, String> environment) {
        var refusal = ai.ravenroot.server.embed.EmbedStartupCheck.evaluate(environment);
        if (refusal == null) return;
        refuseEmbedStartup(refusal);
    }

    private static void refuseEmbedStartup(ai.ravenroot.server.embed.EmbedStartupCheck.Refusal refusal) {
        System.err.println(refusal.diagnostic());
        throw new PackagedEmbedStartupRefused();
    }

    /**
     * Opens the durable embed registration authority, or refuses startup before the listener binds.
     *
     * <p>{@link ai.ravenroot.server.embed.EmbedStartupCheck} has already decided that the directory
     * is configured, the replica count is one and the single-process limit is acknowledged, so the
     * only failure left here is the filesystem or the database itself. The store's own exception
     * carries the path — which is operator-supplied configuration, not a secret — but the cause chain
     * is not propagated into the diagnostic: what an operator can act on is which stage failed.</p>
     */
    private static ai.ravenroot.persistence.sqlite.SqliteEmbedRegistrationStore openEmbedRegistrationStore() {
        try {
            return ai.ravenroot.persistence.sqlite.SqliteEmbedRegistrationStore.openUnder(
                    Path.of(System.getenv(
                            ai.ravenroot.server.embed.EmbedStartupCheck.DIRECTORY_VARIABLE).trim()),
                    java.time.Clock.systemUTC(),
                    ai.ravenroot.api.embed.EmbedProjectionBudget.DEFAULTS);
        } catch (RuntimeException unopenable) {
            refuseEmbedStartup(new ai.ravenroot.server.embed.EmbedStartupCheck.Refusal(
                    "EMBED_REGISTRATION_STORE_UNAVAILABLE",
                    "the embed registration store could not be opened"));
            throw new AssertionError("unreachable: the refusal above always throws", unopenable);
        }
    }

    /** Null-safe because the embed is default-off and the store is only opened when it is enabled. */
    private static void closeEmbedRegistrations(
            ai.ravenroot.persistence.sqlite.SqliteEmbedRegistrationStore store) {
        if (store != null) store.close();
    }

    static final class PackagedEmbedStartupRefused extends RuntimeException {
        private PackagedEmbedStartupRefused() {
            super("packaged embed composition unavailable", null, false, false);
        }
    }

    /**
     * Resolves and activates plugin bundles, merged with {@code NodePackageLoader}'s operator-named
     * classes. On failure: prints the sanitized console message unconditionally and first, attempts
     * an audit write that can fail without suppressing that message, then propagates a payload-free
     * refusal to {@link #launch(Startup, IntConsumer)}. That outer boundary exits only after every
     * owned startup scope has unwound.
     *
     * <p>The operator's per-package service grants are composed here, inside the same try, so
     * an unreadable grant reaches the operator through the identical console-then-audit path as any
     * other activation refusal instead of escaping as a bare stack trace. Reading them is what makes
     * a granted capability reachable at all; with no grant variable set this composes
     * {@code NodePackageServiceRegistry.empty()} and registration is exactly what it was.</p>
     */
    private static PluginActivationOrchestrator.Registration registerNodePackagesOrRefuse(
            BehaviorEnvironment environment, CredentialResolver credentials,
            AuditTrailPluginActivationSink auditSink,
            ai.ravenroot.api.security.ToolCallAuditSink toolAuditSink,
            ai.ravenroot.core.approval.ToolApprovalService toolApprovals,
            ai.ravenroot.core.approval.ToolApprovalSettings toolApprovalSettings,
            ai.ravenroot.core.humantask.HumanTaskService humanTasks) {
        try {
            var services = EnvironmentNodePackageServiceGrants.fromEnvironment(System.getenv(),
                    new DeploymentGlobalTenantCredentials(credentials), environment.toolPolicy(),
                    toolAuditSink, toolApprovals, toolApprovalSettings);
            return PluginActivationOrchestrator.registerWithInventory(
                    BehaviorRegistry.standard(environment,
                            ai.ravenroot.api.publication.PublicationPolicyResolver.none(),
                            ai.ravenroot.api.publication.PublicationAuditSink.noop(), humanTasks),
                    System.getenv(), services);
        } catch (RuntimeException activationFailed) {
            var diagnosis = PluginActivationDiagnostics.diagnose(activationFailed);
            System.err.println(diagnosis.consoleMessage());
            try {
                auditSink.record(new PluginActivationEvent(Instant.now(), pluginIdFrom(activationFailed),
                        PluginActivationEvent.Outcome.REFUSED, diagnosis.reasonToken(), diagnosis.auditDetail(),
                        diagnosis.incidentId()));
            } catch (RuntimeException auditFailed) {
                System.err.println("(plugin activation audit record could not be written: "
                        + PluginActivationDiagnostics.neutralize(String.valueOf(auditFailed.getMessage())) + ")");
            }
            throw new PluginStartupRefused();
        }
    }

    @FunctionalInterface
    interface Startup {
        void run() throws InterruptedException;
    }

    static final class PluginStartupRefused extends RuntimeException {
        PluginStartupRefused() {
            super(null, null, false, false);
        }
    }

    /**
     * An operator-set byte ceiling, where unset and set-to-empty mean the same thing: use the policy's
     * own default (0).
     *
     * <p>{@code getOrDefault} defends against an <em>absent</em> key only, and an environment variable
     * set to the empty string is present. Compose declares every optional setting as
     * {@code NAME: ${NAME:-}}, so each one arrives as an empty string rather than absent, and parsing
     * that as a number aborted startup — a container-only failure no unit test reached, because tests
     * pass a map that simply omits the key. Treating blank as unset is what the surrounding
     * configuration readers already do.</p>
     */
    private static long byteCeiling(String variable) {
        String raw = System.getenv(variable);
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException notANumber) {
            // Do not echo the value: it is operator configuration and the useful answer is which
            // setting is wrong, not what was typed.
            throw new IllegalArgumentException("Invalid byte ceiling configuration: " + variable);
        }
    }

    /** The plugin id a failure concerns, when its diagnostic detail names one; {@code null} otherwise. */
    private static String pluginIdFrom(RuntimeException failure) {
        if (failure instanceof PluginBundleException rejection) {
            return rejection.diagnosticDetail().get("id");
        }
        return null;
    }

    /**
     * SEC-12 read side: the chosen mode and the verifier that mode installs, together, so the
     * value announced at startup and the value the registry admits with cannot drift apart. Modelled
     * on {@code UnknownBehaviorConfiguration}, which pairs {@code policy()} with {@code describe()}
     * for the same reason one layer up in {@link #run(String[])}.
     */
    record ArtifactProvenance(String mode, ArtifactProvenanceVerifier verifier) {

        /** True only for the mode that installs a verifier which checks nothing. */
        boolean unverified() {
            return "unverified".equals(mode);
        }

        /**
         * The startup line, in the shape of the {@code unknown-behavior-policy} event a few lines
         * above its caller: one JSON object on stdout, whether or not the default was kept. Stated
         * unconditionally because the interesting reading is symmetric -- an operator who believes
         * they are fail-closed needs to see {@code refusing} as much as an operator who opted out
         * needs to see {@code unverified} -- and on one stream because two streams is two greps.
         */
        String startupEvent() {
            return "{\"event\":\"artifact-provenance\",\"mode\":\"" + mode + "\""
                    + (unverified()
                            ? ",\"detail\":\"artifact provenance is NOT verified in this deployment;"
                                    + " every artifact is admitted unchecked\""
                            : "")
                    + "}";
        }
    }

    /**
     * SEC-12 read side. Chooses the verifier the artifact registry admits with, and the
     * default is unchanged: {@link ArtifactProvenanceVerifier#refusing()}, which refuses every
     * artifact. Core still ships no verifying implementation, and this method does not add one.
     *
     * <p><b>What it adds is the ability to say "none, deliberately" out loud.</b> With the refusing
     * verifier hard-wired here, a `program` node could not
     * execute in ANY deployment of this server: the five-step lifecycle ran to ACTIVE, the sandbox
     * ran, and redemption then refused with "No artifact provenance verifier is configured". That
     * is the correct posture and the wrong ergonomics -- the whole programmable-artifact feature was
     * unreachable, including for the person trying to evaluate whether it works at all.
     *
     * <p>{@code RAVENROOT_ARTIFACT_PROVENANCE=unverified} installs a verifier that accepts every
     * artifact and verifies <b>nothing</b>. It is not a weaker check: it is the absence of a check,
     * named. The interface's own Javadoc anticipates exactly this and states the condition it must
     * meet -- any deployment that skips verification must record that choice. The variable is that
     * record: it appears in the deployment's own configuration and makes the choice unambiguous.
     *
     * <p>The alternative considered and rejected was a permissive implementation shipped in core
     * with a flag to disable it, which inverts the default and makes the safe posture the one that
     * has to be remembered. Here the unsafe posture is the one that has to be typed.
     *
     * <p>Anything other than the two known values is refused at startup rather than silently
     * treated as the default: an operator who misspells the opt-in must learn it now, not from a
     * node failing hours later. Deliberately no value means "verify with a real backend" -- that
     * belongs to an integrator supplying an implementation, and this switch is not the place to
     * pretend otherwise.
     *
     * <p><b>{@code environment} is a parameter rather than a {@code System.getenv()} call</b>, for
     * the reason {@code RavenrootCliMain.embeddedApplication} and {@code
     * GraalVmProgramRuntime.fromEnvironment} already state in this repository: a seam that reads the
     * real environment is a seam a test cannot drive. Every "why" above is a claim about behaviour;
     * before this parameter existed no test in the reactor referenced {@code
     * RAVENROOT_ARTIFACT_PROVENANCE} at all, so the unchanged default and the startup refusal were
     * asserted only in prose. {@code ArtifactProvenanceConfigurationTest} is what makes breaking
     * either one red.
     *
     * <p>Empty means absent here. Compose declares optional settings as {@code NAME: ${NAME:-}}, so
     * an unset variable arrives as an empty string rather than as an absent key -- the same reason
     * {@link #byteCeiling(String)} spells that case out.
     *
     * <p><b>This method also writes the startup line, rather than leaving that to its caller.</b>
     * {@code GraalVmProgramRuntime.fromEnvironment} does the same with its own probe. Returning the
     * text for someone else to print would leave the announcement deletable
     * without anything turning red -- the call site could quietly stop printing while every test that
     * only inspected the returned string stayed green. Deciding the posture and stating it are one
     * act here, so a test that drives this method is a test of both.
     *
     * <p><b>What is still not covered, recorded rather than left to be rediscovered.</b> The CALL to
     * this method from {@link #run(String[])} can be deleted -- falling back to the registry's no-arg
     * constructor, which installs the same refusing verifier -- and the whole suite stays green. That
     * is not a regression introduced here: it is the standing condition of this composition root,
     * which {@code run} shares with at least one other configuration read the same way, and no test
     * executes {@code run} itself. Closing it would mean making {@code run}'s wiring drivable, which
     * is a change to the composition root rather than to this setting. Noted so the next reader knows
     * the tests below cover the DECISION and not its installation.
     */
    static ArtifactProvenance artifactProvenance(Map<String, String> environment) {
        String configured = environment.getOrDefault("RAVENROOT_ARTIFACT_PROVENANCE", "refusing").trim();
        ArtifactProvenance chosen = switch (configured) {
            case "refusing", "" -> new ArtifactProvenance("refusing", ArtifactProvenanceVerifier.refusing());
            case "unverified" -> new ArtifactProvenance("unverified", artifact -> { });
            default -> throw new IllegalArgumentException("Unknown RAVENROOT_ARTIFACT_PROVENANCE value: "
                    + configured + " (expected 'refusing' or 'unverified')");
        };
        System.out.println(chosen.startupEvent());
        return chosen;
    }
}
