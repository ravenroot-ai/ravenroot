package ai.ravenroot.api.persistence;

import java.time.Instant;
import java.util.Objects;

/**
 * An {@link EventEnvelope} as the journal holds it: the caller's content plus the coordinates the
 * store assigned when it committed (ADR 0011, PERS-07).
 *
 * <p>Splitting these from the envelope is what makes {@link EventEnvelope#digest()} computable by the
 * party that authors the content. Nothing on this record is covered by that digest, and nothing on it
 * could be, because none of it exists until the commit lock is held.</p>
 *
 * <h2>Two sequences, because they answer different questions</h2>
 * <ul>
 *   <li>{@link #streamSequence()} is <strong>per process instance</strong>, starts at one and is
 *   <strong>contiguous</strong>. It is the ordering guarantee the platform actually makes: a
 *   consumer reconstructing one execution can tell a gap from a completed read, because the next
 *   number is always exactly one higher. It is a per-instance counter and not a clock, so it is
 *   meaningless to compare across instances.</li>
 *   <li>{@link #journalOffset()} is <strong>per tenant</strong>, strictly increasing and
 *   <strong>not</strong> guaranteed contiguous. It is the publisher's cursor domain — the axis a
 *   transactional-outbox reader walks — and nothing more.</li>
 * </ul>
 *
 * <p>Neither is a global clock, and neither may be presented as one. Under physical tenant isolation
 * each tenant is a separate database, so a cross-tenant total order
 * does not exist to be published; a per-tenant sequence misrepresented as globally ordered is exactly
 * the failure the durability strategy warns against, and it would be discovered only once a second
 * tenant's stream was interleaved with the first.</p>
 *
 * @param envelope            the caller-authored, digest-covered content
 * @param streamSequence      contiguous position within this process instance, from one
 * @param journalOffset       strictly increasing position within this tenant's journal
 * @param committedAtRevision the {@link StoredProcessInstance#revision()} this event committed at.
 *                            This is the join that makes the shared transactional boundary
 *                            <em>observable</em>: an event whose revision names a transition that is
 *                            not in the store, or a committed transition with no event beside it,
 *                            would both be visible here rather than having to be inferred
 * @param recordedAt          when the store committed it, on the <em>store's</em> clock, as distinct
 *                            from {@link EventEnvelope#occurredAt()} on the producer's
 */
public record JournalRecord(EventEnvelope envelope, long streamSequence, long journalOffset,
                            long committedAtRevision, Instant recordedAt) {

    /** Validates store-assigned journal coordinates for a committed event. */
    public JournalRecord {
        Objects.requireNonNull(envelope, "envelope");
        if (streamSequence < 1) {
            throw new IllegalArgumentException("streamSequence starts at one, got " + streamSequence);
        }
        if (journalOffset < 1) {
            throw new IllegalArgumentException("journalOffset starts at one, got " + journalOffset);
        }
        Objects.requireNonNull(recordedAt, "recordedAt");
    }

/**
 * Derives the execution aggregate key from the committed envelope.
 * @return tenant and process-instance key of this journal event.
 */
    public ExecutionKey key() {
        return new ExecutionKey(envelope.tenantId(), envelope.processInstanceId());
    }
}
