package ai.ravenroot.api.security;

import java.time.Clock;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deny-by-default RBAC/scope policy with mandatory tenant and audit checks. */
public final class DefaultAuthorizationService implements AuthorizationService {
    private static final Map<AuthorizationAction, Set<Role>> ROLE_MATRIX = roleMatrix();

    private final AuthorizationAuditSink auditSink;
    private final Clock clock;

/**
 * Creates the default deny-by-default policy service.
 * @param auditSink non-null destination for payload-free authorization evidence
 */
    public DefaultAuthorizationService(AuthorizationAuditSink auditSink) {
        this(auditSink, Clock.systemUTC());
    }

    DefaultAuthorizationService(AuthorizationAuditSink auditSink, Clock clock) {
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public AuthorizationDecision decide(RequestContext context, AuthorizationAction action,
                                        ProtectedResource resource) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(resource, "resource");
        AuthorizationDecision decision = evaluate(context, action, resource);
        var event = new AuthorizationAuditEvent(clock.instant(), context.requestId(), context.subject(),
                context.tenantId(), action, resource.type(), resource.id(), decision.allowed(), decision.reason());
        try {
            auditSink.record(event);
        } catch (RuntimeException auditFailure) {
            throw new AuthorizationDeniedException("authorization audit unavailable");
        }
        return decision;
    }

    private static AuthorizationDecision evaluate(RequestContext context, AuthorizationAction action,
                                                  ProtectedResource resource) {
        if (resource.tenantId().isEmpty()) {
            return deny("resource ownership is unknown");
        }
        if (!action.available()) {
            return deny("action is unavailable");
        }
        if (action == AuthorizationAction.EMBED_SESSION_CREATE
                && context.principalType() != PrincipalType.WORKLOAD) {
            return deny("embed sessions require a workload principal");
        }
        // The mirror image of the rule above. Every embed HTTP route authenticates a WORKLOAD,
        // so requiring a USER here is what makes provision and revoke unreachable from the browser
        // boundary, from a graph and from a plugin, independently of which roles a token carries.
        if (action == AuthorizationAction.EMBED_REGISTRATION_ADMIN
                && context.principalType() != PrincipalType.USER) {
            return deny("embed registration administration requires an operator principal");
        }
        boolean platformAdmin = context.roles().contains(Role.PLATFORM_ADMIN);
        if (!platformAdmin && !resource.tenantId().get().equals(context.tenantId())) {
            return deny("cross-tenant access denied");
        }
        Set<Role> permitted = ROLE_MATRIX.get(action);
        if (permitted == null || context.roles().stream().noneMatch(permitted::contains)) {
            return deny("role does not permit action");
        }
        if (!context.scopes().contains(action.requiredScope())) {
            return deny("required scope is absent");
        }
        return new AuthorizationDecision(true, "policy allowed");
    }

    private static AuthorizationDecision deny(String reason) {
        return new AuthorizationDecision(false, reason);
    }

    private static Map<AuthorizationAction, Set<Role>> roleMatrix() {
        var matrix = new EnumMap<AuthorizationAction, Set<Role>>(AuthorizationAction.class);
        put(matrix, EnumSet.of(Role.VIEWER, Role.OPERATOR, Role.DEVELOPER, Role.APPROVER,
                Role.TENANT_ADMIN, Role.PLATFORM_ADMIN), AuthorizationAction.STATUS_READ,
                AuthorizationAction.CATALOG_READ, AuthorizationAction.ARTIFACT_LIST);
        put(matrix, EnumSet.of(Role.OPERATOR, Role.TENANT_ADMIN, Role.PLATFORM_ADMIN),
                AuthorizationAction.GRAPH_READ, AuthorizationAction.EXECUTION_START);
        put(matrix, EnumSet.of(Role.VIEWER, Role.OPERATOR, Role.TENANT_ADMIN, Role.PLATFORM_ADMIN),
                AuthorizationAction.EMBED_GRAPH_READ, AuthorizationAction.EMBED_SESSION_CREATE);
        // Deliberately narrower than the two actions above, which VIEWER holds. Deciding which
        // snapshot an embed may ever expose is an operations decision, not a viewing one.
        put(matrix, EnumSet.of(Role.OPERATOR, Role.TENANT_ADMIN, Role.PLATFORM_ADMIN),
                AuthorizationAction.EMBED_REGISTRATION_ADMIN);
        put(matrix, EnumSet.of(Role.PLATFORM_ADMIN), AuthorizationAction.RUNTIME_OBSERVE);
        put(matrix, EnumSet.of(Role.PLATFORM_ADMIN), AuthorizationAction.AGENT_AUTHORITY_CONTROL);
        put(matrix, EnumSet.of(Role.OPERATOR, Role.TENANT_ADMIN, Role.PLATFORM_ADMIN),
                AuthorizationAction.EXECUTION_READ);
        // Same role set as EXECUTION_START/EXECUTION_READ -- the action is shared by cancel
        // (traversal-scoped, tenant-owned) and drain (server-wide). Drain's narrower blast radius is
        // enforced through the resource it is checked against (a "platform"-tenant-owned resource, see
        // AuthorizedRavenrootApplication#drain), not through a stricter role set here, so a tenant's own
        // OPERATOR can cancel its own traversals but cannot drain the server.
        put(matrix, EnumSet.of(Role.OPERATOR, Role.TENANT_ADMIN, Role.PLATFORM_ADMIN),
                AuthorizationAction.EXECUTION_CONTROL);
        put(matrix, EnumSet.of(Role.DEVELOPER, Role.TENANT_ADMIN, Role.PLATFORM_ADMIN),
                AuthorizationAction.ARTIFACT_CREATE, AuthorizationAction.ARTIFACT_VALIDATE,
                AuthorizationAction.ARTIFACT_TEST);
        put(matrix, EnumSet.of(Role.APPROVER, Role.TENANT_ADMIN, Role.PLATFORM_ADMIN),
                AuthorizationAction.ARTIFACT_APPROVE, AuthorizationAction.ARTIFACT_ACTIVATE,
                AuthorizationAction.ARTIFACT_RETIRE);
        // Reading and exporting a tenant's own audit trail is a tenant-administration
        // privilege; redacting it (retention) is reserved to a platform administrator, because a
        // tenant admin redacting their own trail would be the audited party editing its own record.
        put(matrix, EnumSet.of(Role.TENANT_ADMIN, Role.PLATFORM_ADMIN),
                AuthorizationAction.AUDIT_READ, AuthorizationAction.AUDIT_EXPORT);
        put(matrix, EnumSet.of(Role.PLATFORM_ADMIN), AuthorizationAction.AUDIT_ADMIN);
        return Map.copyOf(matrix);
    }

    private static void put(Map<AuthorizationAction, Set<Role>> matrix, Set<Role> roles,
                            AuthorizationAction... actions) {
        for (AuthorizationAction action : actions) {
            matrix.put(action, Set.copyOf(roles));
        }
    }
}
