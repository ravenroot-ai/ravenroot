package ai.ravenroot.core.persistence;

import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.JournalCursor;
import ai.ravenroot.api.persistence.JournalRecord;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Drains one destination's share of a tenant's event journal, retry-safely (ADR 0011, PERS-07).
 *
 * <p>This publisher provides the at-least-once delivery half; the inbox provides the
 * at-most-once effect half and belongs to the <em>consumer</em>, not here. The separation is the point: a publisher can only
 * promise to deliver everything at least once, and only the consumer can make an effect happen at
 * most once. A publisher that deduplicated on the consumer's behalf would be claiming a guarantee it
 * has no way to keep, because it cannot see whether the consumer's effect actually landed.</p>
 *
 * <h2>Deliver first, advance second</h2>
 * <p>The loop is deliberately ordered so that <strong>every</strong> crash point loses nothing:</p>
 * <ul>
 *   <li>Crash after the batch committed but before this publisher ran: the journal holds the event,
 *   the cursor never moved, the next run delivers it. The window between committing a transition and
 *   publishing its event — the window this design closes — is not narrowed here, it is
 *   absent, because the event was committed <em>with</em> the transition rather than published after
 *   it.</li>
 *   <li>Crash after delivering but before advancing the cursor: the next run delivers the same
 *   records again. That is a duplicate <em>delivery</em>, which the consumer's inbox turns into no
 *   duplicate <em>effect</em>.</li>
 *   <li>Crash after advancing: the records are done and are not redelivered.</li>
 * </ul>
 * <p>The reverse order — advance, then deliver — would turn the second case into a silent loss, and
 * silence is what makes it worse than the duplicate: nothing downstream can tell that an event it
 * never saw ever existed.</p>
 *
 * <p>Nothing here knows about SSE, Kafka or AMQP, and nothing may be added that does. The sink is a
 * plain functional interface, so {@code ravenroot-core} acquires no broker dependency and a transport
 * adapter is written against this class rather than inside it.</p>
 */
public final class OutboxPublisher {

    private final ExecutionStore store;
    private final String tenantId;
    private final String destination;
    private final EventSink sink;
    private final int batchSize;

    /**
     * Receives one journal record. Implementations must be able to see the same record more than
     * once: delivery is at-least-once and no amount of care in this class can make it otherwise.
     */
    @FunctionalInterface
    public interface EventSink {
        void deliver(JournalRecord record);
    }

    public OutboxPublisher(ExecutionStore store, String tenantId, String destination, EventSink sink,
                           int batchSize) {
        this.store = Objects.requireNonNull(store, "store");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.destination = Objects.requireNonNull(destination, "destination");
        this.sink = Objects.requireNonNull(sink, "sink");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
    }

    /**
     * Delivers at most one batch and returns how many records it delivered. Zero means the
     * destination is caught up.
     *
     * <p>Not a loop and not a thread. Poll cadence belongs to the runtime for the same reason ADR
     * 0010 section 7 gives for timers: a publisher that owned its own scheduling would be a second
     * scheduling authority, and this one would additionally have to decide backoff on behalf of a
     * transport it knows nothing about.</p>
     *
     * <p>A sink that throws propagates, and the cursor is <strong>not</strong> advanced. That is the
     * correct failure mode: the records stay undelivered and are retried, rather than being marked
     * delivered because the attempt was made.</p>
     */
    public long publishOnce() {
        JournalCursor cursor = await(store.outboxCursor(tenantId, destination));
        List<JournalRecord> batch = await(store.readJournal(tenantId, cursor.deliveredThrough(), batchSize));
        if (batch.isEmpty()) {
            return 0L;
        }
        for (JournalRecord record : batch) {
            sink.deliver(record);
        }
        await(store.advanceOutboxCursor(cursor, batch.get(batch.size() - 1).journalOffset()));
        return batch.size();
    }

    /** Drains until caught up, bounded so a misbehaving sink cannot spin forever. */
    public long drain(int maxBatches) {
        if (maxBatches < 1) {
            throw new IllegalArgumentException("maxBatches must be positive");
        }
        long delivered = 0;
        for (int pass = 0; pass < maxBatches; pass++) {
            long batch = publishOnce();
            if (batch == 0) {
                break;
            }
            delivered += batch;
        }
        return delivered;
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
