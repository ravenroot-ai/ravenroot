package ai.ravenroot.api.application;

import java.util.List;
import java.util.Objects;

/**
 * Bounded replay result for one exact deployment incarnation and graph version.
 * @param status continuity and source-resolution result
 * @param events filtered events in ascending sequence order
 * @param oldestRetainedSequence oldest sequence still retained by the bounded journal
 * @param latestSequence latest sequence observed by the bounded journal
 */
public record DeploymentEventBatch(Status status, List<ExecutionEvent> events,
                                   long oldestRetainedSequence, long latestSequence) {
    /** Closed continuity and source-resolution outcomes for bounded replay. */
    public enum Status {
        /** The source is current and the requested continuity is available. */
        AVAILABLE,
        /** The global process-local ring no longer proves continuity from the requested cursor. */
        GAP,
        /** Unknown, cross-tenant, undeployed and otherwise unreachable sources are indistinguishable. */
        UNAVAILABLE,
        /** The logical id exists, but no longer denotes the captured incarnation/version. */
        SOURCE_CHANGED
    }

    /** Validates bounds and prevents terminal batches from carrying events. */
    public DeploymentEventBatch {
        Objects.requireNonNull(status, "status");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        if (oldestRetainedSequence < 0 || latestSequence < 0) {
            throw new IllegalArgumentException("event sequences must not be negative");
        }
        if (status != Status.AVAILABLE && !events.isEmpty()) {
            throw new IllegalArgumentException("non-available batches cannot carry events");
        }
    }

    /**
     * Creates an event-free terminal batch.
     * @param status terminal status other than {@link Status#AVAILABLE}
     * @return event-free terminal batch
     */
    public static DeploymentEventBatch unavailable(Status status) {
        if (status == Status.AVAILABLE) throw new IllegalArgumentException("available requires bounds");
        return new DeploymentEventBatch(status, List.of(), 0, 0);
    }
}
