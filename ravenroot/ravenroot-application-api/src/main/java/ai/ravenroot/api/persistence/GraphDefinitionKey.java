package ai.ravenroot.api.persistence;

/**
 * Tenant-scoped identity of one stored graph definition.
 *
 * <p>{@code tenantId} participates in the key, exactly as it does in {@link ExecutionKey}, and for
 * the same reason: a read scoped to a mismatched tenant fails with
 * {@link GraphDefinitionStoreFailure.NotFound} and never with a denial, so the store cannot be used
 * as a cross-tenant existence oracle. Since the address is derived from content, a denial would be a
 * particularly sharp oracle — it would confirm that some other tenant holds a document whose bytes
 * the caller already possesses.</p>
 *
 * <p>The port carries the tenant opaquely and does not define the isolation mechanism.</p>
 *
 * @param tenantId stable tenant id that owns the definition.
 * @param contentId address of the canonical document within that tenant.
 */
public record GraphDefinitionKey(String tenantId, GraphContentId contentId) {

    /** Rejects a definition identity that cannot safely address tenant-scoped content. */
    public GraphDefinitionKey {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId cannot be blank");
        }
        if (contentId == null) {
            throw new IllegalArgumentException("contentId cannot be null");
        }
    }
}
