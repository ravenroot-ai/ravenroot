package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.testkit.persistence.ExecutionStoreContract;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The conformance suite, run against the PostgreSQL adapter.
 *
 * <p>{@code storeId} maps to a schema on the shared container, so a reopen genuinely reconnects to the
 * same rows on the same server. Nothing is shared in memory between the two instances, which is what
 * makes the {@link StoreCapability#DURABLE} assertions mean here what they say.</p>
 */
class PostgresExecutionStoreContractTest extends ExecutionStoreContract {

    @Override
    protected ExecutionStore createStore(String storeId, Clock clock) {
        return new PostgresExecutionStore(PostgresTestDatabase.dataSourceFor(storeId), clock);
    }

    /**
     * Pins the declared capability set, in both directions.
     *
     * <p>Enforcement in the suite is asymmetric: absence of a capability skips its assertions, presence
     * never does. That makes a dropped declaration the most expensive kind of regression available here,
     * because in aggregate a skipped assertion is indistinguishable from a passing one — the suite would
     * stay green while it stopped checking anything about the facility.</p>
     *
     * <p>So the <em>presence</em> half asserts each declaration individually, with the reason it matters
     * for this adapter. And the <em>absence</em> half asserts the whole set by equality rather than
     * checking only what is present, because the failure this test exists to catch is a set that quietly
     * changed, and a test that only ever adds expectations cannot see a capability appear.</p>
     */
    @Test
    void theDeclaredCapabilitiesArePinnedSoNoAssertionSilentlyBecomesASkip() {
        assertTrue(store().supports(StoreCapability.DURABLE),
                "state lives in PostgreSQL and is read back on reopen, so the durability assertions must "
                        + "run rather than skip");
        assertTrue(store().supports(StoreCapability.CROSS_PROCESS_LEASE),
                "this is the adapter whose exclusion extends past one host; dropping the declaration "
                        + "would convert the assertion back into a skip that looks identical to a pass");
        assertTrue(store().supports(StoreCapability.TRANSACTIONAL_BATCH));
        assertTrue(store().supports(StoreCapability.IDEMPOTENCY_PURGE));
        assertTrue(store().supports(StoreCapability.EVENT_JOURNAL));
        assertTrue(store().supports(StoreCapability.JOURNAL_COMPACTION));
        assertTrue(store().supports(StoreCapability.PROCESS_INVENTORY));
        assertTrue(store().supports(StoreCapability.INVENTORY_RETENTION));
        assertTrue(store().supports(StoreCapability.EXECUTION_RESULTS));
        assertTrue(store().supports(StoreCapability.DURABLE_HANDLERS),
                "a wait and the handler that records what it is waiting for commit together here; "
                        + "dropping the declaration would silence every assertion that says so");
        assertTrue(store().supports(StoreCapability.TOOL_APPROVALS));
        assertTrue(store().supports(StoreCapability.HUMAN_TASKS));
        assertTrue(store().supports(StoreCapability.HUMAN_TASK_CONFIRMATIONS));
        assertTrue(store().supports(StoreCapability.EXECUTION_PAUSES));
        assertTrue(store().supports(StoreCapability.AGENT_AUTHORITY_BUDGETS),
                "the compare-and-set on the global control epoch, and the budget sweep it drives, are "
                        + "the assertions that distinguish this adapter from a single-host one");

        assertEquals(EnumSet.of(StoreCapability.DURABLE, StoreCapability.TRANSACTIONAL_BATCH,
                        StoreCapability.CROSS_PROCESS_LEASE, StoreCapability.IDEMPOTENCY_PURGE,
                        StoreCapability.EVENT_JOURNAL, StoreCapability.JOURNAL_COMPACTION,
                        StoreCapability.PROCESS_INVENTORY, StoreCapability.INVENTORY_RETENTION,
                        StoreCapability.EXECUTION_RESULTS, StoreCapability.DURABLE_HANDLERS,
                        StoreCapability.TOOL_APPROVALS, StoreCapability.HUMAN_TASKS,
                        StoreCapability.HUMAN_TASK_CONFIRMATIONS, StoreCapability.EXECUTION_PAUSES,
                        StoreCapability.AGENT_AUTHORITY_BUDGETS),
                Set.copyOf(store().capabilities()),
                "the declared set is exactly what this build implements. A capability added here without "
                        + "an implementation would make the suite assert against behaviour that does not "
                        + "exist, and one added by an implementation without being declared here would "
                        + "leave its assertions skipped and invisible");
    }
}
