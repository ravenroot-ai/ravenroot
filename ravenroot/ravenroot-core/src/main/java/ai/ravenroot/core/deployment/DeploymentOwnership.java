package ai.ravenroot.core.deployment;

import ai.ravenroot.api.deployment.lifecycle.DeploymentLifecycleTarget;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry.Lease;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry.Record;
import ai.ravenroot.api.deployment.registry.GenerationExpectation;
import ai.ravenroot.api.persistence.RevisionExpectation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * Taking, keeping and using ownership of one deployment, shared by the coordinator and the reconciler.
 *
 * <h2>Why the two share this rather than each having their own</h2>
 * <p>They perform the same three acts — take the lease, publish evidence under it, record a sanitized
 * failure beside it — and they must perform them identically, because the second is what makes a
 * takeover decidable and the first is what makes the second legal. Two copies of the ledger key, the
 * fence check or the evidence timestamp would be two chances to diverge, and the divergence would be
 * invisible until the two components disagreed about who owned a deployment.</p>
 *
 * <h2>Expiry is never judged here</h2>
 * <p>This process compares a lease against its own clock only to decide whether re-acquiring is worth
 * a write. Whether a lease has actually lapsed is decided by the registry against the registry's
 * clock, which is what makes takeover decidable at all: a holder cannot decide it still owns
 * anything, it can only observe how much of its window the authority says remains (ADR 0038 D0 rule
 * 1). The published {@code maxClockSkew} is subtracted from the comparison so that a process whose
 * clock leads the store's stops trusting its lease before the store stops honouring it.</p>
 */
final class DeploymentOwnership {

    private final DeploymentRegistry registry;
    private final String ownerId;
    private final Duration leaseTtl;
    private final Clock clock;

    DeploymentOwnership(DeploymentRegistry registry, String ownerId, Duration leaseTtl, Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        if (ownerId.isBlank()) throw new IllegalArgumentException("ownerId cannot be blank");
        Objects.requireNonNull(leaseTtl, "leaseTtl");
        if (leaseTtl.isNegative() || leaseTtl.isZero()) {
            throw new IllegalArgumentException("leaseTtl must be positive");
        }
        this.leaseTtl = leaseTtl;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Returns the record after this process has taken ownership, or the record unchanged when another
     * live owner holds it.
     *
     * <p>The acquisition is keyed by the ownership epoch it is <em>superseding</em> — the fence of
     * the lease it found, or zero when it found none. That makes a retried attempt replay into the
     * same lease instead of minting a second one, and makes a genuinely later attempt a different
     * ledger entry rather than a replay of an older lease that has since lapsed. Neither a timestamp
     * nor a random value could do both, and the second is exactly the takeover case: every successful
     * acquisition raises the fence, so the next one this process makes necessarily supersedes a
     * different epoch and occupies a different slot.</p>
     */
    Record own(Record record) {
        if (holds(record)) return record;
        String key = "lease:" + ownerId + ":" + (record.lease() == null ? 0L : record.lease().fence());
        try {
            return await(registry.acquire(ownerId, leaseTtl,
                    new DeploymentRegistry.Command(record.tenantId(), record.deploymentId(), key,
                            digestOf(key), RevisionExpectation.exactly(record.revision()),
                            GenerationExpectation.any())));
        } catch (DeploymentRegistry.RegistryException heldOrRaced) {
            if (heldOrRaced.reason() instanceof DeploymentRegistry.FailureReason.Conflict
                    || heldOrRaced.reason() instanceof DeploymentRegistry.FailureReason.LeaseLost) {
                return record;
            }
            throw heldOrRaced;
        }
    }

    /** Whether this process holds a lease it can still safely act under. */
    boolean holds(Record record) {
        Lease lease = record.lease();
        return lease != null && lease.owner().equals(ownerId)
                && lease.expiresAt().isAfter(clock.instant().plus(registry.limits().maxClockSkew()));
    }

    /**
     * Publishes what the runtime is, under the fence that authorizes saying so.
     *
     * <p>The evidence instant is the one the authority itself last accepted a write at, not this
     * process's own clock. Evidence timestamped in the store's future is rejected, and this process's
     * clock is allowed to lead the store's by the published skew allowance — so its own {@code now} is
     * precisely the value that can be refused, while the authority's last accepted instant is the
     * newest one it is certain to accept.</p>
     */
    Record observe(Record record, DeploymentLifecycleTarget target, long generation) {
        DeploymentLifecycleTarget.Reading reading = await(target.observe());
        String key = "observe:" + generation + ":" + record.lease().fence();
        return await(registry.observe(
                new DeploymentRegistry.Observation(reading.state(), reading.activeVersion(),
                        generation, record.updatedAt()),
                record.lease(),
                new DeploymentRegistry.Command(record.tenantId(), record.deploymentId(), key,
                        digestOf(key), RevisionExpectation.exactly(record.revision()),
                        GenerationExpectation.any())));
    }

    /**
     * Records a sanitized failure beside the intent, so an operator can see that the decision is
     * durable and its effect did not land.
     *
     * <p>Best-effort on purpose: the caller already holds a classified answer, and a failure to record
     * the failure must not replace it with a store error. Losing the annotation costs an operator one
     * line of context; converting a classified lifecycle failure into a store exception costs them the
     * answer.</p>
     */
    void report(Record record, Throwable failure) {
        if (!holds(record)) return;
        String key = "fail:" + record.generation() + ":" + record.lease().fence();
        try {
            await(registry.fail(
                    new DeploymentRegistry.Failure("LIFECYCLE_EFFECT_FAILED", classify(failure),
                            record.updatedAt()),
                    record.lease(),
                    new DeploymentRegistry.Command(record.tenantId(), record.deploymentId(), key,
                            digestOf(key), RevisionExpectation.exactly(record.revision()),
                            GenerationExpectation.any())));
        } catch (RuntimeException unrecorded) {
            // Deliberately swallowed; see this method's contract above.
        }
    }

    /**
     * Classifies a failure by the class name of its root cause and never by its message, the rule
     * {@code DurableExecutionResult} already follows: a message may carry payload fragments,
     * credentials or author-controlled text, and this value reaches operator surfaces and logs, which
     * are exactly the places a leaked secret persists.
     */
    static String classify(Throwable failure) {
        Throwable root = failure;
        while (root instanceof CompletionException && root.getCause() != null) root = root.getCause();
        return root.getClass().getName();
    }

    static String digestOf(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required by every supported runtime", unavailable);
        }
    }

    /**
     * Joins a registry stage, unwrapping the adapter's own structured rejection so callers branch on
     * {@code FailureReason} instead of on the wrapper the completion machinery added.
     */
    static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException wrapped) {
            if (wrapped.getCause() instanceof DeploymentRegistry.RegistryException registry) throw registry;
            throw wrapped;
        }
    }
}
