package ai.ravenroot.api.persistence;

/**
 * Authoritative actionable Human Task counts before paging.
 *
 * @param pending number of authorized waiting or escalated tasks.
 * @param escalated number of those tasks currently escalated.
 */
public record HumanTaskAttentionCounts(long pending, long escalated) {
    /** Validates nonnegative internally consistent counts. */
    public HumanTaskAttentionCounts {
        if (pending < 0 || escalated < 0 || escalated > pending) {
            throw new IllegalArgumentException("invalid human-task attention counts");
        }
    }
}
