package ai.ravenroot.api.security;

import java.time.Instant;

/**
 * Payload-free authorization evidence suitable for a security audit sink.
 * @param occurredAt instant at which the decision was made
 * @param requestId request correlation identifier
 * @param subject authenticated subject name
 * @param tenantId tenant selected at ingress
 * @param action requested application action
 * @param resourceType protected resource kind
 * @param resourceId protected resource identifier
 * @param allowed whether policy permitted the action
 * @param reason safe-to-disclose decision reason
 */
public record AuthorizationAuditEvent(Instant occurredAt, String requestId, String subject, String tenantId,
                                      AuthorizationAction action, String resourceType, String resourceId,
                                      boolean allowed, String reason) {
}
