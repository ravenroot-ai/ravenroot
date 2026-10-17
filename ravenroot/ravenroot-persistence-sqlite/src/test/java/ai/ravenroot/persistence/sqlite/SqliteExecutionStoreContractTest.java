package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.testkit.persistence.ExecutionStoreContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ADR 0010 conformance suite, run against the SQLite adapter.
 *
 * <p>This is the first subclass in the repository for which
 * {@link StoreCapability#DURABLE} and {@link StoreCapability#CROSS_PROCESS_LEASE} are declared, so it
 * is the first run in which the two assertions the suite labels PROVISIONAL actually execute rather
 * than reporting as skips. {@link #theProvisionalAssertionsAreNotSkippedHere} guards that: a
 * capability quietly dropped from {@code capabilities()} would turn both back into skips, and the
 * suite's own Javadoc explains why that is worse than an acknowledged hole — in aggregate a skipped
 * assertion is indistinguishable from a passing one.</p>
 *
 * <p>{@code storeId} maps to a file under the per-test temporary directory, so a reopen genuinely
 * reconnects to the same bytes on disk. Nothing is shared in memory between the two instances.</p>
 */
class SqliteExecutionStoreContractTest extends ExecutionStoreContract {

    @TempDir
    Path databaseDirectory;

    @Override
    protected ExecutionStore createStore(String storeId, Clock clock) {
        return new SqliteExecutionStore(databaseDirectory.resolve(storeId + ".db"), clock);
    }

    @Test
    void theProvisionalAssertionsAreNotSkippedHere() {
        assertTrue(store().supports(StoreCapability.DURABLE),
                "The durability contract requires that the previously skipped DURABLE assertion runs "
                        + "and passes, not that this module's own tests are green");
        assertTrue(store().supports(StoreCapability.CROSS_PROCESS_LEASE),
                "likewise CROSS_PROCESS_LEASE: dropping the declaration would silently convert the "
                        + "assertion back into a skip that looks identical to a pass");
        assertTrue(store().supports(StoreCapability.TRANSACTIONAL_BATCH));
        assertTrue(store().supports(StoreCapability.IDEMPOTENCY_PURGE));
        assertTrue(store().supports(StoreCapability.DURABLE_SAGAS));
        assertTrue(store().supports(StoreCapability.SELECTIVE_REPLAY_EVIDENCE));
        assertTrue(store().supports(StoreCapability.FLOW_INVOCATIONS));

        assertEquals(EnumSet.of(StoreCapability.DURABLE, StoreCapability.TRANSACTIONAL_BATCH,
                        StoreCapability.CROSS_PROCESS_LEASE, StoreCapability.IDEMPOTENCY_PURGE,
                        StoreCapability.EVENT_JOURNAL, StoreCapability.JOURNAL_COMPACTION,
                        StoreCapability.DURABLE_HANDLERS, StoreCapability.PROCESS_INVENTORY,
                        StoreCapability.INVENTORY_RETENTION, StoreCapability.TOOL_APPROVALS,
                        StoreCapability.HUMAN_TASKS, StoreCapability.HUMAN_TASK_CONFIRMATIONS,
                        StoreCapability.EXECUTION_PAUSES, StoreCapability.AGENT_AUTHORITY_BUDGETS,
                        StoreCapability.EXECUTION_RESULTS, StoreCapability.RUNNER_JOBS,
                        StoreCapability.DURABLE_SAGAS, StoreCapability.SELECTIVE_REPLAY_EVIDENCE,
                        StoreCapability.FLOW_INVOCATIONS),
                Set.copyOf(store().capabilities()),
                "the exact capability set keeps every conformance assertion active");
    }
}
