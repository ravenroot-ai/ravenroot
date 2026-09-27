package ai.ravenroot.api.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable state and bounded participant receipt for one logical step occurrence.
 * @param occurrenceId durable logical occurrence identity
 * @param stepId logical step definition identity
 * @param invocationId graph invocation that created the occurrence
 * @param forwardOperationId stable participant forward-operation identity
 * @param compensationOperationId stable compensation-operation identity
 * @param payloadFingerprint SHA-256 binding identity to input
 * @param status durable participant outcome state
 * @param receipt bounded participant receipt
 * @param detail redacted actionable detail
 * @param updatedAt last durable transition instant
 */
public record SagaStepSnapshot(UUID occurrenceId, String stepId, UUID invocationId,
                               String forwardOperationId, String compensationOperationId,
                               String payloadFingerprint, SagaStepStatus status,
                               OpaquePayload receipt, String detail, Instant updatedAt) {
    private static final int MAX_RECEIPT_BYTES = 640 * 1024;
    /** Validates and defensively snapshots the durable value. */
    public SagaStepSnapshot {
        Objects.requireNonNull(occurrenceId, "occurrenceId");
        Objects.requireNonNull(invocationId, "invocationId");
        if (stepId == null || stepId.isBlank()) throw new IllegalArgumentException("stepId cannot be blank");
        forwardOperationId = operation(forwardOperationId, "forwardOperationId");
        compensationOperationId = operation(compensationOperationId, "compensationOperationId");
        if (payloadFingerprint == null || !payloadFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("payloadFingerprint must be SHA-256");
        }
        Objects.requireNonNull(status, "status");
        receipt = receipt == null ? OpaquePayload.empty("application/octet-stream") : receipt;
        if (receipt.size() > MAX_RECEIPT_BYTES) {
            throw new IllegalArgumentException("saga receipt exceeds 640 KiB");
        }
        detail = detail == null ? "" : detail;
        if (detail.length() > 512) throw new IllegalArgumentException("saga detail exceeds 512 characters");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    private static String operation(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException(name + " must be non-blank and bounded");
        }
        return value;
    }
}
