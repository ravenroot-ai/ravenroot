package ai.ravenroot.server;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two properties {@code RavenrootServer.DurableStreamWakeup} exists for, tested where they are
 * actually observable.
 *
 * <p>They are not observable through the HTTP endpoint. The durable stream's live subscription is
 * wrapped by {@code AuthorizedRavenrootApplication.subscribeToExecutionEvents} in a
 * {@code canObserve} check that resolves ownership through an in-process map holding only
 * executions the same process started — so in any server-level test driven by a fake application,
 * every published event is discarded before it reaches this class. An integration test that flooded
 * the publisher would therefore go green against a wakeup that blocked on the first signal, which is
 * the definition of an instrument that cannot fail.</p>
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class DurableStreamWakeupTest {

    /**
     * Signalling must never block, whatever the reader is doing — the whole reason this endpoint
     * cannot let one connection's health reach the shared execution-event publisher. Nothing consumes
     * during the burst, so an implementation that waited for room would wait here forever and this
     * test would fail on its timeout rather than its assertion.
     */
    @Test
    void signallingNeverBlocksTheCallerNoMatterHowFarPastCapacity() {
        var wakeup = new RavenrootServer.DurableStreamWakeup();
        long start = System.nanoTime();
        for (int i = 0; i < 100_000; i++) {
            wakeup.signal(event());
        }
        long elapsedNanos = System.nanoTime() - start;
        assertTrue(elapsedNanos < TimeUnit.SECONDS.toNanos(10),
                "100000 signals with no reader must not block the publisher; took "
                        + java.time.Duration.ofNanos(elapsedNanos));
    }

    /**
     * Signals coalesce: any number of them leaves exactly one pending, never a backlog of wakeups to
     * work through one at a time. That is what makes them safe to drop — the reader answers a single
     * wakeup by draining the journal to exhaustion, so N collapsed into one loses nothing — and it is
     * also what stops a burst from turning into N redundant journal reads.
     */
    @Test
    void manySignalsCoalesceIntoExactlyOnePendingWakeup() throws Exception {
        var wakeup = new RavenrootServer.DurableStreamWakeup();
        for (int i = 0; i < 5_000; i++) {
            wakeup.signal(event());
        }
        assertTrue(wakeup.await(0), "the first await must observe the burst");
        assertFalse(wakeup.await(50),
                "a second await must find nothing pending: 5000 signals that left more than one "
                        + "wakeup behind would be a queue, and a queue is what this class exists not to be");
    }

    /** No signal means no wakeup, which is what makes the caller's periodic re-poll the real clock. */
    @Test
    void awaitReportsATimeoutWhenNothingWasSignalled() throws Exception {
        assertFalse(new RavenrootServer.DurableStreamWakeup().await(20));
    }

    private static ExecutionEvent event() {
        return new ExecutionEvent(1, Instant.EPOCH, "local", "req", "engine", "v1", UUID.randomUUID(),
                UUID.randomUUID(), null, null, ExecutionEventType.NODE_STARTED, null, 0, false, "poke");
    }
}
