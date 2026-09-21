package ai.ravenroot.api.persistence;

import java.util.List;

/**
 * One atomically bounded page from a single process journal stream.
 * @param records ordered records in this bounded page
 * @param retainedFromSequence first sequence that has not been compacted
 * @param nextSequence next sequence the stream allocator will issue
 */
public record ProcessJournalPage(List<JournalRecord> records, long retainedFromSequence,
                                 long nextSequence) {
    /** Validates the durable stream boundary and copies the returned records. */
    public ProcessJournalPage {
        records = List.copyOf(records);
        if (retainedFromSequence < 1 || nextSequence < retainedFromSequence) {
            throw new IllegalArgumentException("invalid process journal boundary");
        }
    }
}
