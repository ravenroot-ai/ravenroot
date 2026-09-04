package ai.ravenroot.core.manifest;

import ai.ravenroot.api.application.ExecutionPolicy;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionManifest;
import ai.ravenroot.api.persistence.ExecutionManifestCompatibility;
import ai.ravenroot.api.persistence.ExecutionManifestStore;
import ai.ravenroot.api.persistence.ExecutionManifestStoreException;
import ai.ravenroot.api.persistence.GraphContentId;
import ai.ravenroot.api.persistence.GraphDefinitionIdentity;
import ai.ravenroot.api.persistence.StoredExecutionManifest;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * The two operations a runtime performs on a manifest: pin one at admission, verify one before
 * running anything against it.
 *
 * <h2>Why both live behind one object</h2>
 * <p>Pinning and verifying must agree, field for field, about what the runtime's dependencies are.
 * Splitting them would put the resolver behind two call sites that could be composed with different
 * inputs — an admission path that pinned one profile and a recovery path that compared against
 * another — and the resulting refusals would look like corruption rather than like a composition
 * mistake. One object holding one resolver makes that class of mistake unrepresentable.</p>
 *
 * <h2>What verification refuses, and what it does not repair</h2>
 * <p>{@link #verify} is a gate, not a restoration. It compares the pinned dependency set against what
 * the caller is about to run under and refuses when they differ; it does not hand the pinned values
 * back so the caller can adopt them. That matters most for the two the recovery paths get from the
 * process rather than from the execution — the submission policy, which the pinned recovery runner
 * hard-codes, and the execution limits, which it takes from current configuration. A composition
 * holding a manifest refuses those cases instead of running them. A composition holding none runs
 * them exactly as it did before, because there is nothing to compare against.</p>
 *
 * <h2>Composition is the switch</h2>
 * <p>A runtime that composes no manifest service verifies nothing and behaves exactly as it did
 * before manifests existed, including both substitutions named above. A runtime that composes one refuses to recover an execution that has no
 * manifest, including one accepted before manifests were introduced. That refusal is deliberate and
 * is the whole point: such an execution can only be recovered by resolving today's environment,
 * which is the substitution this contract exists to prevent. It is stated here rather than left to
 * be discovered, because it is the visible consequence of enabling the feature on an existing
 * database.</p>
 */
public final class ExecutionManifestService {

    private final ExecutionManifestStore store;
    private final ExecutionManifestResolver resolver;
    private final Clock clock;

    /**
     * Composes the service over a store and the runtime's resolved dependencies.
     *
     * @param store durable manifest store.
     * @param resolver resolver describing what this runtime composes.
     * @param clock time authority for the instant a manifest records as its pinning time.
     */
    public ExecutionManifestService(ExecutionManifestStore store, ExecutionManifestResolver resolver,
                                    Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Pins the dependency set one execution is being accepted against.
     *
     * <p>Called before the acceptance that references it, and a failure refuses the submission. That
     * is the entire point of the ordering: the alternative is an accepted execution whose dependency
     * set nothing recorded, which is exactly the state this service exists to make unreachable.</p>
     *
     * @param key tenant-scoped process instance being accepted.
     * @param graphContentId address of the canonical document it was accepted against.
     * @param graphIdentity logical graph and version identity that document is bound under.
     * @param policy submission policy this execution runs under.
     * @return the manifest as stored.
     */
    public StoredExecutionManifest pin(ExecutionKey key, GraphContentId graphContentId,
                                       GraphDefinitionIdentity graphIdentity, ExecutionPolicy policy) {
        ExecutionManifest manifest = resolver.manifestFor(key, graphContentId, graphIdentity, policy,
                Instant.now(clock));
        return await(store.pin(manifest));
    }

    /**
     * Reads one execution's manifest, verifies its integrity, and compares it against this runtime.
     *
     * <p>Integrity is the store's answer and arrives as a classified
     * {@link ExecutionManifestStoreException}: absent, digest-mismatched or unreadable. Compatibility
     * is this method's own answer and arrives as
     * {@link ExecutionManifestIncompatibleException}. Both are refusals, and both are typed, so a
     * caller can tell a runtime that must not run this work from a store it should ask again.</p>
     *
     * @param key tenant-scoped process instance to verify before running anything for it.
     * @param policy submission policy the caller is about to run this execution under.
     * @return the verified manifest, when this runtime resolves exactly what it pins.
     */
    public StoredExecutionManifest verify(ExecutionKey key, ExecutionPolicy policy) {
        StoredExecutionManifest stored = await(store.load(key));
        ExecutionManifest pinned = stored.manifest();
        ExecutionManifest current = resolver.manifestFor(pinned.key(), pinned.graphContentId(),
                pinned.graphIdentity(), policy, pinned.pinnedAt());
        ExecutionManifestCompatibility report = ExecutionManifestCompatibility.compare(pinned, current);
        if (!report.compatible()) {
            throw new ExecutionManifestIncompatibleException(key, report);
        }
        return stored;
    }

    /**
     * Compares one execution's pinned manifest against this runtime without refusing.
     *
     * <p>For a projection that reports compatibility state rather than acting on it. Integrity
     * failures still surface as {@link ExecutionManifestStoreException}, because a manifest that does
     * not verify has no compatibility state to report.</p>
     *
     * @param key tenant-scoped process instance to report on.
     * @param policy submission policy the report should be computed against.
     * @return the bounded compatibility report.
     */
    public ExecutionManifestCompatibility describe(ExecutionKey key, ExecutionPolicy policy) {
        return describe(await(store.load(key)), policy);
    }

    /**
     * Compares a manifest already read back against this runtime.
     *
     * <p>For a caller that has loaded the manifest for another reason — a projection that also reports
     * its digest, for instance — so that rendering one answer does not cost two durable reads.</p>
     *
     * @param stored a manifest this store has already verified.
     * @param policy submission policy the report should be computed against.
     * @return the bounded compatibility report.
     */
    public ExecutionManifestCompatibility describe(StoredExecutionManifest stored, ExecutionPolicy policy) {
        ExecutionManifest pinned = Objects.requireNonNull(stored, "stored").manifest();
        ExecutionManifest current = resolver.manifestFor(pinned.key(), pinned.graphContentId(),
                pinned.graphIdentity(), policy, pinned.pinnedAt());
        return ExecutionManifestCompatibility.compare(pinned, current);
    }

    /**
     * The store this service pins into, for a caller that must read a manifest without verifying it.
     *
     * @return the composed manifest store.
     */
    public ExecutionManifestStore store() {
        return store;
    }

    private static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException wrapped) {
            ExecutionManifestStoreException failure = ExecutionManifestStoreException.unwrap(wrapped);
            throw failure == null ? wrapped : failure;
        }
    }
}
