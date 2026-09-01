/**
 * The durable local execution store: SQLite in WAL mode behind the ADR 0010 port (PERS-03).
 *
 * <h2>What this adapter is for</h2>
 * <p>It is the default store for a single host — a developer machine, a single-node deployment, an
 * embedded or edge installation — where an operator wants execution state to survive a restart
 * without running a database server. It is the first adapter in this repository to declare
 * {@link ai.ravenroot.api.persistence.StoreCapability#DURABLE} and
 * {@link ai.ravenroot.api.persistence.StoreCapability#CROSS_PROCESS_LEASE}, and therefore the first
 * to execute the conformance assertions gated on them.</p>
 *
 * <h2>Where it must not be deployed</h2>
 * <p><strong>The database file must live on a local filesystem.</strong> SQLite's cross-process
 * exclusion is built on POSIX advisory locks, and those are unreliable on NFS, SMB and most network
 * or distributed filesystems: locks may be silently ignored, cached, or lost on a client reconnect.
 * The consequence is not degraded performance. It is that
 * {@link ai.ravenroot.api.persistence.StoreCapability#CROSS_PROCESS_LEASE} becomes false — two
 * processes can each hold what they believe is the same exclusive lease, each with a fencing token it
 * believes is current, and the fence that exists to make split brain impossible stops working. A
 * store that declares a capability its deployment has invalidated is worse than one that declares
 * less, because every caller is entitled to rely on the declaration.</p>
 *
 * <p>The same boundary applies to scale: this adapter coordinates processes on <em>one</em> host,
 * because that is the reach of a file lock. Several hosts sharing execution state is PERS-08's
 * problem, and no configuration of this adapter answers it.</p>
 *
 * <h2>Durability</h2>
 * <p>{@link ai.ravenroot.persistence.sqlite.SqliteStoreConfig.SynchronousMode#FULL} is the default,
 * so a commit is fsynced before {@code apply} completes. It can be configured down, and doing so
 * narrows what {@code DURABLE} means: under {@code NORMAL} in WAL mode a committed transaction
 * survives process death but not host failure, because the commit is not synced until the next
 * checkpoint. That distinction is invisible to a kill test — a {@code SIGKILL} leaves the page cache
 * intact — which is precisely why the default is the strong one rather than the fast one.</p>
 *
 * <h2>Tenancy</h2>
 * <p>Every row carries a tenant, and every query filters on it. A deployment realises physical
 * isolation by giving each tenant its own database file and its own store instance; the tenant
 * column is what makes a single-file deployment behave
 * indistinguishably from that, and what makes the conformance suite's multi-tenant assertions
 * meaningful against one file. The port carries the tenant opaquely and neither reading is imposed
 * here.</p>
 *
 * <h2>Not in this package</h2>
 * <p>Database path policy, retention scheduling, backup and restore. Those are operational concerns
 * outside this adapter; what lives here is the adapter and the semantics it
 * declares.</p>
 */
package ai.ravenroot.persistence.sqlite;
