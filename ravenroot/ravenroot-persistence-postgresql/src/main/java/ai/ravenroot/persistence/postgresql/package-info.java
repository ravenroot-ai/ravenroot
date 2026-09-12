/**
 * The durable shared execution store: PostgreSQL behind the execution-store port (ADR 0040).
 *
 * <h2>What this adapter is for</h2>
 * <p>It is the store for a deployment whose Ravenroot processes are not on one host: several server
 * pods, a rolling upgrade that overlaps two generations, or a control plane and a worker fleet that
 * must agree on which process instance is being advanced. It is the first adapter in this repository
 * whose exclusion is a property of the database rather than of a filesystem, and therefore the first
 * whose {@link ai.ravenroot.api.persistence.StoreCapability#CROSS_PROCESS_LEASE} extends past the
 * machine the process happens to be running on.</p>
 *
 * <p>The execution store is the largest thing here but not the only one. The graph-definition and
 * manifest stores sit beside it because acceptance is ordered across all three, and
 * {@link ai.ravenroot.persistence.postgresql.PostgresDeploymentRegistry} joins them because a
 * deployment aggregate is exactly the kind of state several hosts contend for: its lease, its fencing
 * token and its lifecycle generation are decided by the same row locks, on the same connection, as
 * the executions that deployment dispatches.</p>
 *
 * <h2>Correctness rests on the database, never on the process</h2>
 * <p>Every mutation that reads state and then writes a decision derived from it does so under a
 * row-level lock taken in the same transaction, or as a conditional update whose {@code WHERE} clause
 * carries the value the decision was made on. The uniqueness rules that span a whole tenant rather
 * than one process instance — a handler's or a human task's correlation and deduplication keys, and a
 * traversal's live hold — take a third shape, because no row either competitor holds is shared: the
 * partial unique index decides the winner and the loser reads it back to say which rule it hit. There
 * is no process-local lock anywhere in this
 * package, and there could not be one that helped: the second writer is in a different JVM, usually
 * on a different host. This is the single structural difference from the single-host SQLite adapter,
 * which takes the whole database's write lock for the length of every batch and can therefore read
 * and then write with nothing in between. Reproducing that adapter's read-then-write sequences here
 * would compile, pass a single-threaded conformance run, and lose updates in production under
 * contention, which is the one failure this adapter exists to make impossible.</p>
 *
 * <h2>Where it must not be deployed</h2>
 * <p><strong>All durable stores of one deployment must address one database.</strong> Acceptance is
 * ordered rather than distributed: a graph definition is committed, then the manifest that pins it,
 * then the batch that references both, and the deployment registry that decides which version is
 * meant to be running is read in the same place. That ordering is safe because a later step can
 * check the earlier one's row inside its own transaction, and it stops being safe the moment the
 * rows live in databases that can fail independently. Splitting them does not weaken a guarantee
 * gradually; it removes the only mechanism by which an accepted execution is known to have its exact
 * definition and manifest.</p>
 *
 * <p><strong>Lease correctness is bounded by clock skew between hosts.</strong> The store evaluates
 * expiry on its injected {@link java.time.Clock}, which with several processes means whichever
 * process ran the query. Two hosts whose clocks differ by more than
 * {@link ai.ravenroot.api.persistence.ExecutionStore#maxClockSkew()} can disagree about whether a
 * lease is live. Synchronised time is an operational requirement of this adapter, not a nicety, and
 * no configuration of it substitutes for one.</p>
 *
 * <h2>Durability</h2>
 * <p>{@code DURABLE} is declared on the database's commit guarantee, so it is true exactly to the
 * extent that {@code synchronous_commit} is on. A deployment that turns it off keeps every
 * transactional property this adapter asserts and loses the one word: a commit reported as applied
 * may not survive the loss of the server. That distinction is invisible to a kill test, which ends a
 * client and not the database, so it is stated here rather than discovered.</p>
 *
 * <h2>Tenancy</h2>
 * <p>Every row carries a tenant, every primary key leads with it, and every query filters on it. A
 * key belonging to another tenant is answered as a missing one, never as a refusal, so the store is
 * not an existence oracle across a tenant boundary.</p>
 *
 * <h2>Not in this package</h2>
 * <p>Connection pooling, credential resolution, TLS configuration, backup, restore and retention
 * scheduling. The adapter is handed a {@link javax.sql.DataSource} that a composition root has
 * already built, so no credential, URL or certificate is ever visible to, or storable by, this
 * code.</p>
 */
package ai.ravenroot.persistence.postgresql;
