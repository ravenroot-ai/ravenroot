package ai.ravenroot.api.persistence;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A versioned, causally-attributed, tenant-scoped publishable event, written inside the same
 * {@link ExecutionBatch} as the transition that produced it (ADR 0011, PERS-07).
 *
 * <p>This is the unit the transactional outbox delivers and the journal retains. The caller
 * authors it; the store assigns its position. Everything on this type is caller-authored, and the
 * store-assigned coordinates live on {@link JournalRecord} instead — which is what lets
 * {@link #digest()} be computed here, before any position exists.</p>
 *
 * <h2>Why the caller supplies the envelope rather than the store deriving it</h2>
 * <p>The store could in principle synthesize an event from each {@link ExecutionTransition} in the
 * batch. It must not, because it would have to invent the three things it cannot know: the event
 * <em>type</em> as the domain names it, the <em>causality</em> linking this event to the one that
 * provoked it, and the {@code correlationId} that joins it to an authorization decision. Inventing
 * any of them would put a fabricated value into a durable, replayable record — the defect ADR 0010
 * rejected twice, in section 6.3 and section 13.2. The caller knows all three, so the caller states
 * them, exactly as it already does for {@link TimerSchedule} and {@link IdempotencyWrite}.</p>
 *
 * <h2>Causality</h2>
 * <p>Two distinct fields, because they answer different questions and collapsing them loses one:</p>
 * <ul>
 *   <li>{@link #causationId()} is the {@link #eventId()} of the single event that directly provoked
 *   this one — the parent edge of the causal tree. Absent for an event with no in-system cause,
 *   which is honest rather than self-referential; an event that names itself as its own cause is a
 *   fabricated value standing in for absence.
 *   <p><strong>The contract, and it is stricter than the nullability suggests: causation is absent
 *   only for events whose cause lies outside this journal. An event caused by another journaled
 *   event must name it.</strong> The field being nullable is not permission to omit a cause that
 *   exists. This matters more here than for an ordinary optional value because <em>the journal is
 *   append-only</em>: a row published with absent-but-existing causation cannot be amended later,
 *   and backfilling it would mean rewriting history. An empty journal is honest; a semantically
 *   premature one is wrong forever. The in-transaction publication path is therefore proven with
 *   an empty envelope list rather than emitting node events whose causal model was not
 *   yet decided; node events are emitted only where that model is established.</p>
 *   <p>The decided model, so the next author does not re-derive it: the cause of a node-started
 *   event is the event that <em>triggered its dispatch</em> — the predecessor's completion for an
 *   ordinary node, the traversal-accepted event for the start node, and for fan-in the
 *   join-satisfying arrival, meaning the last branch to cross the threshold. The test is a removal
 *   test at the instant of emission: which event, at that moment, made this one be emitted. A join's
 *   other contributors are necessary conditions but not triggers, and they are already durably
 *   recorded as structure — the graph and the join record — so naming only the trigger loses
 *   nothing.</p></li>
 *   <li>{@link #correlationId()} is the end-to-end identifier shared by everything descending from
 *   one request. It is the {@code requestId} that already appears on
 *   {@link ai.ravenroot.api.application.ExecutionEvent},
 *   {@link ai.ravenroot.api.security.AuthorizationAuditEvent} and
 *   {@link ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent}, which is what makes an audit
 *   trace joinable across the journal without a separate correlation store.</li>
 * </ul>
 *
 * <h2>The digest, and what it is computed over</h2>
 * <p>{@link #digest()} is <strong>derived, never supplied</strong>. A caller-supplied digest is one a
 * buggy caller can get wrong undetectably, which would make every integrity assertion downstream a
 * check that the caller agrees with itself. Computing it in the canonical constructor means a stored
 * digest that disagrees with its stored content proves the <em>storage</em> is at fault, which is the
 * only conclusion worth being able to draw.</p>
 *
 * <p>It covers every caller-authored field above and <strong>no store-assigned field</strong>. That
 * boundary is forced rather than chosen: {@link JournalRecord#streamSequence()} and
 * {@link JournalRecord#journalOffset()} are allocated under the commit lock and do not exist when the
 * envelope is built, so a digest covering them could never be computed by the party that authors the
 * content. It also gives the property that matters for replay — the same event redelivered after a
 * crash digests identically, so a consumer can recognise it as the same event rather than as a new
 * one that merely looks similar.</p>
 *
 * <p>The encoding is <strong>length-prefixed</strong>, and that is load-bearing rather than tidy. A
 * plain concatenation lets {@code ("ab", "c")} and {@code ("a", "bc")} produce identical bytes, so
 * two envelopes differing in where one field ends and the next begins would share a digest and an
 * integrity check would accept either for the other. Every variable-width field is therefore preceded
 * by its length in bytes, and every absent field is encoded as a distinct marker rather than as an
 * empty one, so {@code null} and {@code ""} do not collide either.</p>
 *
 * @param envelopeVersion   schema version of this envelope shape, so a consumer reading a journal
 *                          written by a newer producer can refuse rather than misinterpret
 * @param eventId           globally unique identity of this event. It is the <strong>inbox
 *                          deduplication key</strong>, and it is stable across every redelivery of
 *                          the same event
 * @param tenantId          opaque tenant scope; must match the batch's own tenant
 * @param eventType         opaque domain event type. Deliberately a {@code String} and not an enum:
 *                          the persistence port must not enumerate domain vocabulary, or every new
 *                          event type would be a port change and a schema migration
 * @param processInstanceId the instance this event belongs to; must match the batch's own instance
 * @param traversalId       the traversal this event belongs to
 * @param invocationId      the node invocation, absent for events above that level
 * @param attemptId         the node attempt, absent for events above that level
 * @param causationId       the event that directly caused this one, absent when there is none
 * @param correlationId     end-to-end request correlation
 * @param graphVersion      the pinned graph version this event was produced under
 * @param occurredAt        when the event happened, on the producer's clock. Diagnostic and
 *                          correlational only: ordering is {@link JournalRecord#streamSequence()},
 *                          never a timestamp comparison, which across producers would inherit skew
 * @param payload           opaque event body; {@link OpaquePayload#empty(String)} when there is none
 * @param digest SHA-256 integrity digest of the envelope.
 */
public record EventEnvelope(int envelopeVersion, UUID eventId, String tenantId, String eventType,
                            UUID processInstanceId, UUID traversalId, UUID invocationId, UUID attemptId,
                            UUID causationId, String correlationId, String graphVersion,
                            Instant occurredAt, OpaquePayload payload, EventDigest digest) {

    /** The envelope shape this build produces. Bump it only for a change a consumer must notice. */
    public static final int CURRENT_VERSION = 1;

    private static final byte ABSENT = 0;
    private static final byte PRESENT = 1;

    /** Validates the immutable event before it can enter the durable journal. */
    public EventEnvelope {
        if (envelopeVersion < 1) {
            throw new IllegalArgumentException("envelopeVersion must be positive");
        }
        Objects.requireNonNull(eventId, "eventId");
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId cannot be blank");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType cannot be blank");
        }
        Objects.requireNonNull(processInstanceId, "processInstanceId");
        Objects.requireNonNull(traversalId, "traversalId");
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId cannot be blank");
        }
        graphVersion = graphVersion == null ? "" : graphVersion;
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(digest, "digest");
        if (eventId.equals(causationId)) {
            throw new IllegalArgumentException("an event cannot be its own cause");
        }
    }

    /**
     * Builds an envelope at {@link #CURRENT_VERSION} and computes its digest.
     *
     * <p>This is the only way production code should construct one. The canonical constructor stays
     * public because reading a stored envelope back must reproduce the digest that was stored rather
     * than recompute a fresh one — a reader that recomputed could never detect the corruption the
     * digest exists to detect.</p>
 * @param eventId the stable event id used to identify the requested resource.
 * @param tenantId the stable tenant id used to identify the requested resource.
 * @param eventType domain event type authored by the caller.
 * @param processInstanceId the stable process instance id used to identify the requested resource.
 * @param traversalId the stable traversal id used to identify the requested resource.
 * @param invocationId the stable invocation id used to identify the requested resource.
 * @param attemptId the stable attempt id used to identify the requested resource.
 * @param causationId the stable causation id used to identify the requested resource.
 * @param correlationId the stable correlation id used to identify the requested resource.
 * @param graphVersion the graph version constraint applied while processing the request.
 * @param occurredAt instant at which the event occurred.
 * @param payload bounded payload carried by the pending work.
 * @return canonical event envelope ready for digesting and journal commit.
     */
    public static EventEnvelope of(UUID eventId, String tenantId, String eventType, UUID processInstanceId,
                                   UUID traversalId, UUID invocationId, UUID attemptId, UUID causationId,
                                   String correlationId, String graphVersion, Instant occurredAt,
                                   OpaquePayload payload) {
        EventDigest computed = EventDigest.over(canonicalForm(CURRENT_VERSION, eventId, tenantId, eventType,
                processInstanceId, traversalId, invocationId, attemptId, causationId, correlationId,
                graphVersion == null ? "" : graphVersion, occurredAt, payload));
        return new EventEnvelope(CURRENT_VERSION, eventId, tenantId, eventType, processInstanceId, traversalId,
                invocationId, attemptId, causationId, correlationId, graphVersion, occurredAt, payload, computed);
    }

/**
 * Returns the invocation that emitted this event.
 * @return invocation identifier, or {@code null} when no invocation caused it.
 */
    public Optional<UUID> invocation() {
        return Optional.ofNullable(invocationId);
    }

/**
 * Returns the node-attempt identity associated with this event.
 * @return attempt identifier, or {@code null} when not applicable.
 */
    public Optional<UUID> attempt() {
        return Optional.ofNullable(attemptId);
    }

/**
 * Returns the upstream event that caused this event, if causation is known.
 * @return causal event identifier, or {@code null} for an originating event.
 */
    public Optional<UUID> causation() {
        return Optional.ofNullable(causationId);
    }

    /**
     * Recomputes the digest from this envelope's own content and compares it with the digest it
     * carries.
     *
     * <p>An adapter calls this after reading an envelope back from storage. A {@code false} answer
     * means the stored bytes and the stored digest disagree, which is
     * {@link ExecutionStoreFailure.Corrupted} and must never be swallowed or retried.</p>
 * @return whether the stored digest matches this envelope's canonical content.
     */
    public boolean digestMatchesContent() {
        return digest.equals(EventDigest.over(canonicalForm(envelopeVersion, eventId, tenantId, eventType,
                processInstanceId, traversalId, invocationId, attemptId, causationId, correlationId,
                graphVersion, occurredAt, payload)));
    }

    /**
     * The length-prefixed canonical byte form the digest is taken over.
     *
     * <p>Field order here <em>is</em> the format. Changing the order, adding a field or removing one
     * changes every digest this build produces, so it must be accompanied by a
     * {@link #CURRENT_VERSION} bump; the version is the first thing encoded precisely so two
     * different formats can never produce the same bytes.</p>
     */
    private static byte[] canonicalForm(int envelopeVersion, UUID eventId, String tenantId, String eventType,
                                        UUID processInstanceId, UUID traversalId, UUID invocationId,
                                        UUID attemptId, UUID causationId, String correlationId,
                                        String graphVersion, Instant occurredAt, OpaquePayload payload) {
        var out = new ByteArrayOutputStream(256);
        putInt(out, envelopeVersion);
        putUuid(out, eventId);
        putText(out, tenantId);
        putText(out, eventType);
        putUuid(out, processInstanceId);
        putUuid(out, traversalId);
        putUuid(out, invocationId);
        putUuid(out, attemptId);
        putUuid(out, causationId);
        putText(out, correlationId);
        putText(out, graphVersion);
        putLong(out, occurredAt.getEpochSecond());
        putInt(out, occurredAt.getNano());
        putText(out, payload.contentType());
        putBytes(out, payload.bytes());
        return out.toByteArray();
    }

    private static void putUuid(ByteArrayOutputStream out, UUID value) {
        if (value == null) {
            out.write(ABSENT);
            return;
        }
        out.write(PRESENT);
        putLong(out, value.getMostSignificantBits());
        putLong(out, value.getLeastSignificantBits());
    }

    /** Absent and empty are encoded differently, so {@code null} and {@code ""} cannot collide. */
    private static void putText(ByteArrayOutputStream out, String value) {
        if (value == null) {
            out.write(ABSENT);
            return;
        }
        out.write(PRESENT);
        putBytes(out, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void putBytes(ByteArrayOutputStream out, byte[] value) {
        putInt(out, value.length);
        out.write(value, 0, value.length);
    }

    private static void putInt(ByteArrayOutputStream out, int value) {
        out.write(ByteBuffer.allocate(Integer.BYTES).putInt(value).array(), 0, Integer.BYTES);
    }

    private static void putLong(ByteArrayOutputStream out, long value) {
        out.write(ByteBuffer.allocate(Long.BYTES).putLong(value).array(), 0, Long.BYTES);
    }
}
