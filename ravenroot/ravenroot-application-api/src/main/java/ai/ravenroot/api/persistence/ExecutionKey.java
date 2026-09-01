package ai.ravenroot.api.persistence;

import java.util.UUID;

/**
 * Tenant-scoped identity of one stored process instance (ADR 0010 section 2).
 *
 * <p>{@code tenantId} participates in every key. A read scoped to a mismatched tenant fails with
 * {@link ExecutionStoreFailure.NotFound}, never with a denial, so the store cannot be used as a
 * cross-tenant existence oracle. The port carries the tenant opaquely and does not define the
 * isolation mechanism.</p>
 * @param tenantId the stable tenant id used to identify the requested resource.
 * @param processInstanceId the stable process instance id used to identify the requested resource.
 */
public record ExecutionKey(String tenantId, UUID processInstanceId) {
    /** Rejects an execution identity that cannot safely address tenant-scoped state. */
    public ExecutionKey {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId cannot be blank");
        }
        if (processInstanceId == null) {
            throw new IllegalArgumentException("processInstanceId cannot be null");
        }
    }
}
