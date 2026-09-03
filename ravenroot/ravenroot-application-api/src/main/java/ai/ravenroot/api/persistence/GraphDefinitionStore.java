package ai.ravenroot.api.persistence;

import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * The engine-neutral persistence port for immutable graph definitions.
 *
 * <h2>Why this is a separate port and not an addition to the execution store</h2>
 * <p>{@link ExecutionStore} states, as part of its published contract, that it does not store graph
 * bytes and does not define graph identity or deduplication, and {@link GraphVersionPin} states that
 * it holds a reference and nothing else. Those are boundaries, not omissions. Adding definition
 * storage to the execution store would falsify both, and it would put two different lifetimes behind
 * one interface: an execution is retained for as long as its work is recoverable, a definition for
 * as long as <em>any</em> execution that used it is. Keeping them apart is also what lets one
 * definition serve many executions without the execution aggregate growing a fan-in it cannot
 * express.</p>
 *
 * <h2>Ordering: the definition is durable before the execution that names it</h2>
 * <p>An execution must never be accepted while the definition it pins is absent, because acceptance
 * is the promise that the work can be resumed and a pin alone cannot supply the missing bytes. The
 * required ordering is therefore: commit the definition, then commit the acceptance.</p>
 *
 * <p>That ordering is sufficient, and it is why this port does not need to share a transaction with
 * the execution store. The asymmetry is the whole argument. A definition committed for an acceptance
 * that then fails is an unreferenced blob — invisible, harmless, and reclaimed by
 * {@link #purgeUnreferencedDefinitions(String)}. An acceptance committed for a definition that was
 * never written is an execution that can never be recovered. Only one of the two orderings can leave
 * the second state, so only one ordering is legal. A caller that reverses it, or that writes the two
 * concurrently, breaks this contract even if both writes happen to succeed.</p>
 *
 * <p>Because a definition write is content-addressed it is also idempotent, which is what makes the
 * unreferenced blob genuinely harmless rather than merely rare: repeating the write after an unknown
 * outcome converges on exactly one stored copy.</p>
 *
 * <h2>Verification</h2>
 * <p>Every read verifies the stored bytes against the digest recorded beside them before returning
 * them. There is no unverified read and no verification flag: a runtime replays a graph against
 * these bytes, so a caller offered the choice would eventually take the cheap one.</p>
 *
 * <p>A runtime may cache a verified definition for as long as it holds the execution. The durable
 * store is nevertheless the authority after a restart or an ownership transfer, because a cache
 * cannot outlive the process that holds it and a new owner has none.</p>
 *
 * <h2>Asynchrony</h2>
 * <p>Operations that touch stored state return a {@link CompletionStage}; static self-description is
 * synchronous. {@link ExecutionStore} draws the line in the same place and for the same reasons: a
 * remote adapter is inherently asynchronous, and the composition-time bounds must be readable
 * without blocking inside a constructor.</p>
 *
 * <h2>Tenancy</h2>
 * <p>Every operation is tenant-scoped. There is no global operation on this port, and deduplication
 * never crosses a tenant boundary: two tenants submitting byte-identical documents hold two
 * definitions. Sharing one stored copy between them would make the store a channel through which one
 * tenant could learn that another holds a given document, and would give one tenant's retention
 * decision authority over another tenant's recoverability.</p>
 *
 * <h2>What this port does not do</h2>
 * <p>It does not parse GraphML, does not validate a graph, does not decide which version is active,
 * and does not hold credentials. Graph definitions carry graph content and non-secret references
 * only; secret values are supplied at execution time and never appear in a stored definition.</p>
 */
public interface GraphDefinitionStore extends AutoCloseable {

    /**
     * Facilities this adapter honours.
     *
     * <p>Static self-description, therefore synchronous. The vocabulary is
     * {@link StoreCapability}'s, reused rather than duplicated: a second capability enum would force
     * a caller composing both stores to reason about two names for durability.</p>
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
     * The largest canonical definition this adapter accepts, in bytes.
     *
     * <p>Exceeding it fails with {@link GraphDefinitionStoreFailure.DefinitionTooLarge}, checked
     * before any transaction opens. The bound is part of the contract rather than adapter
     * configuration trivia: a store whose limit sat below the limit GraphML ingest already enforces
     * would accept a document at the edge and then fail to persist it, turning a submission that
     * validated into an acceptance that cannot complete.</p>
     *
     * @return largest canonical definition this adapter accepts in one write.
     */
    int maxDefinitionBytes();

    /**
     * Stores one canonical definition and binds it to an immutable logical graph version.
     *
     * <p>Idempotent by content. Repeating the identical call converges on one stored copy and one
     * binding, and returns the definition as stored. Storing byte-identical content under a second
     * logical identity is legal and adds a binding without a second copy.</p>
     *
     * <p>Fails with {@link GraphDefinitionStoreFailure.DefinitionTooLarge} when the document exceeds
     * {@link #maxDefinitionBytes()}, {@link GraphDefinitionStoreFailure.IdentityConflict} when the
     * logical version is already bound to different content, and
     * {@link GraphDefinitionStoreFailure.DigestMismatch} when content already stored at this address
     * no longer hashes to it — a repeated write must not overwrite corruption into looking healthy.</p>
     *
     * @param tenantId tenant that will own the stored definition.
     * @param identity immutable logical graph version to bind this content to.
     * @param canonical the exact canonical executable GraphML to store.
     * @return the definition as stored, with its verified content address.
     */
    CompletionStage<StoredGraphDefinition> put(String tenantId, GraphDefinitionIdentity identity,
                                               CanonicalGraphMl canonical);

    /**
     * Reads one definition by content address and verifies it before returning it.
     *
     * <p>Fails with {@link GraphDefinitionStoreFailure.NotFound} when nothing observable is stored
     * at the address for this tenant — including when the address belongs to another tenant —
     * {@link GraphDefinitionStoreFailure.DigestMismatch} when the bytes no longer hash to the
     * address, and {@link GraphDefinitionStoreFailure.Corrupted} when the row cannot be read back as
     * a definition at all.</p>
     *
     * @param key tenant-scoped address of the definition to read.
     * @return the verified definition.
     */
    CompletionStage<StoredGraphDefinition> load(GraphDefinitionKey key);

    /**
     * Reads the definition an immutable logical graph version is bound to, and verifies it.
     *
     * <p>Resolves the binding and then reads through {@link #load(GraphDefinitionKey)}, so the same
     * verification applies and the same failures are reachable.</p>
     *
     * @param tenantId tenant that owns the binding.
     * @param identity immutable logical graph version to resolve.
     * @return the verified definition that version is bound to.
     */
    CompletionStage<StoredGraphDefinition> resolve(String tenantId, GraphDefinitionIdentity identity);

    /**
     * Reports whether a definition is stored at this address for this tenant.
     *
     * <p>Exists so a caller can check a precondition without transferring and verifying a document
     * it does not need. It is a weaker statement than {@link #load(GraphDefinitionKey)}: it says a
     * row is present, not that the row verifies. Anything that will execute the definition must
     * load it.</p>
     *
     * @param key tenant-scoped address to test for.
     * @return whether a definition is stored at that address.
     */
    CompletionStage<Boolean> contains(GraphDefinitionKey key);

    /**
     * Removes one named definition, refusing while retained work still reaches it.
     *
     * <p>Fails with {@link GraphDefinitionStoreFailure.StillReferenced} when the definition is still
     * reachable and with {@link GraphDefinitionStoreFailure.NotFound} when nothing observable is
     * stored at the address for this tenant. Refusing is the point: a definition an execution can
     * still be recovered against must outlive every request to delete it, and a caller that asked to
     * delete and was answered with silence could not tell refusal from success.</p>
     *
     * <p>Reachability is recomputed inside the removal transaction; see
     * {@link GraphDefinitionReferences}. Removing a definition also removes every logical version
     * binding that named it, so a later resolve reports an absent definition rather than a broken
     * one.</p>
     *
     * @param key tenant-scoped address of the definition to remove.
     * @return a stage that completes when the definition is gone.
     */
    CompletionStage<Void> remove(GraphDefinitionKey key);

    /**
     * Removes every definition of one tenant that retained work no longer reaches.
     *
     * <p>Caller-invoked, never a background sweep. Retention that belongs to a caller stays an
     * operation rather than becoming an adapter setting, so an operator decides when reclamation
     * happens and can see what it did.</p>
     *
     * <p>Reachability is recomputed inside the removal transaction and no stored reference count
     * exists; see {@link GraphDefinitionReferences} for what that covers and what it does not yet
     * cover. A definition still reachable is left in place and is not counted.</p>
     *
     * @param tenantId tenant whose unreferenced definitions may be reclaimed.
     * @return the number of definitions removed.
     */
    CompletionStage<Long> purgeUnreferencedDefinitions(String tenantId);

    /** Releases this adapter's resources. Closing twice is legal and does nothing the second time. */
    @Override
    void close();
}
