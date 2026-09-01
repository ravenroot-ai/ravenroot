package ai.ravenroot.api.deployment;

import ai.ravenroot.api.application.ExecutionOutcome;
import ai.ravenroot.api.application.ProcessInstanceStatus;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The exactly-once terminal observation of one live request/reply traversal.
 *
 * <p>The wrapper is deliberate: cancellation and timeout are terminal for the live waiter, not new
 * persisted process states. They therefore do not widen {@link ProcessInstanceStatus} or pretend
 * that a cooperatively-cancelled node has already stopped. A completed or failed traversal carries
 * its ordinary {@link ExecutionOutcome}; a detached waiter does not manufacture one.</p>
 * @param processInstanceId stable process instance id for this declaration.
 * @param traversalId stable traversal id for this declaration.
 * @param state terminal waiter state, including cancellation or timeout.
 * @param executionOutcome ordinary traversal outcome only for completed or failed requests.
 */
public record RequestReplyOutcome(UUID processInstanceId, UUID traversalId,
                                  RequestReplyTerminalState state,
                                  Optional<ExecutionOutcome> executionOutcome) {
/**
 * Keeps waiter-only terminal states from fabricating execution results and verifies outcome identity.
 */
    public RequestReplyOutcome {
        Objects.requireNonNull(processInstanceId, "processInstanceId");
        Objects.requireNonNull(traversalId, "traversalId");
        Objects.requireNonNull(state, "state");
        executionOutcome = Objects.requireNonNull(executionOutcome, "executionOutcome");
        executionOutcome.ifPresent(outcome -> {
            if (!processInstanceId.equals(outcome.processInstanceId())
                    || !traversalId.equals(outcome.traversalId())) {
                throw new IllegalArgumentException("execution outcome identity does not match the request");
            }
        });
        boolean observed = executionOutcome.isPresent();
        if ((state == RequestReplyTerminalState.COMPLETED || state == RequestReplyTerminalState.FAILED) != observed) {
            throw new IllegalArgumentException("completed and failed outcomes require an execution outcome; "
                    + "cancelled and timed-out outcomes must not manufacture one");
        }
        if (state == RequestReplyTerminalState.COMPLETED
                && executionOutcome.orElseThrow().status() != ProcessInstanceStatus.COMPLETED) {
            throw new IllegalArgumentException("completed request/reply outcome requires COMPLETED execution status");
        }
        if (state == RequestReplyTerminalState.FAILED
                && executionOutcome.orElseThrow().status() != ProcessInstanceStatus.FAILED) {
            throw new IllegalArgumentException("failed request/reply outcome requires FAILED execution status");
        }
    }

/**
 * The bounded terminal payload, or {@code null} for failure, cancellation and timeout.
 * @return bounded traversal payload only for a completed execution; otherwise {@code null}.
 */
    public Object payload() {
        return executionOutcome.map(ExecutionOutcome::payload).orElse(null);
    }
}
