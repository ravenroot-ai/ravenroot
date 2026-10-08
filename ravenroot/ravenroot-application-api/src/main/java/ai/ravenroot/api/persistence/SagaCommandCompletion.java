package ai.ravenroot.api.persistence;

import java.time.Instant;
import java.util.LinkedHashMap;

/** Deterministic aggregate fold applied atomically with application-command business completion. */
public final class SagaCommandCompletion {
    private SagaCommandCompletion() { }

    /**
     * Confirms the step occurrence named by a completed outbox command and advances saga disposition.
     *
     * @param current saga snapshot locked by the execution store
     * @param intent completed immutable command intent
     * @param now store clock instant
     * @return next compare-and-set revision
     */
    public static SagaSnapshot fold(SagaSnapshot current, SagaCommandIntent intent, Instant now) {
        if (!current.sagaId().equals(intent.sagaId())) {
            throw new IllegalArgumentException("completed command belongs to another saga");
        }
        SagaStepSnapshot matched = current.occurrences().values().stream()
                .filter(step -> intent.operationId().equals(step.forwardOperationId())
                        || intent.operationId().equals(step.compensationOperationId()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "completed command does not name a frozen saga occurrence"));
        boolean compensation = intent.operationId().equals(matched.compensationOperationId());
        SagaStepStatus status = compensation ? SagaStepStatus.COMPENSATED : SagaStepStatus.CONFIRMED_SUCCESS;
        var completed = new SagaStepSnapshot(matched.occurrenceId(), matched.stepId(), matched.invocationId(),
                matched.forwardOperationId(), matched.compensationOperationId(), matched.payloadFingerprint(),
                status, matched.receipt(), "participant business completion confirmed", now);
        var occurrences = new LinkedHashMap<>(current.occurrences());
        occurrences.put(completed.occurrenceId(), completed);
        SagaDisposition disposition;
        String reason = "";
        if (compensation) {
            boolean allCompensated = occurrences.values().stream()
                    .filter(step -> step.status() != SagaStepStatus.CONFIRMED_NO_EFFECT)
                    .allMatch(step -> step.status() == SagaStepStatus.COMPENSATED);
            disposition = allCompensated ? SagaDisposition.COMPENSATED : SagaDisposition.COMPENSATION_PENDING;
            if (!allCompensated) reason = "remaining compensations are pending";
        } else if (current.cancellationRequested()) {
            disposition = SagaDisposition.COMPENSATION_PENDING;
            reason = "participant completed after cancellation; durable compensation required";
        } else {
            boolean allSucceeded = current.definition().steps().keySet().stream().allMatch(stepId ->
                    occurrences.values().stream().anyMatch(step -> step.stepId().equals(stepId)
                            && step.status() == SagaStepStatus.CONFIRMED_SUCCESS));
            disposition = allSucceeded ? SagaDisposition.SUCCEEDED : SagaDisposition.RUNNING;
        }
        return new SagaSnapshot(current.key(), current.sagaId(), current.traversalId(), current.definition(),
                current.revision() + 1,
                disposition, current.cancellationRequested(), occurrences, current.deadline(),
                current.createdAt(), now, reason, current.graphCompleted());
    }

    /**
     * Parks a command whose bounded delivery attempts are exhausted as actionable unresolved work.
     * @param current saga snapshot locked by the execution store
     * @param intent exhausted command intent
     * @param now store clock instant
     * @param reason bounded redacted operator reason
     * @return next compare-and-set revision
     */
    public static SagaSnapshot exhausted(SagaSnapshot current, SagaCommandIntent intent,
                                         Instant now, String reason) {
        SagaStepSnapshot matched = current.occurrences().values().stream()
                .filter(step -> intent.operationId().equals(step.forwardOperationId())
                        || intent.operationId().equals(step.compensationOperationId()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "exhausted command does not name a frozen saga occurrence"));
        boolean compensation = intent.operationId().equals(matched.compensationOperationId());
        SagaStepStatus status = compensation ? SagaStepStatus.COMPENSATION_UNKNOWN
                : SagaStepStatus.OUTCOME_UNKNOWN;
        String safeReason = reason == null || reason.isBlank() ? "command delivery attempts exhausted" : reason;
        if (safeReason.length() > 512) safeReason = safeReason.substring(0, 512);
        var failed = new SagaStepSnapshot(matched.occurrenceId(), matched.stepId(), matched.invocationId(),
                matched.forwardOperationId(), matched.compensationOperationId(), matched.payloadFingerprint(),
                status, matched.receipt(), safeReason, now);
        var occurrences = new LinkedHashMap<>(current.occurrences());
        occurrences.put(failed.occurrenceId(), failed);
        return new SagaSnapshot(current.key(), current.sagaId(), current.traversalId(), current.definition(),
                current.revision() + 1,
                SagaDisposition.UNRESOLVED, current.cancellationRequested(), occurrences, current.deadline(),
                current.createdAt(), now, safeReason, current.graphCompleted());
    }
}
