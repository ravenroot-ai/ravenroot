package ai.ravenroot.api.deployment;

import ai.ravenroot.api.payload.PayloadLimits;

import java.time.Duration;
import java.util.Objects;

/**
 * Operator ceilings for one deployment's live request/reply surface.
 * @param maxPendingWaiters max pending waiters supplied to this declaration.
 * @param maxDeadline the max deadline constraint applied while processing the request.
 * @param requestPayload request payload supplied to this declaration.
 * @param outcomePayload outcome payload supplied to this declaration.
 */
public record RequestReplyLimits(int maxPendingWaiters, Duration maxDeadline,
                                 PayloadLimits requestPayload, PayloadLimits outcomePayload) {
    public static final int MAX_PENDING_WAITERS = 100_000;
    public static final Duration DEFAULT_MAX_DEADLINE = Duration.ofSeconds(30);
    public static final Duration MAX_SUPPORTED_DEADLINE = Duration.ofMinutes(10);

/**
 * Enforces bounded waiter capacity, a positive supported deadline, and both payload policies.
 */
    public RequestReplyLimits {
        if (maxPendingWaiters < 1 || maxPendingWaiters > MAX_PENDING_WAITERS) {
            throw new IllegalArgumentException("maxPendingWaiters must be between 1 and "
                    + MAX_PENDING_WAITERS);
        }
        Objects.requireNonNull(maxDeadline, "maxDeadline");
        if (maxDeadline.isZero() || maxDeadline.isNegative()
                || maxDeadline.compareTo(MAX_SUPPORTED_DEADLINE) > 0) {
            throw new IllegalArgumentException("maxDeadline must be positive and no greater than "
                    + MAX_SUPPORTED_DEADLINE);
        }
        Objects.requireNonNull(requestPayload, "requestPayload");
        Objects.requireNonNull(outcomePayload, "outcomePayload");
    }

/**
 * Defaults aligned with the deployment's already-finite ingress capacity.
 * @param ingressCapacity existing deployment ingress capacity used to derive a safe waiter bound.
 * @return defaults that preserve legacy positive capacities while clamping waiter storage safely.
 */
    public static RequestReplyLimits defaults(int ingressCapacity) {
        // The legacy deployment constructor historically accepted every positive int. Do not make
        // adding request/reply reject a previously valid large ingress capacity; the new waiter
        // surface is independently capped at its supported safety ceiling.
        int boundedWaiters = Math.max(1, Math.min(ingressCapacity, MAX_PENDING_WAITERS));
        return new RequestReplyLimits(boundedWaiters, DEFAULT_MAX_DEADLINE,
                PayloadLimits.DEFAULTS, PayloadLimits.DEFAULTS);
    }
}
