package ai.ravenroot.api.persistence;

import java.time.Duration;

/** Fenced outcome of one claimed command delivery. */
public sealed interface SagaOutboxSettlement {
    /** Records durable broker confirmation separately from participant completion. */
    record BrokerAccepted() implements SagaOutboxSettlement { }
    /** Records confirmed atomic inbox receipt and participant business effect. */
    record BusinessCompleted() implements SagaOutboxSettlement { }
    /**
     * Requests another bounded attempt.
     * @param delay positive backoff
     * @param safeReason redacted reason
     */
    record Retry(Duration delay, String safeReason) implements SagaOutboxSettlement {
        /** Validates the retry bound and diagnostic. */
        public Retry {
            if (delay == null || delay.isNegative() || delay.isZero() || delay.compareTo(Duration.ofDays(1)) > 0) {
                throw new IllegalArgumentException("retry delay must be in (0, 1 day]");
            }
            if (safeReason == null || safeReason.isBlank() || safeReason.length() > 512) {
                throw new IllegalArgumentException("safeReason must be non-blank and bounded");
            }
        }
    }
    /**
     * Marks delivery visibly exhausted.
     * @param safeReason redacted actionable reason
     */
    record Exhausted(String safeReason) implements SagaOutboxSettlement {
        /** Validates the bounded diagnostic. */
        public Exhausted {
            if (safeReason == null || safeReason.isBlank() || safeReason.length() > 512) {
                throw new IllegalArgumentException("safeReason must be non-blank and bounded");
            }
        }
    }
}
