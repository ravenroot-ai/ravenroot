package ai.ravenroot.server.ratelimit;

import java.time.Instant;
import java.util.Objects;

/**
 * One refused request, described in fields an operator can act on.
 *
 * <p>The client address is recorded deliberately. It is the field that lets an operator block or
 * investigate a source, so omitting it would leave the record true but useless. It is safe to record
 * because it has already passed {@link IpAddresses}: by the time it reaches here it is a canonical IP
 * literal, drawn from a tiny alphabet and bounded to 45 characters, so it cannot carry a log-injection
 * payload or an unbounded string. Every other field is either a server-defined constant or an
 * identifier the authenticator has already validated.</p>
 *
 * <p>{@code tenantId} and {@code subject} are {@code "-"} when the limit fired before authentication
 * resolved an identity. That is the honest value: pre-authentication the server genuinely does not know
 * who is calling, and writing anything else would invent attribution.</p>
 */
public record RateLimitAuditEvent(Instant occurredAt, String requestId, String clientAddress,
                                  boolean forwarded, String tenantId, String subject, String method,
                                  String path, String code, String scope, int status,
                                  long retryAfterSeconds) {
    /** Placeholder for an identity that is genuinely unknown at the point the limit fired. */
    public static final String UNKNOWN = "-";

    public RateLimitAuditEvent {
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        requestId = Objects.requireNonNull(requestId, "requestId");
        clientAddress = Objects.requireNonNullElse(clientAddress, UNKNOWN);
        tenantId = Objects.requireNonNullElse(tenantId, UNKNOWN);
        subject = Objects.requireNonNullElse(subject, UNKNOWN);
        method = Objects.requireNonNullElse(method, UNKNOWN);
        path = Objects.requireNonNullElse(path, UNKNOWN);
        code = Objects.requireNonNull(code, "code");
        scope = Objects.requireNonNull(scope, "scope");
    }
}
