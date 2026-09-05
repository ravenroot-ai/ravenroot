package ai.ravenroot.core.recovery;

import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.ProcessInventoryEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The single production authority every recovered item passes through.
 *
 * <h2>Why this exists beside {@link CompositeRecoveryDispatcher}</h2>
 * <p>The composite answers one question — which registered adapter owns this item — and answers it
 * well. It cannot answer the question that has to be settled first: whether this deployment is
 * entitled to rebuild the execution at all. Before graph documents and manifests were retained there
 * was nothing to ask, so each continuation executor carried its own copy of the check and applied it
 * only to the kind it happened to own. An adapter added later inherited none of it, and a kind whose
 * adapter did not verify — a due timer, for instance — reached its effect with no check having run.
 * Routing every kind through one authority is what makes "unsafe dispatch stays closed" a property
 * of the path rather than a habit each adapter is trusted to keep.</p>
 *
 * <h2>Registration is explicit, and order chooses nothing</h2>
 * <p>Adapters are registered by the composition root and exactly one must claim an item. No owner
 * leaves the item unacknowledged and still claimable; more than one is a configuration error,
 * because either could otherwise perform the effect. An empty registry is legal and declines
 * everything, which is what a deployment that composes no continuation service should do — it still
 * discovers and classifies its outstanding work, it simply sends none of it.</p>
 *
 * <h2>Classification precedes ownership</h2>
 * <p>{@link #admits} is answered before any adapter is consulted, not after. An adapter's
 * {@code canDispatch} is not a pure question — it loads durable state and, for the continuation
 * executors, rebuilds a graph — so asking an adapter about an execution this deployment has already
 * decided it cannot reproduce spends that work to reach a refusal that was already known. Deciding
 * first also means the refusal is reported once, in one place, with the reason intact, instead of
 * disappearing into whichever adapter happened to return {@code false} — and it keeps the
 * refusal out of the recovery loop's park branch, which exists to adjudicate effects rather
 * than to record deployment mistakes.</p>
 */
public final class ExecutionRecoveryCoordinator implements RecoveryDispatcher {

    private final PinnedGraphRecoveryAuthority authority;
    private final List<RecoveryDispatcher> adapters;

    /**
     * Composes the coordinator over its rehydration authority and its registered adapters.
     *
     * @param authority the authority that decides whether an execution may be rebuilt here.
     * @param adapters  explicitly registered recovery adapters; may be empty, never containing null.
     */
    public ExecutionRecoveryCoordinator(PinnedGraphRecoveryAuthority authority,
                                        List<RecoveryDispatcher> adapters) {
        this.authority = Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(adapters, "adapters");
        if (adapters.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("a registered recovery adapter cannot be null");
        }
        this.adapters = List.copyOf(adapters);
    }

    /**
     * The declaration source a recovery sweep must be given, so an ambiguous effect is decided
     * against the document its execution was pinned to rather than against nothing.
     *
     * <p>Returned from here rather than constructed beside the coordinator: two sources built from
     * the same inputs would agree until one of the two composition sites was updated and the other
     * was not, and the parks that followed would look like authors who had declared nothing.</p>
     *
     * @return this coordinator's own authority, as a declaration source.
     */
    public RepeatabilityDeclarations declarations() {
        return authority;
    }

    /**
     * Whether this deployment may act on {@code item}'s execution at all, and if not, whether waiting
     * could change that.
     *
     * <p>This is where the fail-closed gate lives, rather than inside {@link #canDispatch}, because
     * the recovery loop disposes of the two answers differently — see {@link RecoveryDispatcher#admits}.
     * A refusal is reported here, once, with its reason intact; inside {@code canDispatch} it would
     * disappear into a boolean the loop reads as "nothing owns this kind", and an ambiguous attempt
     * would then be parked immediately for a deployment fault, reported as though its author had
     * declared nothing.</p>
     *
     * <p>The classification's own retryability decides which refusal this is, and the mapping is the
     * whole point of keeping the two apart. A document or manifest that is absent, that does not
     * verify, or that this deployment resolves differently will answer the same way on every later
     * sweep, so waiting alone repairs none of them: those are deterministic, and the loop bounds them.
     * A store that could not be read is expected to answer eventually, so that one waits.</p>
     *
     * @param item claimed work item.
     * @return whether the item proceeds, waits, or waits and then parks.
     */
    @Override
    public RecoveryAdmission admits(PendingWork item) {
        Objects.requireNonNull(item, "item");
        RecoveryClassification classification = authority.classify(item.key());
        if (!(classification instanceof RecoveryClassification.Refused refused)) {
            return RecoveryAdmission.admitted();
        }
        authority.report(refused);
        return refused.reason() == RecoveryClassification.Reason.UNAVAILABLE
                ? RecoveryAdmission.retryable(refused.detail())
                : RecoveryAdmission.deterministic(refused.detail());
    }

    /**
     * Whether exactly one registered adapter claims {@code item}.
     *
     * <p>Ownership only. It deliberately does <em>not</em> repeat the classification: the recovery
     * loop asks {@link #admits} first and does not reach this method on a withheld item, and
     * classifying again here cost a second aggregate read and a second manifest verification per
     * item per sweep to re-derive an answer the caller was already holding. A caller that reaches
     * this method without having asked {@link #admits} is asking a different question than the loop
     * asks, and would get an answer about ownership rather than about entitlement.</p>
     *
     * @param item claimed work item.
     * @return {@code true} when exactly one registered adapter claims the item.
     */
    @Override
    public boolean canDispatch(PendingWork item) {
        return owner(item, false) != null;
    }

    /**
     * Hands {@code item} to the adapter that owns it.
     *
     * <p>The classification is not repeated here. The recovery loop asks {@link #admits} and then
     * {@link #canDispatch} immediately before this, and re-reading the manifest and the document
     * again would be a new answer to a question already answered — one that could differ from the
     * answer the loop acted on, which is worse than not asking.</p>
     *
     * @param item           claimed work item.
     * @param idempotencyKey effect identity to present.
     */
    @Override
    public void dispatch(PendingWork item, String idempotencyKey) {
        owner(item, true).dispatch(item, idempotencyKey);
    }

    /**
     * Releases adapter-owned resources after recovery has acknowledged the claimed work.
     *
     * @param item acknowledged work item.
     */
    @Override
    public void afterAcknowledged(PendingWork item) {
        RecoveryDispatcher owner = owner(item, false);
        if (owner != null) {
            owner.afterAcknowledged(item);
        }
    }

    /**
     * Classifies a discovered cohort, one row at a time.
     *
     * <p>Per row rather than as a batch, and that is the whole of "one corrupt item stays
     * inspectable without hiding the rest": a row whose classification throws becomes an
     * {@link RecoveryClassification.Reason#UNAVAILABLE} verdict for that row alone. A single loop
     * that let one failure escape would abandon the scan partway through and report a cohort
     * silently missing every instance after the bad one.</p>
     *
     * @param discovered interrupted instances, typically from
     *                   {@link ExecutionRecoveryService#discoverInterrupted()}.
     * @return one verdict per row, in the order given.
     */
    public List<RecoveryCandidate> classify(List<ProcessInventoryEntry> discovered) {
        Objects.requireNonNull(discovered, "discovered");
        var candidates = new ArrayList<RecoveryCandidate>(discovered.size());
        for (ProcessInventoryEntry entry : discovered) {
            RecoveryCandidate candidate;
            try {
                candidate = authority.classify(entry);
            } catch (RuntimeException unclassifiable) {
                candidate = new RecoveryCandidate(entry, new RecoveryClassification.Refused(entry.key(),
                        RecoveryClassification.Reason.UNAVAILABLE,
                        "the instance could not be classified on this pass"));
            }
            if (candidate.classification() instanceof RecoveryClassification.Refused refused) {
                authority.report(refused);
            }
            candidates.add(candidate);
        }
        return List.copyOf(candidates);
    }

    private RecoveryDispatcher owner(PendingWork item, boolean required) {
        Objects.requireNonNull(item, "item");
        RecoveryDispatcher selected = null;
        for (RecoveryDispatcher candidate : adapters) {
            if (!candidate.canDispatch(item)) continue;
            if (selected != null) {
                throw new IllegalStateException("multiple recovery adapters claim work item "
                        + item.workItemId());
            }
            selected = candidate;
        }
        if (required && selected == null) {
            throw new IllegalStateException("no recovery adapter claims work item " + item.workItemId());
        }
        return selected;
    }
}
