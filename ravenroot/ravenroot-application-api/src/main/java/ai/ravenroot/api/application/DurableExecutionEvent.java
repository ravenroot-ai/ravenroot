package ai.ravenroot.api.application;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of a tenant's durable event journal, projected for an external adapter (API-03,
 * PERS-07). This is the durable counterpart of {@link ExecutionEvent}: where that type is
 * evidence about the live, in-process, non-durable {@code ExecutionMonitor} stream, this type is
 * evidence read back from {@code ExecutionStore}'s journal and survives a process restart.
 *
 * <h2>Cursor: {@code journalOffset}, never {@link ExecutionEvent#sequence()}</h2>
 * <p>{@link #journalOffset()} is the axis a client resumes from ({@code Last-Event-ID}). It is
 * per-tenant, strictly increasing and store-assigned ({@code JournalRecord.journalOffset()}'s own
 * contract) — durable across a restart, unlike {@code ExecutionMonitor}'s in-process sequence
 * counter, which resets to zero the moment the process does. That reset is exactly the gap API-03
 * exists to close: a client resuming with the old counter's last-seen value after a restart would
 * be asking a fresh counter, now at or near zero again, for events "after" a number it will not
 * reach for a long time — silently replaying nothing rather than the true backlog.</p>
 *
 * <h2>Why this is a distinct type from {@link ExecutionEvent}, not a second constructor on it</h2>
 * <p>The two carry genuinely different information. {@link ExecutionEvent} carries derived,
 * in-process-only instrumentation — {@code activeInstances}, {@code fallback}, {@code detail},
 * {@code processingDuration}, {@code joinWaitDuration} — that the durable journal does not
 * capture and is not asked to (the causal model underlying {@code EventEnvelope} leaves the
 * payload schema for a later decision, deliberately: see {@code EventEnvelope}'s own Javadoc).
 * Forcing this type's sparser durable facts through {@link ExecutionEvent}'s wider shape would
 * mean fabricating values for fields the journal has no record of — {@code activeInstances=0},
 * for instance, would read as "zero nodes active" rather than "not measured here", which is the
 * exact false-zero shape {@link ExecutionEvent}'s own field Javadoc warns against for
 * {@code joinWaitDuration} and {@code processingDuration}. A distinct, honestly narrower type
 * makes the absence of those facts a compile-time fact rather than a runtime convention every
 * caller has to remember.</p>
 *
 * @param eventId the journal's own deduplication identity ({@code EventEnvelope#eventId()})
 * @param journalOffset this tenant's durable, strictly increasing cursor position — the SSE id
 * @param streamSequence this process instance's own contiguous position, for a consumer
 * reconstructing a single instance's history rather than a tenant's stream
 * @param tenantId the authenticated tenant this event was read under. Always the caller's
 * own — see {@code AuthorizedRavenrootApplication#durableEventsAfter} for
 * why that is a property of how this value is produced, not a claim a reader
 * of this record has to trust after the fact
 * @param eventType opaque domain event type, {@code EventEnvelope#eventType()} verbatim
 * @param processInstanceId the instance this event belongs to
 * @param traversalId the traversal this event belongs to
 * @param invocationId the node invocation this event belongs to, absent above that level
 * @param attemptId the node attempt this event belongs to, absent above that level
 * @param causationId the journalled event that caused this one, absent only when the cause
 * lies outside the journal entirely ({@code EventEnvelope#causationId()}'s
 * own, stricter-than-nullability contract)
 * @param correlationId end-to-end request correlation, shared with {@link ExecutionEvent#requestId()}
 * @param graphVersion the pinned graph version this event was produced under
 * @param occurredAt when the event happened, on the producer's clock — diagnostic only;
 * ordering is {@link #journalOffset()}, never a timestamp comparison
 * @param nodeId resolved by joining {@code invocationId} against that instance's own
 * {@code InvocationAdded} structure, which is where the binding is durably
 * recorded (the envelope itself deliberately carries no node id — see
 * {@code EventEnvelope}'s Javadoc). {@code null} when {@code invocationId} is
 * absent, or when the instance's current state could not be loaded to resolve
 * it (a declared best-effort join, not a second source of truth: the instance
 * state can have its own, independent retention from the journal's)
 * @param edgeId stable edge identity decoded from an {@code EDGE_TRAVERSED} event body;
 * absent on older rows and every other event type
 */
public record DurableExecutionEvent(UUID eventId, long journalOffset, long streamSequence, String tenantId,
                                    String eventType, UUID processInstanceId, UUID traversalId, UUID invocationId,
                                    UUID attemptId, UUID causationId, String correlationId, String graphVersion,
                                    Instant occurredAt, String nodeId, String edgeId) {

/**
 * Normalizes durable execution-event fields required for replay and ordered observation.
 */
    public DurableExecutionEvent {
        java.util.Objects.requireNonNull(eventId, "eventId");
        if (journalOffset < 1) {
            throw new IllegalArgumentException("journalOffset starts at one, got " + journalOffset);
        }
        if (streamSequence < 1) {
            throw new IllegalArgumentException("streamSequence starts at one, got " + streamSequence);
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId cannot be blank");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType cannot be blank");
        }
        java.util.Objects.requireNonNull(processInstanceId, "processInstanceId");
        java.util.Objects.requireNonNull(traversalId, "traversalId");
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId cannot be blank");
        }
        java.util.Objects.requireNonNull(occurredAt, "occurredAt");
        if (edgeId != null) {
            edgeId = StableEdgeId.requireValid(edgeId);
        }
        if (ExecutionEventType.EDGE_TRAVERSED.name().equals(eventType)) {
            EdgeTraversalWireBudget.requireDurableProjection(eventType, graphVersion, nodeId);
        }
    }

    /**
 * Compatibility constructor preserving the durable projection shape before edge traversal.
* @param eventId durable journal deduplication identity
* @param journalOffset tenant-local durable cursor assigned by the store
* @param streamSequence contiguous position within the process-instance event stream
* @param tenantId authenticated tenant that owns the operation or event
* @param eventType domain event type recorded in the durable journal
* @param processInstanceId identity of the process instance that owns the event
* @param traversalId identity of the traversal that owns the event
* @param invocationId identity of the node invocation, or {@code null} above invocation scope
* @param attemptId identity of the node attempt, or {@code null} above attempt scope
* @param causationId identity of the journal event that caused this event, when journalled
* @param correlationId end-to-end request correlation identifier
* @param graphVersion pinned graph version for the execution
* @param occurredAt time at which the event occurred
* @param nodeId graph node identity associated with the operation or event
 */
    public DurableExecutionEvent(UUID eventId, long journalOffset, long streamSequence, String tenantId,
                                 String eventType, UUID processInstanceId, UUID traversalId, UUID invocationId,
                                 UUID attemptId, UUID causationId, String correlationId, String graphVersion,
                                 Instant occurredAt, String nodeId) {
        this(eventId, journalOffset, streamSequence, tenantId, eventType, processInstanceId, traversalId,
                invocationId, attemptId, causationId, correlationId, graphVersion, occurredAt, nodeId, null);
    }
}
