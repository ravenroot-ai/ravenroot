package ai.ravenroot.server.persistence;

import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.ExecutionManifestStore;
import ai.ravenroot.api.persistence.GraphDefinitionStore;
import ai.ravenroot.core.graph.GraphMlLimits;
import ai.ravenroot.persistence.sqlite.SqliteExecutionManifestStore;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.persistence.sqlite.SqliteGraphDefinitionStore;
import ai.ravenroot.persistence.sqlite.SqliteStoreMaintenanceLock;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opens the server's configured execution store and turns adapter diagnostics into a small,
 * path-free startup contract.
 *
 * <p>The SQLite adapter already validates and prepares its location synchronously in its
 * constructor. What belongs here is the composition-root policy around that validation: an enabled
 * store is mandatory, so an unusable location aborts startup rather than falling back to no store;
 * and an uncaught startup exception must not print the configured path, a JDBC message or a nested
 * adapter exception. Those details can contain deployment topology and are not needed to distinguish
 * the three operator actions exposed by {@link FailureReason}.</p>
 */
public final class ExecutionStoreBootstrap {
    private ExecutionStoreBootstrap() {
    }

    /**
     * Opens the configured store together with the process-wide maintenance lease that must outlive
     * every audit/store consumer.  The returned owner is the only lifecycle authority.
     */
    public static Opened openOwned(ExecutionStoreConfiguration configuration, Clock clock) {
        return openOwned(configuration, clock, GraphMlLimits.DEFAULTS);
    }

    /** Opens all durable stores with the graph-definition budget chosen by the composition root. */
    public static Opened openOwned(ExecutionStoreConfiguration configuration, Clock clock,
                                   GraphMlLimits graphMlLimits) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(graphMlLimits, "graphMlLimits");
        try {
            // Preserve the adapter's useful location classification before the maintenance API
            // deliberately reduces its own diagnostics to path-free lock failures.
            configuration.location().prepare();
            var maintenanceLock = SqliteStoreMaintenanceLock.acquire(configuration.location());
            try {
                SqliteStoreMaintenanceLock.requireNoPendingRecovery(configuration.location());
                if (!configuration.enabled()) {
                    return new Opened(null, null, null, () -> { }, maintenanceLock::close);
                }
                var store = new SqliteExecutionStore(configuration.location(), clock);
                // Same database file as the executions that pin these definitions, which is what puts
                // both into one backup snapshot and lets retention decide reachability from the
                // execution rows in the transaction that removes a definition. The store's own
                // reachability query is the authority here, so no additional reference source is
                // composed; see GraphDefinitionReferences for the reference classes that are not yet
                // durable and therefore cannot contribute one.
                GraphDefinitionStore definitions;
                try {
                    definitions = new SqliteGraphDefinitionStore(configuration.location(), clock,
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
                    manifests = new SqliteExecutionManifestStore(configuration.location(), clock,
                            ai.ravenroot.api.persistence.ExecutionManifestReferences.NONE);
                } catch (RuntimeException failed) {
                    try {
                        definitions.close();
                    } finally {
                        store.close();
                    }
                    throw failed;
                }
                return new Opened(store, definitions, manifests, () -> {
                    try {
                        manifests.close();
                    } finally {
                        try {
                            definitions.close();
                        } finally {
                            store.close();
                        }
                    }
                }, maintenanceLock::close);
            } catch (RuntimeException failed) {
                maintenanceLock.close();
                throw failed;
            }
        } catch (RuntimeException failed) {
            throw new StartupException(classify(failed));
        }
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

    /** Exactly-once owner for the store first and its maintenance lease second. */
    public static final class Opened implements AutoCloseable {
        private final ExecutionStore store;
        private final GraphDefinitionStore graphDefinitionStore;
        private final ExecutionManifestStore executionManifestStore;
        private final Runnable closeStore;
        private final Runnable releaseMaintenanceLock;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Opened(ExecutionStore store, GraphDefinitionStore graphDefinitionStore,
                       ExecutionManifestStore executionManifestStore,
                       Runnable closeStore, Runnable releaseMaintenanceLock) {
            this.store = store;
            this.graphDefinitionStore = graphDefinitionStore;
            this.executionManifestStore = executionManifestStore;
            this.closeStore = Objects.requireNonNull(closeStore, "closeStore");
            this.releaseMaintenanceLock = Objects.requireNonNull(
                    releaseMaintenanceLock, "releaseMaintenanceLock");
        }

        static Opened forTest(Runnable closeStore, Runnable releaseMaintenanceLock) {
            return new Opened(null, null, null, closeStore, releaseMaintenanceLock);
        }

        public ExecutionStore store() {
            return store;
        }

        /**
         * The durable graph definitions held in the same database, or {@code null} when the store is
         * configured off. Closed with the execution store, definitions first, so nothing can observe
         * a definition store whose database file has already been released.
         *
         * @return the composed graph definition store, or {@code null}.
         */
        public GraphDefinitionStore graphDefinitionStore() {
            return graphDefinitionStore;
        }

        /**
         * The durable execution manifests held in the same database, or {@code null} when the store
         * is configured off. Closed first, before the definitions and the execution store, so nothing
         * can observe a manifest store whose database file has already been released.
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
                releaseMaintenanceLock.run();
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
     * rendering would print the adapter's raw path and JDBC diagnostic after this safe message.
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
