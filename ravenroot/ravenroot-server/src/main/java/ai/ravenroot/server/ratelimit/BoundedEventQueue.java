package ai.ravenroot.server.ratelimit;

import ai.ravenroot.api.application.ExecutionEvent;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-connection event buffer with a fixed capacity and a drop-the-consumer overflow policy.
 *
 * <p>This replaces an unbounded {@code LinkedBlockingQueue}. Unbounded was the whole slow-consumer
 * defect: a subscriber that stops reading — a paused laptop, a wedged proxy, a client that opened an
 * {@code EventSource} and walked away — kept an entry queued for every event the engine produced, for
 * as long as the socket stayed open. Nothing in the functional tests could see it, because a test
 * consumer always reads.</p>
 *
 * <h2>Why disconnect rather than block or drop silently</h2>
 *
 * <p><strong>Not blocking.</strong> The producer is the shared execution-event publisher. Blocking it
 * on a full queue would let one stalled subscriber stop event delivery for every other subscriber and,
 * behind them, the engine thread that publishes. A slow consumer must cost only itself; the moment
 * backpressure crosses a connection boundary, one client's problem becomes the server's.</p>
 *
 * <p><strong>Not silently dropping.</strong> Discarding events would leave the client believing it has
 * a complete stream while it is quietly missing the middle of one. That is worse than an error,
 * because it is an error the client cannot detect.</p>
 *
 * <p><strong>So: terminate the connection.</strong> The stream carries monotonic sequence ids and the
 * server already supports resuming from {@code Last-Event-ID}. A dropped consumer therefore reconnects
 * and replays exactly what it missed, which makes disconnection lossless from the client's point of
 * view and bounded from the server's. The overrun is announced as a terminal event first, so a client
 * knows to resume rather than treating it as a transport failure.</p>
 */
public final class BoundedEventQueue {
    private final ArrayBlockingQueue<ExecutionEvent> events;
    private final AtomicBoolean overrun = new AtomicBoolean();

    public BoundedEventQueue(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("stream queue capacity must be positive");
        }
        this.events = new ArrayBlockingQueue<>(capacity);
    }

    /**
     * Offers an event without ever blocking the publisher.
     *
     * <p>Returns normally in both cases: the caller is a shared listener that must not learn about, or
     * be slowed by, one connection's health.</p>
     */
    public void offer(ExecutionEvent event) {
        if (!events.offer(event)) {
            overrun.set(true);
        }
    }

    /** Waits briefly for the next event, or {@code null} if none arrived within the window. */
    public ExecutionEvent poll(long timeoutMillis) throws InterruptedException {
        return events.poll(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /** True once an event has been discarded, meaning this connection can no longer be trusted. */
    public boolean overrun() {
        return overrun.get();
    }

    /** Current depth, so a test can assert the capacity bound directly. */
    public int depth() {
        return events.size();
    }

    public int capacity() {
        return events.size() + events.remainingCapacity();
    }

    /** Drops buffered events when the connection ends, so teardown does not retain them. */
    public void clear() {
        events.clear();
    }
}
