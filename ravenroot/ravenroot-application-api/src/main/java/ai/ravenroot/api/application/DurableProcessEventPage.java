package ai.ravenroot.api.application;

import java.util.List;

/**
 * Authoritative bounded replay page for one durable process stream.
 * @param events ordered public events in this bounded page
 * @param retainedFromSequence first sequence that has not been compacted
 * @param nextSequence next sequence the durable stream allocator will issue
 */
public record DurableProcessEventPage(List<DurableExecutionEvent> events, long retainedFromSequence,
                                      long nextSequence) {
    /** Copies events and validates the reported stream boundary. */
    public DurableProcessEventPage {
        events = List.copyOf(events);
        if (retainedFromSequence < 1 || nextSequence < retainedFromSequence) {
            throw new IllegalArgumentException("invalid durable process event boundary");
        }
    }
}
