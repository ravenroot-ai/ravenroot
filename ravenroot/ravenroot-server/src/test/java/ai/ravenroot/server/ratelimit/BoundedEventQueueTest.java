package ai.ravenroot.server.ratelimit;

import ai.ravenroot.api.application.ExecutionEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The slow-consumer invariants, asserted directly rather than as a throughput number.
 *
 * <p>A wall-clock assertion about how fast a stalled consumer is dropped would be flaky in CI and would
 * not actually state the property that matters. What matters is that a consumer which stops reading can
 * cost the server only a fixed amount of memory, cannot block the shared publisher, and is detectable
 * so it can be disconnected. All three are exact and testable without timing.</p>
 */
class BoundedEventQueueTest {
    @Test
    void depthNeverExceedsCapacityHoweverManyEventsArePublished() {
        var queue = new BoundedEventQueue(16);

        for (int index = 0; index < 100_000; index++) {
            queue.offer(event(index));
            assertTrue(queue.depth() <= 16,
                    "queue depth " + queue.depth() + " exceeded its capacity of 16 at event " + index);
        }

        assertEquals(16, queue.depth());
        assertEquals(16, queue.capacity());
    }

    @Test
    void aConsumerThatStopsReadingIsMarkedOverrunRatherThanBuffered() {
        var queue = new BoundedEventQueue(4);

        for (int index = 0; index < 4; index++) {
            queue.offer(event(index));
        }
        assertFalse(queue.overrun(), "the queue reported overrun while it still had room");

        queue.offer(event(4));

        assertTrue(queue.overrun(), "an event was discarded without the connection being marked overrun, "
                + "so the client would silently receive an incomplete stream");
    }

    /**
     * The publisher is shared across every open stream. If one stalled consumer could block it, one
     * client's problem would become every client's problem and, behind them, the engine's.
     */
    @Test
    void offeringToAFullQueueNeverBlocksThePublisher() throws Exception {
        var queue = new BoundedEventQueue(1);
        queue.offer(event(0));
        var completed = new CountDownLatch(1);

        var publisher = new Thread(() -> {
            for (int index = 0; index < 10_000; index++) {
                queue.offer(event(index));
            }
            completed.countDown();
        });
        publisher.start();

        assertTrue(completed.await(10, TimeUnit.SECONDS),
                "the publisher was still blocked on a full queue after 10 seconds");
        publisher.join();
    }

    @Test
    void pollReturnsNullOnTimeoutSoTheStreamCanEmitKeepalives() throws Exception {
        var queue = new BoundedEventQueue(4);

        assertNull(queue.poll(1));
    }

    @Test
    void clearReleasesBufferedEventsAtTeardown() {
        var queue = new BoundedEventQueue(8);
        for (int index = 0; index < 8; index++) {
            queue.offer(event(index));
        }
        assertEquals(8, queue.depth());

        queue.clear();

        assertEquals(0, queue.depth(), "buffered events survived connection teardown");
    }

    @Test
    void capacityMustBePositiveSoAStreamCannotBeConfiguredUnbuffered() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new BoundedEventQueue(0));
    }

    private static final java.util.UUID EXECUTION = java.util.UUID.randomUUID();

    private static ExecutionEvent event(long sequence) {
        return new ExecutionEvent(sequence, Instant.EPOCH, "tenant-a", "request-a", "engine", "v1",
                EXECUTION, ai.ravenroot.api.application.ExecutionEventType.NODE_STARTED, "node", 1, false,
                "detail");
    }
}
