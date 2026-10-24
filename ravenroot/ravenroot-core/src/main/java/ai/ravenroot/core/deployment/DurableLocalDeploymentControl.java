package ai.ravenroot.core.deployment;

import ai.ravenroot.api.application.LocalDeploymentStatus;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.lifecycle.DeploymentCommandOutcome;
import ai.ravenroot.api.deployment.lifecycle.LifecycleCommand;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry;
import ai.ravenroot.api.deployment.registry.GenerationExpectation;
import ai.ravenroot.api.deployment.registry.GraphVersion;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Binds caller-facing local deployment names to the shared durable lifecycle authority. */
public final class DurableLocalDeploymentControl implements AutoCloseable {
    private final DefaultRavenrootApplication application;
    private final DeploymentRegistry registry;
    private final DeploymentCoordinator coordinator;
    private final Clock clock;
    private final DeploymentReconciler reconciler;
    private final java.time.Duration reconciliationInterval;
    private final java.util.concurrent.ScheduledExecutorService recovery;
    private final Runnable uninstallRestore;
    private final ConcurrentMap<Alias, DeploymentId> aliases = new ConcurrentHashMap<>();

    public DurableLocalDeploymentControl(DefaultRavenrootApplication application,
                                         DeploymentRegistry registry,
                                         DeploymentCoordinator coordinator,
                                         Clock clock) {
        this(application, registry, coordinator, null, java.time.Duration.ofSeconds(1), clock);
    }

    public DurableLocalDeploymentControl(DefaultRavenrootApplication application,
                                         DeploymentRegistry registry,
                                         DeploymentCoordinator coordinator,
                                         DeploymentReconciler reconciler,
                                         java.time.Duration reconciliationInterval,
                                         Clock clock) {
        this.application = Objects.requireNonNull(application, "application");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.reconciler = reconciler;
        this.reconciliationInterval = Objects.requireNonNull(
                reconciliationInterval, "reconciliationInterval");
        if (reconciliationInterval.isZero() || reconciliationInterval.isNegative()) {
            throw new IllegalArgumentException("reconciliationInterval must be positive");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
        this.recovery = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ravenroot-deployment-recovery");
            thread.setDaemon(true);
            return thread;
        });
        this.uninstallRestore = application.installDurableLocalRegistrationRestorer(this::restore);
    }

    /** Creates or replays the durable aggregate before publishing its local runtime. */
    public Registration register(SecurityContext security, String localId, byte[] canonicalGraphMl) {
        Objects.requireNonNull(security, "security");
        Objects.requireNonNull(canonicalGraphMl, "canonicalGraphMl");
        Alias alias = new Alias(security.tenantId(), localId);
        DeploymentId known = aliases.get(alias);
        if (known != null && await(registry.get(security.tenantId(), known))
                .map(existing -> existing.tombstone() != null).orElse(false)) {
            throw new IllegalStateException("a removed durable deployment id cannot be reused");
        }
        String digest = sha256(canonicalGraphMl);
        DeploymentRegistry.Record record = await(registry.create(
                new GraphVersion.Content(1, canonicalGraphMl,
                        security.qualifiedIdentity(), clock.instant()),
                new DeploymentRegistry.CreateCommand(security.tenantId(),
                        "local-deployment:" + localId, digest, true)));
        if (record.tombstone() != null) {
            throw new IllegalStateException("a removed durable deployment id cannot be reused");
        }
        DeploymentId prior = aliases.putIfAbsent(alias, record.deploymentId());
        if (prior != null && !prior.equals(record.deploymentId())) {
            throw new IllegalStateException("local deployment alias changed durable identity");
        }
        LocalDeploymentStatus status = application.registerDurableLocalDeployment(
                security, localId, record.deploymentId(), new ByteArrayInputStream(canonicalGraphMl));
        if (reconciler != null && record.generation() > 0) {
            reconciler.reconcileHosted(record.tenantId(), record.deploymentId());
        }
        return new Registration(status, record);
    }

    /** Rehosts an exact existing alias from bounded retained graph custody; never calls create. */
    public Optional<LocalDeploymentStatus> restore(SecurityContext security, String localId,
            String registrationIdentity, String graphVersion) {
        return restore(security, localId, registrationIdentity, graphVersion, bytes -> { });
    }

    /** Adapter admission runs on retained bytes before any local runtime is published. */
    public Optional<LocalDeploymentStatus> restore(SecurityContext security, String localId,
            String registrationIdentity, String graphVersion, java.util.function.Consumer<byte[]> admission) {
        Objects.requireNonNull(security); Objects.requireNonNull(admission);
        if (localId == null || !localId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                || registrationIdentity == null || registrationIdentity.length() > 256
                || graphVersion == null || !graphVersion.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("invalid durable restore binding");
        var retained = awaitRestore(registry.retainedIdentity(security.tenantId(), "local-deployment:" + localId));
        DeploymentId id = retained.map(DeploymentRegistry.Record::deploymentId).orElse(null);
        if (id == null || !id.value().equals(registrationIdentity)) return Optional.empty();
        return coordinator.withHostedRegistration(security.tenantId(), id, () -> {
            var record = awaitRestore(registry.get(security.tenantId(), id)).orElse(null);
            if (record == null || record.tombstone() != null) return Optional.empty();
            int bound = application.maximumLocalDeploymentGraphBytes();
            var version = awaitRestore(registry.version(security.tenantId(), id, record.latestVersion(), bound)).orElse(null);
            if (version == null || !version.tenantId().equals(security.tenantId()) || !version.deploymentId().equals(id)
                    || version.version() != record.latestVersion() || !version.canonicalDigest().equals(graphVersion)) return Optional.empty();
            byte[] graph = version.canonicalSnapshot(); admission.accept(graph);
            DeploymentId prior = aliases.putIfAbsent(new Alias(security.tenantId(), localId), id);
            if (prior != null && !prior.equals(id)) throw new IllegalStateException("local deployment alias changed durable identity");
            application.registerDurableLocalDeployment(security, localId, id, new ByteArrayInputStream(graph));
            try {
                if (reconciler != null && record.generation() > 0) reconciler.reconcileHosted(security.tenantId(), id);
                var current = awaitRestore(registry.get(security.tenantId(), id)).orElseThrow();
                if (current.tombstone() != null) {
                    awaitRestore(application.undeployLocalDeployment(security.tenantId(), localId)); return Optional.empty();
                }
                return application.localDeployment(security.tenantId(), localId);
            } catch (RuntimeException failure) {
                awaitRestore(application.undeployLocalDeployment(security.tenantId(), localId)); throw failure;
            }
        });
    }

    private <T> T awaitRestore(java.util.concurrent.CompletionStage<T> operation) {
        try { return operation.toCompletableFuture().get(application.durableDeploymentRestoreReadTimeout().toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("durable restore interrupted", interrupted); }
        catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException unresolved) {
            throw new IllegalStateException("durable restore unresolved", unresolved);
        }
    }

    /** Starts bounded periodic takeover/restart convergence for locally hosted aliases. */
    public void startRecovery() {
        if (reconciler == null) return;
        recovery.scheduleWithFixedDelay(this::reconcileKnownSafely,
                reconciliationInterval.toMillis(), reconciliationInterval.toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void reconcileKnownSafely() {
        for (var entry : aliases.entrySet()) {
            try {
                reconciler.reconcileHosted(entry.getKey().tenantId(), entry.getValue());
            } catch (RuntimeException ignored) {
                // The durable intent remains standing; a later bounded pass retries it.
            }
        }
    }

    public Optional<DeploymentRegistry.Record> get(String tenantId, String localId) {
        DeploymentId id = resolveAlias(tenantId, localId);
        return id == null ? Optional.empty() : await(registry.get(tenantId, id));
    }

    public Optional<DeploymentCommandOutcome> submit(String tenantId, String localId,
                                                      LifecycleCommand command,
                                                      GenerationExpectation expectedGeneration) {
        DeploymentId id = resolveAlias(tenantId, localId);
        if (id == null) return Optional.empty();
        DeploymentCommandOutcome outcome = coordinator.submit(
                tenantId, id, command, expectedGeneration);
        if (isTerminal(outcome)) {
            // The registry tombstone remains authoritative and the alias remains resolvable so an
            // identical retry can replay TERMINAL. Only the process-local runtime is removed.
            await(application.undeployLocalDeployment(tenantId, localId));
        }
        return Optional.of(outcome);
    }

    /**
     * Submits to the exact immutable registration and active graph observed by a document, while
     * retaining the lifecycle coordinator's durable generation, lease and compare-and-set fences.
     * @param tenantId owning tenant
     * @param localId logical deployment name
     * @param registrationIdentity immutable registry identity
     * @param runtimeIncarnationId exact runtime captured before the command
     * @param graphVersion observed active graph digest
     * @param command explicit lifecycle command
     * @param expectedGeneration authoritative generation observed by the caller
     * @return normal lifecycle outcome, or empty when the alias is absent
     */
    public Optional<DeploymentCommandOutcome> submitBound(String tenantId, String localId,
            String registrationIdentity, String runtimeIncarnationId, String graphVersion, LifecycleCommand command,
            long expectedGeneration) {
        Objects.requireNonNull(registrationIdentity); Objects.requireNonNull(graphVersion);
        if (!(command instanceof LifecycleCommand.Stop) && !(command instanceof LifecycleCommand.Start))
            throw new IllegalArgumentException("bound command must be Start or Stop");
        DeploymentId id = resolveAlias(tenantId, localId);
        if (id == null) return Optional.empty();
        if (!id.value().equals(registrationIdentity)) throw new IllegalStateException("deployment registration changed");
        return coordinator.withHostedRegistration(tenantId, id, () -> {
            var runtime = application.localDeployment(tenantId, localId).orElseThrow(() -> new IllegalStateException("deployment runtime is absent"));
            if (!runtime.runtimeIncarnationId().filter(runtimeIncarnationId::equals).isPresent())
                throw new IllegalStateException("deployment runtime changed");
            var record = await(registry.get(tenantId, id)).orElseThrow();
            if (record.tombstone() != null) throw new IllegalStateException("deployment was removed");
            Long active = record.observed().activeVersion();
            long selected = command instanceof LifecycleCommand.Start start ? start.version() : active != null ? active : record.desired().desiredVersion() != null
                    ? record.desired().desiredVersion() : record.latestVersion();
            var version = await(registry.version(tenantId, id, selected,
                    application.maximumLocalDeploymentGraphBytes())).orElseThrow();
            if (!version.canonicalDigest().equals(graphVersion)) throw new IllegalStateException("deployment graph changed");
            // The alias is never re-resolved. A new activation changes generation, so a concurrent
            // authority cannot switch the active graph past this check and still accept the command.
            return Optional.of(coordinator.submit(tenantId, id, command, GenerationExpectation.exactly(expectedGeneration)));
        });
    }

    private DeploymentId resolveAlias(String tenantId, String localId) {
        Alias alias = new Alias(tenantId, localId);
        DeploymentId known = aliases.get(alias);
        if (known != null) return known;
        Optional<DeploymentRegistry.Record> retained = await(registry.retainedIdentity(
                tenantId, "local-deployment:" + localId));
        if (retained.isEmpty()) return null;
        DeploymentId resolved = retained.orElseThrow().deploymentId();
        DeploymentId raced = aliases.putIfAbsent(alias, resolved);
        if (raced != null && !raced.equals(resolved)) {
            throw new IllegalStateException("local deployment alias changed durable identity");
        }
        return raced == null ? resolved : raced;
    }

    private static boolean isTerminal(DeploymentCommandOutcome outcome) {
        if (outcome instanceof DeploymentCommandOutcome.Terminal) return true;
        return outcome instanceof DeploymentCommandOutcome.Replayed replayed
                && replayed.original() instanceof DeploymentCommandOutcome.Terminal;
    }

    public record Registration(LocalDeploymentStatus local, DeploymentRegistry.Record durable) {
        public Registration {
            Objects.requireNonNull(local, "local");
            Objects.requireNonNull(durable, "durable");
        }
    }

    private record Alias(String tenantId, String localId) {
        private Alias {
            if (tenantId == null || tenantId.isBlank() || localId == null || localId.isBlank()) {
                throw new IllegalArgumentException("tenant and local deployment id are required");
            }
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static <T> T await(java.util.concurrent.CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    @Override
    public void close() {
        uninstallRestore.run();
        recovery.shutdownNow();
    }
}
