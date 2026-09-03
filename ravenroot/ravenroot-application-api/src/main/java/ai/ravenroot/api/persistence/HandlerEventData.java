package ai.ravenroot.api.persistence;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable journal vocabulary for the handler lifecycle, and the minimal body its events carry
 * (PERS-05).
 *
 * <p>The event types are {@code String} constants rather than
 * {@link ai.ravenroot.api.application.ExecutionEventType} members, because
 * {@link EventEnvelope#eventType()} is deliberately an open string — "the persistence port must not
 * enumerate domain vocabulary, or every new event type would be a port change and a schema
 * migration" — while {@code ExecutionEventType} is the live, in-process runtime vocabulary that
 * adapters switch over exhaustively. A handler event is not an in-process runtime transition: it can
 * be produced by a process that was not running when the wait began.</p>
 *
 * <p>The body is the handler id and nothing else, following {@link EdgeTraversalEventData}'s rule
 * exactly: the durable identity replay cannot otherwise recover is the only thing worth duplicating
 * into the journal. The process, traversal and invocation are already envelope fields, so an event
 * read back distinguishes all four levels — process, traversal, handler, node invocation — without
 * any of them being inferred.</p>
 */
public final class HandlerEventData {

    /** A durable handler was registered and its process began waiting. */
    public static final String HANDLER_REGISTERED = "HANDLER_REGISTERED";

    /** A waiting handler exceeded an attention threshold and stays resolvable. */
    public static final String HANDLER_ESCALATED = "HANDLER_ESCALATED";

    /** A handler's wait ended without a trigger. */
    public static final String HANDLER_EXPIRED = "HANDLER_EXPIRED";

    /** An authorized principal refused the handler's task. */
    public static final String HANDLER_DENIED = "HANDLER_DENIED";

    /** An authorized trigger supplied an outcome and the process re-entered. */
    public static final String HANDLER_RESOLVED = "HANDLER_RESOLVED";

    /**
     * A trigger was refused deterministically — duplicate, late, cross-tenant, unauthorized or
     * non-conforming.
     *
     * <p>Journalled rather than dropped. Every one of those causes is normal on a healthy system, so
     * none of them is a platform failure; but a handler whose refusal rate is climbing is a handler
     * being triggered by something that believes it should be succeeding, and without an event
     * nothing distinguishes that from nothing happening. The same reasoning
     * {@code JOIN_ARRIVAL_DISCARDED} already records for fan-in.</p>
     */
    public static final String HANDLER_TRIGGER_REFUSED = "HANDLER_TRIGGER_REFUSED";

    /** Media type used for the strict UTF-8 handler identity. */
    public static final String CONTENT_TYPE = "application/vnd.ravenroot.handler-id; charset=utf-8";

    private HandlerEventData() {
    }

    /**
     * Tests whether a durable event type belongs to the handler lifecycle.
     * @param eventType domain event type recorded in the durable journal.
     * @return whether the type is one of this class's constants.
     */
    public static boolean isHandlerEvent(String eventType) {
        return HANDLER_REGISTERED.equals(eventType) || HANDLER_ESCALATED.equals(eventType)
                || HANDLER_EXPIRED.equals(eventType) || HANDLER_DENIED.equals(eventType)
                || HANDLER_RESOLVED.equals(eventType) || HANDLER_TRIGGER_REFUSED.equals(eventType);
    }

    /**
     * Returns the durable event type that records reaching {@code status}.
     * @param status handler lifecycle state just entered.
     * @return the journal event type for that state.
     */
    public static String eventTypeFor(HandlerStatus status) {
        return switch (status) {
            case WAITING -> HANDLER_REGISTERED;
            case ESCALATED -> HANDLER_ESCALATED;
            case EXPIRED -> HANDLER_EXPIRED;
            case DENIED -> HANDLER_DENIED;
            case RESOLVED -> HANDLER_RESOLVED;
        };
    }

    /**
     * Creates the immutable event body for one handler identity.
     * @param handlerId stable identity of the handler the event is about.
     * @return immutable payload using the dedicated handler media type.
     */
    public static OpaquePayload payload(UUID handlerId) {
        if (handlerId == null) {
            throw new IllegalArgumentException("handlerId cannot be null");
        }
        return OpaquePayload.of(handlerId.toString().getBytes(StandardCharsets.UTF_8), CONTENT_TYPE);
    }

    /**
     * Reads a handler identity only from this contract's exact media type and strict UTF-8 bytes.
     *
     * <p>Malformed or foreign payloads stay absent rather than becoming a plausible-looking identity,
     * which is the rule {@link EdgeTraversalEventData#edgeId(OpaquePayload)} already sets: a value
     * guessed out of a body that was never this contract's would be attributed to a handler that may
     * exist and may belong to someone else.</p>
     * @param payload payload to inspect.
     * @return decoded handler identity, or empty for foreign or malformed data.
     */
    public static Optional<UUID> handlerId(OpaquePayload payload) {
        if (payload == null || !CONTENT_TYPE.equals(payload.contentType())) {
            return Optional.empty();
        }
        // A canonical UUID is 36 ASCII characters; anything longer cannot be one and must not be
        // decoded merely to be rejected.
        if (payload.size() != 36) {
            return Optional.empty();
        }
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload.bytes())).toString();
            UUID decoded = UUID.fromString(value);
            // UUID.fromString accepts some non-canonical spellings; round-tripping rejects anything
            // whose stored text is not the exact canonical form the encoder produced.
            return decoded.toString().equals(value) ? Optional.of(decoded) : Optional.empty();
        } catch (CharacterCodingException | IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }
}
