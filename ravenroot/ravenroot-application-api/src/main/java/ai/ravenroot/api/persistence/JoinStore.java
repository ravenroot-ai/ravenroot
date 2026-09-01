package ai.ravenroot.api.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * The engine-neutral port for fan-in join state (CORE-03).
 *
 * <p>{@link PendingWork} named this port before it existed: fan-in "is state, not work, and it
 * models arrivals that precede invocation creation; CORE-03 adds it as a separately keyed
 * compare-and-set resource". This is that resource. It is a <em>separate</em> port from
 * {@link ExecutionStore} because {@link ExecutionStore} declares in its own contract that it does
 * not model fan-in, and widening it would have made every existing adapter's conformance evidence
 * stale.</p>
 *
 * <p>No Pekko, Akka or other framework type appears here, and none may be added. The same asynchrony
 * rule as {@link ExecutionStore} applies: {@link CompletionStage} governs operations that touch
 * stored state, and static self-description is synchronous.</p>
 *
 * <h2>Compare-and-set, and why the port has no "arrive" operation</h2>
 * <p>The port deliberately exposes {@link #compareAndSet(JoinRecord)} rather than a semantic
 * {@code arrive(key, branch)}. Quorum arithmetic, timeout policy and duplicate handling are
 * <em>runtime</em> decisions that depend on the graph — a store cannot evaluate "is the quorum still
 * reachable" without knowing the branch count, which is graph topology it has no business holding.
 * Putting the decision in the store would also make every future adapter re-implement it, and two
 * adapters that disagreed about it would both pass a conformance suite written against the port's
 * own vocabulary.</p>
 *
 * <p>So the runtime reads, decides, and writes back under the revision it read. Exactly one
 * concurrent writer wins; the losers re-read and re-decide. That is what isolates concurrent
 * executions of the same join across threads <em>and</em> across processes, which an in-process lock
 * cannot do.</p>
 *
 * <h2>Retention</h2>
 * <p>Join records are <strong>not</strong> self-expiring. A join that settles is discarded by the
 * runtime when its traversal ends ({@link #discard(JoinKey)}); a join whose runtime died is
 * discarded by {@link #purgeSettledBefore(String, Instant)}. Both are required. Without the first
 * the store grows once per join per traversal on the ordinary path; without the second it grows once
 * per join per crash, forever, which is the failure mode that only shows up in production.
 * {@link #recordCount(String)} exists so that growth is observable before it becomes an incident.</p>
 */
public interface JoinStore extends AutoCloseable {

    /**
     * Whether this adapter's records survive process death. Static self-description, therefore
     * synchronous, matching {@link ExecutionStore#capabilities()}.
     *
     * <p>An in-memory adapter must answer {@code false}. A conformance suite skips the restart
     * assertions for a non-durable adapter and never skips them for a durable one.</p>
 * @return whether settled join state survives process restart.
     */
    boolean durable();

/**
 * Reads the current record, or empty when this join has never been written.
 * @param key the stable key used to identify the requested resource.
 * @return stored join record, or empty when no join has been opened for the key.
 */
    CompletionStage<Optional<JoinRecord>> load(JoinKey key);

    /**
     * Writes {@code desired} if and only if the stored revision is {@code desired.revision() - 1},
     * returning the record as stored.
     *
     * <p>A {@code desired} whose revision is {@link JoinRecord#ABSENT_REVISION} plus one is a
     * creation and requires that nothing is stored. Any mismatch fails the stage with
     * {@link JoinStoreException.Reason#CONCURRENCY_CONFLICT}; the caller must re-read, because the
     * decision it computed was made against state that has since moved.</p>
 * @param desired requested target state.
 * @return record after the expected revision transition succeeds.
     */
    CompletionStage<JoinRecord> compareAndSet(JoinRecord desired);

    /**
     * Removes the record for {@code key}, returning whether one was present.
     *
     * <p>Called by the runtime when the traversal ends, which is the point at which no further
     * arrival for this join is possible and retaining it can no longer change any decision. It is an
     * unconditional delete rather than a compare-and-set: the traversal is over, so there is no
     * concurrent decision left to lose a race against.</p>
 * @param key the stable key used to identify the requested resource.
 * @return whether an existing join record was removed.
     */
    CompletionStage<Boolean> discard(JoinKey key);

    /**
     * Every join of {@code tenantId} still in {@link JoinPhase#OPEN}.
     *
     * <p>This is the recovery entry point: after a restart the runtime has no memory of which joins
     * were partially satisfied, and this is how it learns. Tenant-scoped for the reason
     * {@link ExecutionStore#leases(String)} gives — under physical tenant isolation an unscoped
     * enumeration answers for whichever pools happen to be open.</p>
     *
     * <h4>What {@code OPEN} means now: no longer only "unfinished"</h4>
     * <p>A join that re-arms — the default — never writes {@link JoinPhase#SATISFIED}, because it may
     * fire again on the next lap of a cycle. So a record returned here is in <em>one of two</em>
     * states, and a caller that assumes the first will resume work that has already run:
     * <ul>
     *   <li>the join has never fired, or has fired and is now filling a later iteration — the classic
     *       reading, and still the common one;</li>
     *   <li>the join has fired and the traversal then died, so nothing is outstanding at this join at
     *       all. This is the case that used to be {@code SATISFIED} and is now indistinguishable by
     *       phase alone.</li>
     * </ul>
     * <p>{@link JoinRecord#firedThrough()} is what tells them apart, together with the branch keys:
     * bucket {@code firedThrough + 1} is the iteration still being filled, and a record whose newest
     * branch keys all belong to a bucket at or below {@code firedThrough} has nothing pending. A
     * {@code null} marker on an {@code OPEN} record means the join never fired, which is what every
     * record written before the firing marker existed says.</p>
 * @param tenantId the stable tenant id used to identify the requested resource.
 * @return open joins belonging to the tenant.
     */
    CompletionStage<List<JoinRecord>> openJoins(String tenantId);

    /**
     * How many records are retained for {@code tenantId}, in every phase.
     *
     * <p>Present for the same reason {@link ExecutionStore#idempotencyRecordCount(String)} is: a
     * retention bound that cannot be observed is a bound nobody can prove holds, and the failure is
     * silent until the disk fills.</p>
 * @param tenantId the stable tenant id used to identify the requested resource.
 * @return number of retained join records in the tenant scope.
     */
    CompletionStage<Long> recordCount(String tenantId);

    /**
     * Removes {@code tenantId}'s terminal records settled strictly before {@code cutoff}, returning
     * how many were removed, and never removes a record in {@link JoinPhase#OPEN}.
     *
     * <p>This is the crash backstop. {@link #discard(JoinKey)} is driven by a live runtime finishing
     * a traversal; a runtime that dies mid-traversal discards nothing, so without this the store
     * accumulates one orphan per join per crash with nothing that ever removes it. An {@code OPEN}
     * record is never purged by age, because "old and unfinished" is exactly the state recovery
     * exists to resume — purging it would silently convert a recoverable join into one that
     * re-executes its branches from zero.</p>
     *
     * <p><b>This now reclaims strictly less, and the shortfall is deliberate.</b> A join that
     * re-arms never becomes terminal, so the orphan of a join that had already <em>fired</em> is now
     * {@code OPEN} and is left here rather than removed. That is not a regression of this operation:
     * by phase alone it is genuinely indistinguishable from a join still waiting, and this operation
     * must not guess — a wrong guess deletes the record of a legitimately slow join and strands its
     * traversal. Deciding whether such a record is finished needs {@link JoinRecord#firedThrough()}
     * and the traversal's own state, which belongs to recovery (PERS-04) and not to a sweep by age.
     * No reclamation path currently exists for those orphans.</p>
 * @param tenantId the stable tenant id used to identify the requested resource.
 * @param cutoff oldest completion instant to retain.
 * @return number of settled join records removed before the cutoff.
     */
    CompletionStage<Long> purgeSettledBefore(String tenantId, Instant cutoff);

    /**
     * Releases what the process owns and no join state, mirroring {@link ExecutionStore#close()}.
     * A non-durable adapter discards everything because it has nothing durable to keep, which is the
     * capability differing rather than the contract differing.
     */
    @Override
    void close();
}
