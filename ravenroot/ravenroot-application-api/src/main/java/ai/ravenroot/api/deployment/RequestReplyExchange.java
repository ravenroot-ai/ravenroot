package ai.ravenroot.api.deployment;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * One admitted, process-local request/reply traversal.
 *
 * <p>The handle is capability-bound to this request. It has no operation accepting an identifier,
 * so it cannot cancel or complete another traversal. {@link #completion()} is read-only: callers
 * can observe the terminal winner but cannot publish one.</p>
 */
public interface RequestReplyExchange {
/**
 * Runtime-issued correlation identity for this live waiter.
 * @return correlation identity allocated for this waiter.
 */
    UUID correlationId();

/**
 * Returns the process instance that hosts the admitted traversal.
 * @return runtime-issued process instance identifier.
 */
    UUID processInstanceId();

/**
 * Returns the traversal associated with this waiter.
 * @return runtime-issued traversal identifier.
 */
    UUID traversalId();

/**
 * The absolute, finite deadline accepted for this request.
 * @return absolute instant after which the exchange cannot deliver a result.
 */
    Instant deadline();

/**
 * Exactly one terminal outcome.
 * @return stage completing exactly once with the traversal's terminal outcome.
 */
    CompletionStage<RequestReplyOutcome> completion();

    /**
     * Detaches this waiter and asks the traversal to stop cooperatively.
     *
     * @return {@code true} only when this call won the terminal race; {@code false} when the
     *         exchange was already terminal
     */
    boolean cancel();
}
