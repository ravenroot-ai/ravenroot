package ai.ravenroot.api.security;

/**
 * Receives durable authorization-decision audit events from an application adapter.
 */
@FunctionalInterface
public interface AuthorizationAuditSink {
/**
 * Records one authorization decision without authorizing it.
 * @param event immutable decision event to persist or forward
 */
    void record(AuthorizationAuditEvent event);
}
