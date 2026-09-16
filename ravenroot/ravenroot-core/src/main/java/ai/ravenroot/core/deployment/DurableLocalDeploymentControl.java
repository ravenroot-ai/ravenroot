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
    }

    /** Creates or replays the durable aggregate before publishing its local runtime. */
    public Registration register(SecurityContext security, String localId, byte[] canonicalGraphMl) {
        Objects.requireNonNull(security, "security");
        Objects.requireNonNull(canonicalGraphMl, "canonicalGraphMl");
        String digest = sha256(canonicalGraphMl);
        DeploymentRegistry.Record record = await(registry.create(
                new GraphVersion.Content(1, canonicalGraphMl,
                        security.qualifiedIdentity(), clock.instant()),
                new DeploymentRegistry.CreateCommand(security.tenantId(),
                        "local-deployment:" + localId, digest)));
        Alias alias = new Alias(security.tenantId(), localId);
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
        DeploymentId id = aliases.get(new Alias(tenantId, localId));
        return id == null ? Optional.empty() : await(registry.get(tenantId, id));
    }

    public Optional<DeploymentCommandOutcome> submit(String tenantId, String localId,
                                                      LifecycleCommand command,
                                                      GenerationExpectation expectedGeneration) {
        DeploymentId id = aliases.get(new Alias(tenantId, localId));
        return id == null ? Optional.empty() : Optional.of(
                coordinator.submit(tenantId, id, command, expectedGeneration));
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
        recovery.shutdownNow();
    }
}
