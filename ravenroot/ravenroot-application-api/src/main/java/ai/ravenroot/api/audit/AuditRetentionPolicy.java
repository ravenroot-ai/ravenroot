package ai.ravenroot.api.audit;

import java.time.Duration;
import java.util.Objects;

/**
 * An adapter's own retention rule, declared synchronously so a caller can read it rather than
 * discover it in production, following the {@code ExecutionStore.journalRetention()}
 * self-description precedent (ADR 0011 section 7).
 *
 * <p>This is a declaration, not an enforcement mechanism: the concrete value stays deployment
 * configuration, and nothing calls {@link AuditTrail#redact} automatically. An operator or a
 * scheduled job decides when to redact, and does so through the authorized, self-auditing path
 * ({@code AuthorizedAuditTrail}), never by deleting rows directly.
 *
 * @param minimumRetention the shortest time a record is guaranteed to remain unredacted after it is
 *                          appended
 */
public record AuditRetentionPolicy(Duration minimumRetention) {
/**
 * Represents the retention behavior declared by an audit-trail adapter.
 */
    public AuditRetentionPolicy {
        Objects.requireNonNull(minimumRetention, "minimumRetention");
        if (minimumRetention.isNegative()) {
            throw new IllegalArgumentException("minimumRetention cannot be negative");
        }
    }
}
