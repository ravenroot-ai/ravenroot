package ai.ravenroot.api.persistence;

import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * The engine-neutral persistence port for immutable resolved execution manifests.
 *
 * <h2>Why this is a third port and not a field on either existing one</h2>
 * <p>{@link ExecutionStore} states as part of its published contract that it stores a graph version
 * reference and nothing else, and {@link GraphDefinitionStore} stores one document that many
 * executions may share. A manifest is neither: it is per-execution, it is not the execution's
 * mutable aggregate, and it outlives no execution but its own. Folding it into the execution store
 * would falsify that store's stated boundary and would require every existing adapter to implement a
 * new operation; folding it into the definition store would give a shared, deduplicated row a
 * per-execution lifetime. Keeping it separate is also what lets a deployment compose durable
 * executions without manifests and be told so explicitly, rather than silently.</p>
 *
 * <h2>Ordering: the manifest is durable before the acceptance that references it</h2>
 * <p>An execution must never be accepted while the manifest describing what it was resolved against
 * is absent, because acceptance is the promise that the work can be resumed as the work that was
 * accepted. The required ordering is: commit the definition, commit the manifest, then commit the
 * acceptance.</p>
 *
 * <p>That ordering is sufficient, and it is why this port does not share a transaction with the
 * execution store. The asymmetry is the argument, exactly as it is for
 * {@link GraphDefinitionStore}: a manifest committed for an acceptance that then fails is an
 * unreferenced row — invisible, harmless, reclaimed by {@link #remove(ExecutionKey)} once the
 * execution is known not to exist — while an acceptance committed for a manifest that was never
 * written is an execution that can only be recovered by resolving today's environment, which is the
 * state this port exists to make unreachable. Only one of the two orderings can reach the second
 * state, so only one ordering is legal.</p>
 *
 * <p>The manifest is addressed by the execution it belongs to, so the write is idempotent: repeating
 * it after an unknown outcome converges on exactly one pinned manifest, and a repeat carrying
 * different content is refused rather than absorbed.</p>
 *
 * <h2>Verification</h2>
 * <p>Every read re-derives the digest from the stored fields and compares it against the address
 * recorded beside them before returning. There is no unverified read and no verification flag: a
 * recovery decides whether to run against this record, so a caller offered the choice would
 * eventually take the cheap one.</p>
 *
 * <p>Verification of <em>integrity</em> is this port's whole responsibility. Whether the pinned
 * dependencies match what the runtime resolves today is a separate question, answered by
 * {@link ExecutionManifestCompatibility#compare(ExecutionManifest, ExecutionManifest)}, because the
 * store has no way to observe a running engine.</p>
 *
 * <h2>Tenancy</h2>
 * <p>Every operation is keyed by {@link ExecutionKey}, so the tenant participates in the address. A
 * read scoped to a mismatched tenant reports {@link ExecutionManifestStoreFailure.NotFound} and
 * never a denial, so the store cannot be used as a cross-tenant existence oracle.</p>
 *
 * <h2>What this port does not do</h2>
 * <p>It does not resolve dependencies, does not decide compatibility, does not store graph bytes,
 * and does not hold credentials. A manifest carries closed values and digests only; the record's own
 * shape is what makes the last of those structural rather than a rule an adapter must remember.</p>
 */
public interface ExecutionManifestStore extends AutoCloseable {

    /**
     * Facilities this adapter honours.
     *
     * <p>Static self-description, therefore synchronous. The vocabulary is {@link StoreCapability}'s,
     * reused rather than duplicated, for the reason {@link GraphDefinitionStore} gives.</p>
     *
     * @return immutable persistence features guaranteed by this adapter.
     */
    Set<StoreCapability> capabilities();

    /**
     * Tests whether this adapter advertises a requested persistence capability.
     *
     * @param capability capability to test for.
     * @return whether the capability is present in {@link #capabilities()}.
     */
    default boolean supports(StoreCapability capability) {
        return capabilities().contains(capability);
    }

    /**
     * Pins one manifest to its execution, write-once.
     *
     * <p>Idempotent by content. Repeating the identical call converges on one pinned manifest and
     * returns it as stored. A call presenting different content for an execution that is already
     * pinned fails with {@link ExecutionManifestStoreFailure.ManifestConflict} and never overwrites,
     * so a repeated write cannot turn a manifest that no longer verifies into one that looks
     * healthy.</p>
     *
     * <p>Pinning is <strong>not</strong> a full integrity check of what is already there. An
     * implementation may compare the presented digest against the address recorded for the execution
     * without reconstructing the stored manifest, so that accepting an execution does not pay a read
     * and a re-derivation once per acceptance. Re-deriving from the stored fields is what
     * {@link #load(ExecutionKey)} does, at the moment the manifest is actually handed to something
     * that will decide whether to run.</p>
     *
     * @param manifest the resolved dependency set to pin; its own key addresses the execution.
     * @return the manifest as stored, with the address recorded beside it.
     */
    CompletionStage<StoredExecutionManifest> pin(ExecutionManifest manifest);

    /**
     * Reads one execution's manifest and verifies it before returning it.
     *
     * <p>Fails with {@link ExecutionManifestStoreFailure.NotFound} when nothing observable is stored
     * for this execution — including when it belongs to another tenant —
     * {@link ExecutionManifestStoreFailure.DigestMismatch} when the stored fields no longer derive
     * the recorded address, and {@link ExecutionManifestStoreFailure.Corrupted} when the row cannot
     * be read back as a manifest at all.</p>
     *
     * @param key tenant-scoped execution whose manifest to read.
     * @return the verified manifest.
     */
    CompletionStage<StoredExecutionManifest> load(ExecutionKey key);

    /**
     * Reports whether a manifest is pinned for this execution.
     *
     * <p>Exists so a caller can check a precondition without re-deriving a digest it does not need.
     * It is a weaker statement than {@link #load(ExecutionKey)}: it says a row is present, not that
     * the row verifies. Anything that will decide whether to run must load it.</p>
     *
     * @param key tenant-scoped execution to test for.
     * @return whether a manifest is pinned for that execution.
     */
    CompletionStage<Boolean> contains(ExecutionKey key);

    /**
     * Removes one execution's manifest, refusing while retained work still needs it.
     *
     * <p>Fails with {@link ExecutionManifestStoreFailure.StillReferenced} when the execution is still
     * retained or recoverable and with {@link ExecutionManifestStoreFailure.NotFound} when nothing
     * observable is stored for it. Reachability is recomputed inside the removal transaction; see
     * {@link ExecutionManifestReferences}.</p>
     *
     * @param key tenant-scoped execution whose manifest may be reclaimed.
     * @return a stage that completes when the manifest is gone, and that fails when it may not be removed.
     */
    CompletionStage<Void> remove(ExecutionKey key);

    /**
     * Removes every manifest of one tenant whose execution no longer exists.
     *
     * <p>Caller-invoked, never a background sweep, for the reason
     * {@link GraphDefinitionStore#purgeUnreferencedDefinitions(String)} gives: retention that belongs
     * to a caller stays an operation rather than becoming an adapter setting, so an operator decides
     * when reclamation happens and can see what it did.</p>
     *
     * <p>This is the operation that keeps manifests from outliving everything that could need them.
     * A manifest is committed before the acceptance that references it and is not removed when that
     * execution's rows are, so two populations accumulate without it: manifests pinned for an
     * acceptance that then failed, and manifests whose instance a later retention pass removed.
     * Neither is reachable and neither would ever be reclaimed on its own.</p>
     *
     * <p>Reachability is recomputed inside the removal transaction and no stored reference count
     * exists; see {@link ExecutionManifestReferences}. A manifest still needed is left in place and
     * is not counted.</p>
     *
     * @param tenantId tenant whose unreferenced manifests may be reclaimed.
     * @return the number of manifests removed.
     */
    CompletionStage<Long> purgeUnreferencedManifests(String tenantId);

    /** Releases this adapter's resources. Closing twice is legal and does nothing the second time. */
    @Override
    void close();
}
