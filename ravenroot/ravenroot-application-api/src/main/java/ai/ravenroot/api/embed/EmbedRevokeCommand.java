package ai.ravenroot.api.embed;

/**
 * One operator revocation, also a compare-and-set.
 *
 * <p>{@code tenantId} is carried so the store can refuse a revocation aimed at another tenant's
 * registration id without the caller having to have read the aggregate first — a read-then-write
 * check would be a race, and refusing inside the same transaction as the write is not.</p>
 *
 * @param registrationId tenant-scoped registration to revoke
 * @param tenantId tenant that owns the registration
 * @param expectedRevision compare-and-set revision that must still be current
 */
public record EmbedRevokeCommand(String registrationId, String tenantId, long expectedRevision) {

    /** Rejects blank identity fields and revisions before the first persisted revision. */
    public EmbedRevokeCommand {
        registrationId = requireText(registrationId, "registrationId");
        tenantId = requireText(tenantId, "tenantId");
        if (expectedRevision < 1) {
            throw new IllegalArgumentException("a revocation must name the revision it withdraws");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
