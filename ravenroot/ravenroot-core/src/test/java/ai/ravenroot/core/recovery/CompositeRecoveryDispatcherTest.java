package ai.ravenroot.core.recovery;

import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.PendingWork;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeRecoveryDispatcherTest {
    private static final PendingWork ITEM = new PendingWork.HandlerTrigger(
            new ExecutionKey("acme", UUID.randomUUID()), UUID.randomUUID(), UUID.randomUUID(), null,
            "human-task", OpaquePayload.empty("application/octet-stream"), 1,
            Instant.parse("2026-01-01T00:00:30Z"), 1);

    @Test
    void delegatesToTheOnlyOwnerIncludingPostAcknowledgementCleanup() {
        var dispatches = new AtomicInteger();
        var acknowledgements = new AtomicInteger();
        RecoveryDispatcher owner = dispatcher(true, dispatches, acknowledgements);
        var composite = new CompositeRecoveryDispatcher(dispatcher(false,
                new AtomicInteger(), new AtomicInteger()), owner);

        assertTrue(composite.canDispatch(ITEM));
        composite.dispatch(ITEM, "attempt-key");
        composite.afterAcknowledged(ITEM);

        assertEquals(1, dispatches.get());
        assertEquals(1, acknowledgements.get());
    }

    @Test
    void refusesAmbiguousOwnershipAndFailsClosedWhenNobodyOwnsTheItem() {
        var none = new CompositeRecoveryDispatcher(dispatcher(false,
                new AtomicInteger(), new AtomicInteger()));
        assertFalse(none.canDispatch(ITEM));
        assertThrows(IllegalStateException.class, () -> none.dispatch(ITEM, "key"));

        var ambiguous = new CompositeRecoveryDispatcher(
                dispatcher(true, new AtomicInteger(), new AtomicInteger()),
                dispatcher(true, new AtomicInteger(), new AtomicInteger()));
        assertThrows(IllegalStateException.class, () -> ambiguous.canDispatch(ITEM));
        assertThrows(IllegalStateException.class, () -> ambiguous.dispatch(ITEM, "key"));
        assertThrows(IllegalStateException.class, () -> ambiguous.afterAcknowledged(ITEM));
    }

    private static RecoveryDispatcher dispatcher(boolean owns, AtomicInteger dispatches,
                                                   AtomicInteger acknowledgements) {
        return new RecoveryDispatcher() {
            @Override public boolean canDispatch(PendingWork item) { return owns; }
            @Override public void dispatch(PendingWork item, String idempotencyKey) {
                dispatches.incrementAndGet();
            }
            @Override public void afterAcknowledged(PendingWork item) {
                acknowledgements.incrementAndGet();
            }
        };
    }
}
