package ai.ravenroot.api.persistence;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Exclusive ownership of one tenant/source namespace, including its cursor and ingress inbox.
 * Implementations exclude other processes in their supported storage topology. Close rejects new
 * work immediately but retains ownership until every admitted write has settled, even if its caller
 * cancelled a returned future. A store that cannot provide this guarantee must refuse opening it.
 * No lease or authority over process executions is implied. Positions belong to the source
 * protocol, not the execution journal. A protocol may reserve a nonzero baseline to distinguish an
 * initialized empty source from an absent checkpoint; it must keep that encoding stable on restart.
 */
public interface SourceCheckpointStore extends AutoCloseable {
    /**
     * Reads a cursor inside the owned namespace.
     * @param sourceId opaque substream identity
     * @return durable cursor, initially zero
     */
    CompletionStage<JournalCursor> checkpoint(String sourceId);
    /**
     * Advances only a cursor belonging to this namespace, by compare-and-set.
     * @param expected owned cursor and expected position
     * @param position acknowledged position
     * @return persisted cursor
     */
    CompletionStage<JournalCursor> advance(JournalCursor expected, long position);
    /**
     * Records durable ingress custody in this namespace.
     * @param sourceId opaque substream identity
     * @param eventId stable event identity
     * @param retention inbox retention
     * @return true for first custody, false for duplicate
     */
    CompletionStage<Boolean> recordInbox(String sourceId, UUID eventId, Duration retention);
    /** Revokes this handle and releases ownership after admitted operations settle. */
    @Override void close();
}
