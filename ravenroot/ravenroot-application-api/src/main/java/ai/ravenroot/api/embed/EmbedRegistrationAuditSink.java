package ai.ravenroot.api.embed;

import java.time.Instant;
import java.util.Objects;

/**
 * Fail-closed audit seam for operator provision and revoke.
 *
 * <p>The {@link Event} shape is the whole redaction policy, enforced by construction rather than by
 * a rule a caller has to remember. It carries who acted, in which tenant, on which registration id,
 * at which revision, and what happened. It carries no bearer, ticket, challenge, key, parent origin,
 * graph identifier, digest or node — nothing that would let a reader of the audit trail reconstruct
 * the graph or replay a credential. {@code detail} is an enum name, never free text: a free-text
 * field is how a secret eventually reaches a log.</p>
 *
 * <p>An implementation that cannot append must throw. {@link AuthorizedEmbedRegistrationAdministration}
 * treats that as a refusal of the operation, so an unrecorded provision does not happen.</p>
 */
@FunctionalInterface
public interface EmbedRegistrationAuditSink {

    /** Appends an immutable, redacted operator action event.
     * @param event event whose fixed fields are safe to retain in the audit trail
     */
    void record(Event event);

    /** Redacted audit record for one provision or revoke decision.
     * @param occurredAt instant at which the operation was observed
     * @param requestId correlation identifier of the operator request
     * @param tenantId tenant containing the registration
     * @param principal authenticated operator principal
     * @param registrationId registration affected by the operation
     * @param revision relevant aggregate revision
     * @param phase provision or revoke action
     * @param outcome coarse result safe to disclose
     * @param detail bounded upper-case detail token
     */
    record Event(Instant occurredAt, String requestId, String tenantId, String principal,
                 String registrationId, long revision, Phase phase, Outcome outcome, String detail) {
        /** Validates the immutable redacted audit shape. */
        public Event {
            Objects.requireNonNull(occurredAt, "occurredAt");
            requestId = text(requestId, "requestId");
            tenantId = text(tenantId, "tenantId");
            principal = text(principal, "principal");
            registrationId = text(registrationId, "registrationId");
            if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(outcome, "outcome");
            detail = token(detail, "detail");
        }

        private static String text(String value, String name) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is blank");
            return value;
        }

        /**
         * Enforces the «never free text» rule this type's Javadoc states, by construction.
         *
         * <p>It was previously enforced by convention: {@code detail} was validated only as non-blank,
         * so nothing stopped a caller appending a digest, a path or an origin to it -- and appending
         * "which snapshot we pinned" to an outcome token is a plausible thing for someone to do. The
         * shape below (upper-case screaming snake, no separators, bounded) admits every token this
         * package produces and admits no identifier, digest, URL or free sentence.</p>
         */
        private static String token(String value, String name) {
            String text = text(value, name);
            if (text.length() > 64) {
                throw new IllegalArgumentException(name + " must be a short token, not free text");
            }
            for (int index = 0; index < text.length(); index++) {
                char character = text.charAt(index);
                boolean allowed = (character >= 'A' && character <= 'Z')
                        || (character >= '0' && character <= '9') || character == '_';
                if (!allowed) {
                    throw new IllegalArgumentException(
                            name + " must be an upper-case token, not free text");
                }
            }
            return text;
        }
    }

    /** Operation phase represented by an audit event. */
    enum Phase { /** Provision action. */ PROVISION, /** Revoke action. */ REVOKE }

    /** {@code ATTEMPTED} is recorded before the store is asked, so a crash mid-write leaves a trace. */
    enum Outcome {
        /** Store operation is about to be attempted. */ ATTEMPTED,
        /** Operation was authorized and persisted. */ ALLOWED,
        /** Compare-and-set revision conflicted. */ CONFLICT,
        /** Policy or authorization denied the operation. */ DENIED,
        /** Persistence or audit recording failed. */ FAILED
    }
}
