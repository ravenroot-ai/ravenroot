package ai.ravenroot.core.handler;

import ai.ravenroot.api.persistence.HandlerStatus;

import java.util.UUID;

/**
 * What happened to one attempt to act on a durable handler (PERS-05).
 *
 * <p>A sealed result rather than an exception channel, because refusal is the ordinary case here and
 * every arm of it has to be audited. Four of the five arms are refusals, and all four are
 * <strong>deterministic</strong>: repeating the identical trigger produces the identical arm, before
 * and after a restart, because each is decided from durable state alone rather than from a
 * deduplication window that could age out.</p>
 *
 * <p>{@link NotFound} deliberately absorbs both "no such correlation key" and "that handler belongs
 * to another tenant". Splitting them would turn this call into a cross-tenant existence oracle: a
 * caller could enumerate another tenant's live correlation keys by observing which ones came back
 * as unauthorized rather than as unknown. {@link ExecutionKey}'s own contract already fixes that
 * rule for the store, and it must not be undone one layer up.</p>
 */
public sealed interface HandlerTriggerOutcome {

    /**
     * Whether the trigger changed durable state.
     * @return {@code true} only for {@link Accepted}.
     */
    boolean accepted();

    /**
     * Payload-safe explanation, suitable for an audit reason and an operator-facing message.
     * @return bounded reason text that never contains payload bytes.
     */
    String reason();

    /**
     * The handler moved to a new state and, when that state resumes the process, a re-entry traversal
     * was committed with it.
     * @param handlerId the stable handler id used to identify the requested resource.
     * @param status state the handler now holds.
     * @param resumeTraversalId re-entry traversal committed with the transition, or {@code null} for
     *                          an escalation.
     */
    record Accepted(UUID handlerId, HandlerStatus status, UUID resumeTraversalId)
            implements HandlerTriggerOutcome {
        @Override
        public boolean accepted() {
            return true;
        }

        @Override
        public String reason() {
            return "handler " + handlerId + " is " + status;
        }
    }

    /** No live handler of this tenant answers to that name and correlation key. */
    record NotFound() implements HandlerTriggerOutcome {
        @Override
        public boolean accepted() {
            return false;
        }

        @Override
        public String reason() {
            return "no live handler matches the presented name and correlation key";
        }
    }

    /**
     * The principal does not hold what the handler's registration declared.
     * @param detail payload-safe description of the unmet requirement.
     */
    record Unauthorized(String detail) implements HandlerTriggerOutcome {
        @Override
        public boolean accepted() {
            return false;
        }

        @Override
        public String reason() {
            return "the principal is not authorized for this handler: " + detail;
        }
    }

    /**
     * The handler already reached a state that accepts no further trigger — the duplicate and the
     * late arrival, which are the same fact.
     * @param handlerId the stable handler id used to identify the requested resource.
     * @param current state the handler already holds.
     */
    record AlreadySettled(UUID handlerId, HandlerStatus current) implements HandlerTriggerOutcome {
        @Override
        public boolean accepted() {
            return false;
        }

        @Override
        public String reason() {
            return "handler " + handlerId + " is already " + current;
        }
    }

    /**
     * The payload does not match the shape the handler declared at registration.
     * @param detail payload-safe non-conformance description, never the bytes.
     */
    record PayloadRefused(String detail) implements HandlerTriggerOutcome {
        @Override
        public boolean accepted() {
            return false;
        }

        @Override
        public String reason() {
            return "the trigger payload was refused: " + detail;
        }
    }
}
