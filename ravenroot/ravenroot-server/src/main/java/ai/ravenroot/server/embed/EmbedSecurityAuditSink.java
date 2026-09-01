package ai.ravenroot.server.embed;

import java.time.Instant;
import java.util.Objects;

/** Fail-closed audit seam; events deliberately contain no ticket, bearer, key or graph coordinate. */
@FunctionalInterface
public interface EmbedSecurityAuditSink {
    void record(Event event);

    record Event(Instant occurredAt, String requestId, String tenantId, String principal,
                 Phase phase, Outcome outcome) {
        public Event {
            Objects.requireNonNull(occurredAt, "occurredAt");
            requestId = text(requestId, "requestId");
            tenantId = text(tenantId, "tenantId");
            principal = text(principal, "principal");
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(outcome, "outcome");
        }
        private static String text(String value, String name) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is blank");
            return value;
        }
    }

    enum Phase { SESSION_CREATED, TICKET_CONSUMED, PARENT_ACKNOWLEDGED, BEARER_ISSUED, PROJECTION_READ }
    enum Outcome { ALLOWED, DENIED, FAILED }
}
