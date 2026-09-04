package ai.ravenroot.api.publication;

/**
 * Stable bounded identifier of one policy rule or guard-owned refusal.
 *
 * @param value portable dotted identifier
 */
public record PublicationRuleId(String value) implements Comparable<PublicationRuleId> {
    /** Validates a portable dotted identifier. */
    public PublicationRuleId {
        if (value == null || !value.matches("[a-z][a-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("publication rule id must be a bounded lowercase identifier");
        }
    }

    @Override
    public int compareTo(PublicationRuleId other) {
        return value.compareTo(other.value);
    }
}
