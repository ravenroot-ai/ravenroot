# ADR 0040: Shared persistence adapters share the ports and the conformance suite, not a JDBC core

- Status: Accepted contract, implementation delivered incrementally
- Date: 2026-09-06
- Supersedes: None. It extends ADR 0014, which describes the operational surface of the local
  single-host store, to the case where the processes sharing execution state are not on one host
- Superseded by: None
- Public references: [ADR 0014](0014-local-execution-store-operational-surface.md),
  [ADR 0031](0031-durable-canonical-graph-definitions.md),
  [ADR 0034](0034-immutable-resolved-execution-manifests.md),
  [ADR 0038](0038-deployment-lifecycle-generations-and-typed-command-outcomes.md),
  [Persistence, lifecycle, and recovery](../docs/operator-guide/persistence-lifecycle.md),
  [Shared PostgreSQL persistence](../docs/reference/postgresql-persistence.md)

## Context

Two adapters implement the execution-store port today, and they were built to be aligned by a shared
conformance suite rather than by shared code: the in-memory adapter in the core, and the durable
single-host SQLite adapter. Both encode the same check ordering with the same method names, and
`ravenroot-persistence-testkit` is what keeps them honest — a capability an adapter declares is a
capability the suite asserts, and one it does not declare is one the suite visibly skips.

Both adapters coordinate processes within a boundary that a third kind of deployment does not have.
The in-memory adapter coordinates one JVM. The SQLite adapter coordinates several processes on one
host, because that is the reach of a POSIX advisory lock, and its own package documentation states
that placing its database on a network filesystem does not degrade that guarantee but falsifies it. A
deployment whose Ravenroot processes are on different hosts — several pods, a rolling upgrade that
overlaps two generations, a control plane beside a worker fleet — has no adapter at all.

The question this record answers is not whether to add one. It is what a second durable adapter
shares with the first. The obvious answer is a JDBC base module holding what looks common: aggregate
folding, transition validation, corruption detection, failure mapping, the migration runner. That
answer does not survive contact with the two adapters.

Most of what looks shared is not in the adapter. The fold itself lives in the port, in
`ExecutionTransition`, and reconstruction validity lives in the aggregate's own canonical
constructors. What an adapter holds is the ordering, the fencing comparison, the expectation switch,
and the SQL. Of the single-host adapter's execution store, roughly a fifth is dialect-independent
contract logic — and about half of that fifth is correct only because SQLite admits one writer to the
whole database for the length of a transaction. Under that lock, an adapter may read state and then
write a decision derived from it with nothing in between, and the single-host adapter does so in
every mutation: the lease claim, the revision increment, the delivery-attempt counter, the journal
offset, the watermarks. Each of those is a lost update the moment the competing writer is a different
process against a server that does not serialize them. They cannot be inherited. They have to be
rewritten as row-level compare-and-set, which is different code rather than different syntax.

The remainder that genuinely could be shared is small, and it is already duplicated on purpose: the
in-memory adapter reimplements it so that the suite's assertions execute against two adapters instead
of being skipped into invisibility on one. That duplication is the existing, working answer to the
same question.

## Decision

**A shared persistence adapter is a standalone module implementing the existing ports.** It shares
with every other adapter the port vocabulary, the closed `StoreCapability` set, the sealed failure
vocabularies, and the `ravenroot-persistence-testkit` contracts. It shares no production code with
another adapter, and no base class exists that an adapter could inherit. A third adapter inherits a
suite it must pass, not a class it must read in order to learn which methods are safe to override.

**Correctness across processes rests on database transactions and row-level compare-and-set, never on
a process-local lock and never on an exclusive lock over the whole store.** Every mutation that reads
state and then writes a decision derived from it does so under a row lock taken in the same
transaction, or as a conditional update whose predicate carries the value the decision was made on.
This is the structural difference from the single-host adapter, and it is the reason a port of that
adapter's code would compile, pass a single-threaded conformance run, and lose updates in production.

**All durable stores of one deployment address one database.** Acceptance stays ordered rather than
distributed — the graph definition is committed, then the manifest that pins it, then the batch that
references both — because a later step can check the earlier one's rows inside its own transaction.
That ordering is what makes the guarantee that an accepted execution has its exact definition and
manifest; it stops holding when the rows can fail independently, so splitting the stores across
databases is not a deployment variant but a removal of the mechanism.

**Schema migration is serialized by a database advisory lock, and the version lives in a table.**
The single-host adapter gets concurrent-startup safety from the same write lock that makes its
read-then-write sequences safe, and neither is available here. Idempotent DDL alone does not
substitute: two processes can both observe the same version, both decide to apply the next migration,
and the loser then fails on a constraint the winner has just created, turning an ordinary concurrent
start into a crash loop. Each adapter's version sequence is its own and starts at 1 with its current
shape; the sequences are not aligned across adapters, because a shared number would imply a
correspondence nothing maintains.

**A capability an adapter declares is a capability its deployment must not invalidate.** The
single-host adapter's boundary is the filesystem its database sits on. This one's boundaries are that
all its stores address one database, and that lease evaluation tolerates clock skew between hosts only
up to the budget the port publishes as `maxClockSkew`. Synchronised time is therefore an operational
requirement of a multi-host deployment rather than a recommendation, and no configuration substitutes
for it.

**The first such module is the PostgreSQL adapter**, `ravenroot-persistence-postgresql`. It is in the
reactor so its conformance run happens on every full build, and it is deliberately not a dependency of
the server or the distribution: a deployment that wants it adds the module and supplies it a
`DataSource` that a composition root has already built. No credential, URL or certificate is visible
to, or storable by, adapter code.

## Consequences

The single-host adapter is unchanged. Its contract, its schema, its crash matrix and its stated
boundary are exactly as they were, and no part of this decision refactors code that currently passes a
real-SIGKILL test suite.

Two adapters now carry versions of the same logic, and a change to a shared rule — a boundary
convention, the check ordering, a new lifecycle status — must be made in each. This is a real cost and
it is accepted deliberately, because the conformance suite is what detects the divergence, and it
detects it as a failing assertion rather than as a subclass that compiles.

An outcome the single-host adapter records as unreachable becomes ordinary here. A connection can be
lost between a client sending `COMMIT` and the acknowledgement arriving, and in that window the
transaction may or may not have been applied; retrying is unavailable, because a retry of a committed
transaction is a duplicate. The port already has the vocabulary for this, and this adapter is the first
that reaches it in practice rather than in principle.

Composition remains adapter-typed. The server's persistence configuration names a single-host store
location today, so selecting an adapter is a change to that composition root and is delivered
separately from the adapter itself. Until it lands, the module is usable by a deployment that composes
its own stores, and by tests.

There is no capability that says "coordinates across hosts". `CROSS_PROCESS_LEASE` is declared by the
single-host adapter for one host, and the conformance suite can only demonstrate that two store
instances against one store identity agree — which the single-host adapter also satisfies. A caller
that must distinguish the two therefore cannot do so from the declaration alone today. Adding a member
to that closed set is a port change and is deliberately not taken here.
