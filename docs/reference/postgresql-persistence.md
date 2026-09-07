# Shared PostgreSQL persistence

The shared PostgreSQL adapter is the durable store for a deployment whose Ravenroot processes are not
on one host. Use it when several server instances must agree on which process instance is being
advanced: several pods behind one service, a rolling upgrade that overlaps two generations, or a
control plane beside a worker fleet.

The single-host store remains the right choice for a developer machine, a single-node deployment, or
an embedded installation. See [Persistence, lifecycle, and recovery](../operator-guide/persistence-lifecycle.md)
for the procedures that are common to both. The architecture decision record collection in the
repository explains why the two adapters share their contract and not their code.

## Selecting this store

A deployment chooses its execution store with `RAVENROOT_EXECUTION_STORE`. It is `sqlite` when unset,
which is the single-host store and the behaviour every existing deployment already has; setting it to
`postgresql` selects the store this page describes. Any other value refuses to start rather than
falling back, because a mistyped selector that quietly kept the old store is the failure this setting
exists to prevent.

| Variable | Default | Meaning |
|---|---|---|
| `RAVENROOT_EXECUTION_STORE` | `sqlite` | `sqlite` or `postgresql`. Anything else refuses startup. |
| `RAVENROOT_EXECUTION_STORE_URL` | none | The JDBC URL, required under `postgresql`. Must begin `jdbc:postgresql:`. Never logged. |
| `RAVENROOT_EXECUTION_STORE_USER` | unset | Database role, when the URL does not carry it. |
| `RAVENROOT_EXECUTION_STORE_PASSWORD` | unset | Never logged, never trimmed, and never rendered by any diagnostic. |
| `RAVENROOT_EXECUTION_STORE_POOL_SIZE` | `10` | Connections this replica may hold. See the pool sizing section. |
| `RAVENROOT_EXECUTION_STORE_POOL_TIMEOUT_MS` | `10000` | How long a caller waits for a connection. Must stay below the statement timeout, so that waiting for a connection is distinguishable from waiting on a lock. |
| `RAVENROOT_WORKER_ID` | the host name | The replica half of the identity every lease is taken under. |
| `RAVENROOT_EXECUTION_LEASE_TTL_SECONDS` | `30` | How long a replica's claim on an execution outlives its last renewal. Bounded by what the store publishes and required to exceed the clock-skew budget. |

`RAVENROOT_EXECUTION_STORE_DIR` belongs to the single-host store, and setting it while selecting this
one refuses startup instead of being ignored: the two name different stores, and a deployment that set
both has not decided which it wants.

### Which replica holds an execution

Every claim on an execution is taken under an identity of the form `<replica>#<incarnation>/<role>`,
and it is what `ownerWorkerId` reports in the process inventory, so an operator reading the inventory
can name the pod. The replica half comes from `RAVENROOT_WORKER_ID`, defaulting to the host name; in
Kubernetes, set it from the pod name.

The incarnation is minted fresh at every start, and it — not the name — is what makes an identity
unique. A name that repeats across restarts, or two pods misconfigured with one name, would otherwise
each look like the other renewing its own claim, which is the single condition the fence exists to make
impossible. The role half separates the runtime that advances an execution from the recovery sweep
that reclaims abandoned work, because a sweep sharing an identity with its own runtime would reclaim
the work that runtime is still doing.

Work held by a replica that never returns needs no cleanup: a claim is evaluated against the store's
clock when the next claimant asks for it, so an expired one is simply no obstacle.

### Several replicas

Selecting this store does not by itself enable several replicas, and a deployment that configures more
than one is refused at startup today. Distributed request ownership, the readiness handoff that must
precede it, and the deployment manifests that would express it are delivered separately; until they
are, this store is the durable, shared-capable foundation rather than a supported multi-replica
topology. The refusal names the specific combination it will not run.

## Supported topology

| Property | Contract |
|---|---|
| Server version | Tested against PostgreSQL 17. Nothing in the adapter requires a version beyond 9.5, but older servers are untested. |
| Databases per deployment | Exactly one. Every durable store addresses the same database. |
| Isolation level | `READ COMMITTED` for ordinary work; `REPEATABLE READ` for reads assembled from several statements. |
| Deployments per database | One. A second deployment needs its own database. A separate schema isolates the tables but not the migration advisory lock, which is database-wide. |
| Clock discipline | Every host running Ravenroot must be time-synchronised. |

**One database is a contract, not a convenience.** An accepted execution is guaranteed to have its
exact graph definition and its resolved execution manifest because each step verifies the previous
step's rows inside its own transaction. Splitting the stores across databases that can fail
independently does not weaken that guarantee gradually; it removes the mechanism that provides it.

**Time synchronisation is an operational requirement.** Lease expiry is evaluated on the clock of
whichever process runs the query. Two hosts whose clocks differ by more than the store's published
clock-skew budget can disagree about whether a lease is live, and no configuration of the adapter
compensates for that. Run NTP or an equivalent on every host.

## Credentials, TLS, and connection ownership

The adapter is handed a `javax.sql.DataSource` that the deployment has already built. It never sees a
JDBC URL, a password, or certificate material, and it stores none: no credential, and no reference to
one, is written to any table. Supply the connection details to the composition root through the
platform's own secret mechanism, exactly as for any other credential — see
[Credentials and egress](../operator-guide/credentials-egress.md).

Require TLS at the database rather than trusting the network. Configure the driver with
`sslmode=verify-full` and a certificate authority the deployment controls, so that a server
presenting an unexpected certificate is refused rather than accepted. `sslmode=require` encrypts the
connection but authenticates nothing, and is not sufficient for a database that holds execution state.

Grant the adapter's role `SELECT`, `INSERT`, `UPDATE` and `DELETE` on the store's tables, and
`CREATE` on its schema. `CREATE` is needed on **every** start, not only on the one that migrates: the
adapter re-asserts its version and history tables each time it opens, so a role that has had `CREATE`
revoked after the initial migration fails at startup with an authorization failure rather than
running against a schema it cannot verify.

## Connection pool sizing

The adapter does not pool. Size the pool the deployment supplies it as follows.

- **Start from concurrency, not from instance count.** Each in-flight store operation holds one
  connection for the length of its transaction. The floor is the number of operations one instance
  runs concurrently — its worker parallelism plus its request-handling parallelism — and the pool must
  be at least that or workers will queue behind each other rather than behind the database.
- **Multiply by instances, then check against the server.** The sum of every instance's pool must stay
  comfortably below the server's `max_connections`, leaving headroom for administrative sessions and
  for a rolling upgrade during which old and new instances overlap. Exceeding it surfaces as startup
  or operation failures reported as unavailability.
- **Prefer a connection multiplexer over a very large pool** when the instance count makes the
  arithmetic uncomfortable. PostgreSQL connections are processes, and a pool far larger than the
  server's core count degrades throughput rather than improving it.
- **Set a pool acquisition timeout below the store's statement timeout.** A caller waiting
  indefinitely for a connection is indistinguishable, from the outside, from one waiting on a lock,
  and the two need different operator responses.

## Contention and timeouts

The adapter sets two bounds on every connection it uses for ordinary work, from its own configuration
rather than from the server's, because a store cannot publish a bound it does not control. The
connection that runs schema migration is deliberately excluded from both: it waits on the advisory
lock for as long as another process's migration takes, and a bound there would turn an ordinary
concurrent start into a failure.

- `lock_timeout` bounds how long a statement waits for a row lock another transaction holds. It is
  never zero: PostgreSQL reads zero as "wait forever", which would let a worker block on a row whose
  holder has already gone.
- `statement_timeout` bounds any single statement, so a pathological query cannot hold a row lock for
  longer than that.

Transactions that PostgreSQL aborts with a serialization failure or a deadlock are retried inside the
adapter, a bounded number of times, and then reported as unavailability. A retry is safe because a
retried transaction re-reads the state it decides on.

## Schema migration

Migrations are forward-only and run automatically at store construction. There is no rollback script:
a rollback is a promise that data written under the newer schema can be expressed under the older one,
which is false for any migration that adds information, and an untested rollback is one that does not
work. Recovery from a bad migration is a restore.

**Concurrent startup is safe.** Each migration step takes a transaction-scoped advisory lock before
reading the installed version, so processes starting at the same moment cannot interleave their DDL.
A process that arrives second waits, then observes the version the first installed and finds nothing
to do. Because the lock is transaction-scoped, a process killed mid-migration cannot leave it held.

**A database ahead of the binary is refused.** An older instance opening a newer schema would write
rows the newer schema no longer means, silently, and the damage would surface much later. During a
rolling upgrade this means old instances fail to start once the new schema is installed, which is the
guard working rather than an incident.

Two tables record the state: `store_schema_version` holds the authoritative version, and
`store_schema_history` records what ran and when, for an operator inspecting a database they did not
migrate themselves.

The schema arrives in steps rather than in one, so an installation created by an earlier build is
upgraded in place rather than rebuilt. Each step is applied once, in order, and recorded; a database
already carrying the earlier steps keeps its rows untouched.

Plan a migration as an ordinary database change: take a backup first, verify it restores, and expect
the upgrade to be one-way.

## Backup and restore

Use PostgreSQL's own tooling. The offline bundle described in
[Backup and recovery bundle](backup-recovery.md) captures a single-host store's files and does not
apply here.

- **Take a consistent snapshot of the whole database**, not of selected tables. Referential integrity
  across definitions, manifests, executions, results, events and retention metadata is what makes a
  restored store recoverable, and a partial dump breaks it in ways that only appear when an execution
  is resumed.
- **Restore into a database at the same schema version or older**, then let the adapter migrate
  forward. Restoring a newer dump under an older binary is refused by the downgrade guard.
- **Verify the restore before directing traffic at it**: check that `store_schema_version` reads the
  expected version, and that a known process instance loads.
- **Point-in-time recovery is supported by the database, not by the adapter.** Continuous archiving
  gives a deployment a recovery point between snapshots; the store has no separate journal an operator
  should replay by hand.

### Restoring in practice

A **full restore** needs no special handling: `pg_dump` writes every table's rows before it creates any
key or foreign-key constraint, so nothing is enforced while the rows are loading and the order the
tables happen to be dumped in cannot matter. Neither `--disable-triggers` nor a schema-and-data split
is required, and neither should be used.

A **data-only restore into an already-migrated database** also needs no `--disable-triggers`, because
this schema's references form a tree with no cycle and `pg_dump --data-only` orders the tables by that
dependency. Two things do have to be handled, and both fail loudly rather than silently:

- Exclude the schema's own bookkeeping tables from the dump. The target writes them when it migrates,
  and the version table holds a single row that the restore collides with.
- Empty the target of rows the migration itself seeded. A freshly migrated database is not an empty
  one — the schema seeds a store-global row so that every reader finds one rather than having to decide
  what an absent row means — and restoring over it violates that row's key.

Pass `--exit-on-error` to `pg_restore` in either case. Without it the restore continues past a failed
statement and finishes having applied only part of the dump; it does report this — the exit status is
1 and the last line reads `errors ignored on restore: N` — but by then the damage is done, and the
flag is what stops it at the first error instead.

The failure that *is* silent belongs to the other tool. A plain-format dump is restored with `psql`,
which by default reports every error and then exits 0, so a restore that loaded nothing at all is
indistinguishable from a clean one. Pass `-v ON_ERROR_STOP=1` whenever restoring that way.

Restore as a role that owns the schema, or pass `--no-owner` and `--no-acl`: a restore performed by a
role that cannot reassign ownership fails on the ownership statements rather than on the data.

### What integrity means here

The store's referential integrity spans more than its foreign keys. An execution manifest's digest is
derived across its own row *and* its node-package rows, so a restore that brought the manifests and
dropped their packages violates no constraint, loads no row incorrectly, and is still wrong — the
manifest is refused with a digest mismatch when it is next read. A restore is therefore verified by
reading the store back through its own interfaces, not by counting rows or trusting that the database
raised nothing.

## Retention

Retention windows are configuration of the adapter and are enforced by explicit purge operations,
never as a side effect of a read. Each window publishes a per-tenant floor saying how far back an
answer is still complete, so a caller that finds nothing can tell "never existed" from "expired by
policy" — those demand opposite responses.

Terminal process instances are retained at least as long as their own events, so a replayed event
always resolves to an instance the inventory can still describe.

## Operational health

- **Readiness** should depend on the store answering, not merely on the pool being constructed. An
  instance whose database is unreachable must not accept work.
- **Watch for retry pressure.** A rising rate of serialization failures and deadlocks means
  contention has moved from transient to structural; the answer is usually capacity or a partition of
  work across tenants, not a longer retry budget.
- **Watch lock waits.** Statements ending at `lock_timeout` indicate a holder that is alive and slow,
  which is a different diagnosis from a database that is unreachable, and the store's reason text
  keeps them apart.
- **Watch connection saturation**, since exhausting the server's connections presents to callers as
  unavailability and is not distinguishable from an outage without server-side metrics.
- **Autovacuum matters here.** Execution state is updated repeatedly, so dead tuples accumulate in
  proportion to write rate; leave autovacuum enabled and monitor table bloat on the busiest tables.

## Durability and high-availability limits

- **`DURABLE` is exactly as true as `synchronous_commit`.** With it off, a commit the store has
  already reported as applied may not survive the loss of the server. This is invisible to any test
  that kills a client rather than the database, so it is stated rather than discovered.
- **A synchronous replica bounds data loss on failover; it does not remove it.** Configure
  `synchronous_standby_names` if the deployment's recovery point objective is zero, and accept the
  write-latency cost that comes with it.
- **Failover is not transparent to in-flight transactions.** A connection lost while a commit is in
  flight leaves that transaction's outcome genuinely unknown to the client, and the store reports it
  as such rather than guessing. A caller that receives an unknown outcome resolves it by reading the
  store, which is the only authority.
- **Split-brain protection comes from the database being single-writer.** Two Ravenroot instances
  cannot both advance a process instance, because the fencing token that authorises the advance is
  compared and incremented under a row lock. Pointing two instances at two databases that replicate
  asynchronously removes that guarantee entirely.
