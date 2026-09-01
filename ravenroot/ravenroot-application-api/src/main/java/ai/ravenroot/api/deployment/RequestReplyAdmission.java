package ai.ravenroot.api.deployment;

import java.util.Objects;

/** The admission decision for one request/reply offer. */
public sealed interface RequestReplyAdmission {
/**
 * Defines the accepted contract exposed to Ravenroot integrators.
 * @param exchange live request-scoped handle when the traversal was admitted.
 */
    record Accepted(RequestReplyExchange exchange) implements RequestReplyAdmission {
/**
 * Requires the request-scoped exchange that exposes completion and cooperative cancellation.
 */
        public Accepted {
            Objects.requireNonNull(exchange, "exchange");
        }
    }

/**
 * Defines the refused contract exposed to Ravenroot integrators.
 * @param reason expected reason no traversal or exchange was created.
 */
    record Refused(RequestReplyRefusal reason) implements RequestReplyAdmission {
/**
 * Requires a structured refusal reason so callers never parse admission text.
 */
        public Refused {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
