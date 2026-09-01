package ai.ravenroot.api.security;

/** Kind of authenticated actor, independent of any transport or identity provider. */
public enum PrincipalType {
/**
 * An authenticated human or user-facing identity.
 */
    USER,
/**
 * A non-human workload identity such as a service account.
 */
    WORKLOAD
}
