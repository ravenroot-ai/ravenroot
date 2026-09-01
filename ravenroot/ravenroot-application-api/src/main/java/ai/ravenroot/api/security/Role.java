package ai.ravenroot.api.security;

/** Stable application roles. Permissions are defined centrally by {@link DefaultAuthorizationService}. */
public enum Role {
/**
 * May view tenant-visible information without changing it.
 */
    VIEWER,
/**
 * May operate executions within granted tenant and scope boundaries.
 */
    OPERATOR,
/**
 * May use developer-facing artifact and graph capabilities.
 */
    DEVELOPER,
/**
 * May pass approval gates assigned to this role.
 */
    APPROVER,
/**
 * Administers resources belonging to one tenant.
 */
    TENANT_ADMIN,
/**
 * Administers platform-wide resources where the policy permits it.
 */
    PLATFORM_ADMIN
}
