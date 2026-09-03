package ai.ravenroot.api.persistence;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A named durable handler to be created inside a batch (PERS-05).
 *
 * <p>This is what makes a long wait survive the death of whatever was waiting. The runtime writes it
 * in the <em>same</em> batch as the {@link ExecutionTransition.TraversalTransitioned} that moves the
 * traversal to {@link ai.ravenroot.api.application.TraversalStatus#WAITING}, so there is no instant
 * at which a process is waiting and nothing durable records what it is waiting for. It carries no
 * callback, no continuation and no actor reference: {@link ExecutionStore} contains no callbacks at
 * all, and a handler that named an in-process object could not be honoured after a restart, which is
 * the single case this type exists for.</p>
 *
 * <h2>Two keys, two different jobs</h2>
 * <ul>
 *   <li>{@link #correlationKey()} is the <strong>business identity a trigger presents</strong>. It is
 *   unique per {@code (tenantId, name, correlationKey)} across handlers that are not yet terminal, so
 *   a trigger resolves to exactly one live handler. Terminal handlers are excluded from that
 *   uniqueness on purpose: they are retained for audit and for duplicate detection, and including
 *   them would make every correlation key single-use for the lifetime of the store.</li>
 *   <li>{@link #deduplicationKey()} makes <strong>registration itself</strong> exactly-once. It is
 *   unique per {@code (tenantId, deduplicationKey)} across <em>every</em> handler, terminal included,
 *   and a repeated registration carrying one is answered as a no-op success rather than creating a
 *   second handler. Without it, a crash between the waiting transition and this registration would be
 *   unrecoverable in the direction that duplicates: a retry would register a second handler for the
 *   same wait, and a single trigger would then resume the process once while leaving the other
 *   handler waiting forever.</li>
 * </ul>
 *
 * <p>Both are bounded at {@link #MAX_KEY_UTF8_BYTES}. They become index keys in every adapter, and an
 * unbounded caller-supplied index key is a durable growth channel rather than a validation nicety.</p>
 *
 * @param handlerId        stable identity of this handler, minted by the caller and used as the
 *                         {@link PendingWork#workItemId()} of the trigger it eventually produces
 * @param name             opaque handler name, the vocabulary a trigger names alongside the
 *                         correlation key
 * @param traversalId      the traversal that is waiting; must exist in the post-fold aggregate
 * @param invocationId     the node invocation that is waiting; must exist in that traversal
 * @param correlationKey   business identity an inbound trigger presents
 * @param deduplicationKey registration idempotency key
 * @param payloadSchema    the shape a trigger payload must have
 * @param authorization    what a principal must present before a trigger may act on this handler
 */
public record HandlerRegistration(UUID handlerId, String name, UUID traversalId, UUID invocationId,
                                  String correlationKey, String deduplicationKey,
                                  HandlerPayloadSchema payloadSchema, HandlerAuthorization authorization) {

    /** Inclusive bound on every caller-supplied handler key, in UTF-8 bytes. */
    public static final int MAX_KEY_UTF8_BYTES = 256;

    /** Validates the durable registration before an adapter can be asked to store it. */
    public HandlerRegistration {
        if (handlerId == null) throw new IllegalArgumentException("handlerId cannot be null");
        name = requireBoundedKey(name, "name");
        if (traversalId == null) throw new IllegalArgumentException("traversalId cannot be null");
        if (invocationId == null) throw new IllegalArgumentException("invocationId cannot be null");
        correlationKey = requireBoundedKey(correlationKey, "correlationKey");
        deduplicationKey = requireBoundedKey(deduplicationKey, "deduplicationKey");
        if (payloadSchema == null) throw new IllegalArgumentException("payloadSchema cannot be null");
        if (authorization == null) throw new IllegalArgumentException("authorization cannot be null");
    }

    /**
     * Rejects a blank or oversized handler key.
     *
     * <p>Measured in UTF-8 bytes rather than in {@code char}s so the bound means the same thing for
     * an ASCII key and for one that is not, which is what an adapter's column width actually is.</p>
     * @param value candidate handler key.
     * @param name field name used in the rejection message.
     * @return the accepted key, unchanged.
     */
    public static String requireBoundedKey(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_KEY_UTF8_BYTES) {
            throw new IllegalArgumentException(name + " is " + bytes + " UTF-8 bytes, above the "
                    + MAX_KEY_UTF8_BYTES + "-byte bound");
        }
        return value;
    }
}
