package ai.ravenroot.server.persistence;

import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.ExecutionManifestStore;
import ai.ravenroot.api.persistence.GraphDefinitionStore;
import ai.ravenroot.core.graph.GraphMlLimits;
import ai.ravenroot.persistence.postgresql.PostgresExecutionManifestStore;
import ai.ravenroot.persistence.postgresql.PostgresExecutionStore;
import ai.ravenroot.persistence.postgresql.PostgresGraphDefinitionStore;
import ai.ravenroot.persistence.postgresql.PostgresStoreConfig;
import ai.ravenroot.persistence.sqlite.SqliteExecutionManifestStore;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.persistence.sqlite.SqliteGraphDefinitionStore;
import ai.ravenroot.persistence.sqlite.SqliteStoreLocation;
import ai.ravenroot.persistence.sqlite.SqliteStoreMaintenanceLock;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opens the server's configured execution store and turns adapter diagnostics into a small,
 * path-free startup contract.
 *
 * <p>Both adapters validate and prepare synchronously in their constructors — the SQLite one against
 * its location, the PostgreSQL one against its schema. What belongs here is the composition-root
 * policy around that validation: an enabled store is mandatory, so an unusable location or an
 * unreachable database aborts startup rather than falling back to no store; and an uncaught startup
 * exception must not print a configured path, a JDBC URL, a driver message or a nested adapter
 * exception. Those details can contain deployment topology and credentials and are not needed to
 * distinguish the operator actions exposed by {@link FailureReason}.</p>
 *
 * <h2>This is where the adapter is chosen, and the only place</h2>
 * <p>Selecting an adapter is a composition-root change: core names only ports, and the shared adapter
 * is unreachable from anything but this module. {@link #openOwned} switches on
 * {@link ExecutionStoreConfiguration}'s variants and each branch composes one adapter's three stores
 * over one backing resource — one SQLite directory, or one pooled database.</p>
 *
 * <h2>Why the shared branch has no maintenance lock</h2>
 * <p>{@link SqliteStoreMaintenanceLock} appears only in the single-host branch, and giving the shared
 * branch an analogue would be actively wrong rather than merely unnecessary. It does three things,
 * and the shared store needs the opposite of all three:</p>
 * <ul>
 *   <li><strong>Single-writer exclusion.</strong> The file lock guarantees that one process at a time
 *   owns the database. That guarantee is exactly what the shared store exists to remove: several
 *   replicas addressing one database is the deployment, not the accident, and a lock that admitted
 *   one of them would turn a horizontally scaled deployment into a single-writer one that happens to
 *   have spare pods.</li>
 *   <li><strong>Backup exclusion.</strong> A SQLite backup copies files, so it must exclude the
 *   writer. A PostgreSQL backup is taken by the database's own tooling against a live server, which
 *   is the documented procedure; there is nothing for a Ravenroot-side lock to exclude, and one would
 *   only be able to exclude the wrong participant — the replicas, never the backup.</li>
 *   <li><strong>Pending-recovery detection.</strong> That check reads a marker left by an interrupted
 *   file-level restore. A shared-store restore is a database restore; its interrupted state lives in
 *   the database's own recovery machinery and is not observable, or fixable, from here.</li>
 * </ul>
 */
public final class ExecutionStoreBootstrap {
    private ExecutionStoreBootstrap() {
    }

    /**
     * Opens the configured store together with the process-wide resource that must outlive every
     * audit/store consumer.  The returned owner is the only lifecycle authority.
     */
    public static Opened openOwned(ExecutionStoreConfiguration configuration, Clock clock) {
        return openOwned(configuration, clock, GraphMlLimits.DEFAULTS);
    }

    /** Opens all durable stores with the graph-definition budget chosen by the composition root. */
    public static Opened openOwned(ExecutionStoreConfiguration configuration, Clock clock,
                                   GraphMlLimits graphMlLimits) {
        return openOwned(configuration, clock, graphMlLimits,
                ai.ravenroot.api.persistence.HumanTaskPolicy.DEFAULTS);
    }

    /** Opens all durable stores with the Human Task policy chosen by the composition root. */
    public static Opened openOwned(ExecutionStoreConfiguration configuration, Clock clock,
                                   GraphMlLimits graphMlLimits,
                                   ai.ravenroot.api.persistence.HumanTaskPolicy humanTaskPolicy) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(graphMlLimits, "graphMlLimits");
        Objects.requireNonNull(humanTaskPolicy, "humanTaskPolicy");
        try {
            return switch (configuration) {
                case ExecutionStoreConfiguration.Disabled disabled ->
                        openSingleHost(disabled.location(), false, clock, graphMlLimits, humanTaskPolicy);
                case ExecutionStoreConfiguration.SingleHost singleHost ->
                        openSingleHost(singleHost.location(), true, clock, graphMlLimits, humanTaskPolicy);
                case ExecutionStoreConfiguration.Shared shared ->
                        openShared(shared, clock, graphMlLimits, humanTaskPolicy);
            };
        } catch (RuntimeException failed) {
            throw new StartupException(classify(failed));
        }
    }

    private static Opened openSingleHost(SqliteStoreLocation location, boolean enabled, Clock clock,
                                         GraphMlLimits graphMlLimits,
                                         ai.ravenroot.api.persistence.HumanTaskPolicy humanTaskPolicy) {
        // Preserve the adapter's useful location classification before the maintenance API
        // deliberately reduces its own diagnostics to path-free lock failures.
        location.prepare();
        var maintenanceLock = SqliteStoreMaintenanceLock.acquire(location);
        try {
            SqliteStoreMaintenanceLock.requireNoPendingRecovery(location);
            if (!enabled) {
                return new Opened(null, null, null, () -> { }, maintenanceLock::close);
            }
            var store = new SqliteExecutionStore(location, clock,
                    ai.ravenroot.persistence.sqlite.SqliteStoreConfig.defaults(), humanTaskPolicy);
            // Same database file as the executions that pin these definitions, which is what puts
            // both into one backup snapshot and lets retention decide reachability from the
            // execution rows in the transaction that removes a definition. The store's own
            // reachability query is the authority here, so no additional reference source is
            // composed; see GraphDefinitionReferences for the reference classes that are not yet
            // durable and therefore cannot contribute one.
            GraphDefinitionStore definitions;
            try {
                definitions = new SqliteGraphDefinitionStore(location, clock,
                        ai.ravenroot.api.persistence.GraphDefinitionReferences.NONE,
                        graphMlLimits.maxBytes());
            } catch (RuntimeException failed) {
                store.close();
                throw failed;
            }
            // Same file again, and for the third time the same three reasons: one backup captures
            // an execution together with the manifest it needs, retention can ask whether the
            // instance still exists inside the transaction that removes its manifest, and one
            // schema version describes all three. The adapter's own reachability query is the
            // authority, so no additional reference source is composed.
            ExecutionManifestStore manifests;
            try {
                manifests = new SqliteExecutionManifestStore(location, clock,
                        ai.ravenroot.api.persistence.ExecutionManifestReferences.NONE);
            } catch (RuntimeException failed) {
                try {
                    definitions.close();
                } finally {
                    store.close();
                }
                throw failed;
            }
            return new Opened(store, definitions, manifests,
                    closeInOrder(store, definitions, manifests), maintenanceLock::close);
        } catch (RuntimeException failed) {
            maintenanceLock.close();
            throw failed;
        }
    }

    /**
     * Opens the three shared stores over one pool.
     *
     * <p>One pool for all three, deliberately. They address one database by contract — an accepted
     * execution's definition and manifest are verified inside the transactions that write it — and
     * three pools would triple this replica's connection footprint against a server whose connections
     * are processes, while buying isolation between stores that are not isolated in the first place.</p>
     *
     * <p>The pool is built here and never inside the adapter, which is the whole of ADR 0040's
     * "share ports, not a JDBC core": the adapter is handed a {@code DataSource} it does not inspect,
     * so credentials, TLS material and sizing stay deployment configuration and never become
     * persistence-layer types.</p>
     *
     * <p>There is no {@code enabled == false} arm here because there cannot be one: a disabled store
     * is {@link ExecutionStoreConfiguration.Disabled}, which the parser never produces alongside the
     * shared selector.</p>
     */
    private static Opened openShared(ExecutionStoreConfiguration.Shared configuration, Clock clock,
                                     GraphMlLimits graphMlLimits,
                                     ai.ravenroot.api.persistence.HumanTaskPolicy humanTaskPolicy) {
        SharedStoreConnection connection = configuration.connection();
        var pool = SharedExecutionStoreDataSource.open(connection);
        try {
            var store = new PostgresExecutionStore(pool.dataSource(), clock,
                    PostgresStoreConfig.defaults(), humanTaskPolicy);
            GraphDefinitionStore definitions;
            try {
                definitions = new PostgresGraphDefinitionStore(pool.dataSource(), clock,
                        ai.ravenroot.api.persistence.GraphDefinitionReferences.NONE,
                        graphMlLimits.maxBytes());
            } catch (RuntimeException failed) {
                store.close();
                throw failed;
            }
            ExecutionManifestStore manifests;
            try {
                manifests = new PostgresExecutionManifestStore(pool.dataSource(), clock,
                        ai.ravenroot.api.persistence.ExecutionManifestReferences.NONE,
                        configuration.manifestPinAttempts());
            } catch (RuntimeException failed) {
                try {
                    definitions.close();
                } finally {
                    store.close();
                }
                throw failed;
            }
            // The pool takes the maintenance lease's slot in the owner, and for the same structural
            // reason that slot exists: it is the process-wide resource every store is built on, so it
            // must be released strictly after all three of them. It is not a maintenance lease and
            // excludes nobody — see this class's own explanation of why the shared store must not
            // have one.
            return new Opened(store, definitions, manifests,
                    closeInOrder(store, definitions, manifests), pool::close);
        } catch (RuntimeException failed) {
            pool.close();
            throw failed;
        }
    }

    /** Manifests first, then definitions, then the execution store: nothing observes a released backing store. */
    private static Runnable closeInOrder(ExecutionStore store, GraphDefinitionStore definitions,
                                         ExecutionManifestStore manifests) {
        return () -> {
            try {
                manifests.close();
            } finally {
                try {
                    definitions.close();
                } finally {
                    store.close();
                }
            }
        };
    }

    private static FailureReason classify(RuntimeException failed) {
        if (failed instanceof SqliteStoreMaintenanceLock.MaintenanceLockException lockFailure) {
            return switch (lockFailure.failure()) {
                case BUSY -> FailureReason.MAINTENANCE_BUSY;
                case RECOVERY_PENDING -> FailureReason.RECOVERY_PENDING;
                case UNSAFE_LOCATION -> FailureReason.INVALID_LOCATION;
                case UNAVAILABLE -> FailureReason.UNAVAILABLE;
            };
        }
        ExecutionStoreException storeFailure = ExecutionStoreException.unwrap(failed);
        if (storeFailure == null) {
            return FailureReason.UNAVAILABLE;
        }
        return switch (storeFailure.failure()) {
            case ExecutionStoreFailure.InvalidRequest ignored -> FailureReason.INVALID_LOCATION;
            case ExecutionStoreFailure.NotAuthorized ignored -> FailureReason.NOT_WRITABLE;
            default -> FailureReason.UNAVAILABLE;
        };
    }

    /** Stable, non-sensitive reasons suitable for an uncaught startup diagnostic. */
    public enum FailureReason {
        INVALID_LOCATION,
        NOT_WRITABLE,
        MAINTENANCE_BUSY,
        RECOVERY_PENDING,
        UNAVAILABLE
    }

    /** Exactly-once owner for the stores first and their backing resource second. */
    public static final class Opened implements AutoCloseable {
        private final ExecutionStore store;
        private final GraphDefinitionStore graphDefinitionStore;
        private final ExecutionManifestStore executionManifestStore;
        private final Runnable closeStore;
        private final Runnable releaseBackingResource;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Opened(ExecutionStore store, GraphDefinitionStore graphDefinitionStore,
                       ExecutionManifestStore executionManifestStore,
                       Runnable closeStore, Runnable releaseBackingResource) {
            this.store = store;
            this.graphDefinitionStore = graphDefinitionStore;
            this.executionManifestStore = executionManifestStore;
            this.closeStore = Objects.requireNonNull(closeStore, "closeStore");
            this.releaseBackingResource = Objects.requireNonNull(
                    releaseBackingResource, "releaseBackingResource");
        }

        static Opened forTest(Runnable closeStore, Runnable releaseBackingResource) {
            return new Opened(null, null, null, closeStore, releaseBackingResource);
        }

        public ExecutionStore store() {
            return store;
        }

        /**
         * The durable graph definitions held in the same database, or {@code null} when the store is
         * configured off. Closed with the execution store, definitions first, so nothing can observe
         * a definition store whose database has already been released.
         *
         * @return the composed graph definition store, or {@code null}.
         */
        public GraphDefinitionStore graphDefinitionStore() {
            return graphDefinitionStore;
        }

        /**
         * The durable execution manifests held in the same database, or {@code null} when the store
         * is configured off. Closed first, before the definitions and the execution store, so nothing
         * can observe a manifest store whose database has already been released.
         *
         * @return the composed execution manifest store, or {@code null}.
         */
        public ExecutionManifestStore executionManifestStore() {
            return executionManifestStore;
        }

        /**
         * Guards the interval between acquiring the store and successfully handing its lifecycle
         * to the JVM shutdown hook.  Closing an untransferred guard performs partial-start cleanup.
         */
        public StartupGuard startupGuard() {
            return new StartupGuard(this);
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                closeStore.run();
            } finally {
                releaseBackingResource.run();
            }
        }
    }

    public static final class StartupGuard implements AutoCloseable {
        private final Opened owner;
        private final AtomicBoolean transferred = new AtomicBoolean();

        private StartupGuard(Opened owner) {
            this.owner = owner;
        }

        public void transferToShutdownHook() {
            transferred.set(true);
        }

        @Override
        public void close() {
            if (!transferred.get()) {
                owner.close();
            }
        }
    }

    /**
     * A fail-closed startup result. Deliberately has no cause: otherwise the JVM's uncaught-exception
     * rendering would print the adapter's raw path or JDBC diagnostic after this safe message.
     */
    public static final class StartupException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private final FailureReason reason;

        private StartupException(FailureReason reason) {
            super("Execution store startup failed: " + Objects.requireNonNull(reason, "reason"));
            this.reason = reason;
        }

        public FailureReason reason() {
            return reason;
        }
    }
}
