package ai.ravenroot.api.persistence;

import java.time.Instant;
import java.util.UUID;

/**
 * A durable timer record to be written inside a batch (ADR 0010 section 7).
 *
 * <p>The store owns timer <em>records</em>; <em>firing</em> stays with the runtime. That split is
 * structural, not stylistic: the port contains no callbacks, and a remote adapter cannot persist a
 * {@code Runnable}. Poll cadence therefore belongs to the runtime, consistently with
 * {@link ai.ravenroot.api.execution.Scheduler} already owning "when to look next".</p>
 * @param timerId the stable timer id used to identify the requested resource.
 * @param dueAt instant at which the timer becomes due.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @param invocationId the stable invocation id used to identify the requested resource.
 * @param payload bounded payload carried by the pending work.
 */
public record TimerSchedule(UUID timerId, Instant dueAt, UUID traversalId, UUID invocationId,
                            OpaquePayload payload) {
    /** Validates a timer that the store must later make claimable. */
    public TimerSchedule {
        if (timerId == null) throw new IllegalArgumentException("timerId cannot be null");
        if (dueAt == null) throw new IllegalArgumentException("dueAt cannot be null");
        if (payload == null) throw new IllegalArgumentException("payload cannot be null");
    }
}
