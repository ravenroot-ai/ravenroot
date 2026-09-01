package ai.ravenroot.api.application;

/**
 * Server-selected policy for an inline graph submission.
 *
 * <p>This is not deployment lifecycle state. Deployments have their own explicit activation and
 * ingress contracts; an inline submission never creates one. {@link #TEST_PASSTHROUGH} is the Play
 * boundary from ADR 0023: the immutable submitted graph is traversed, but behavior implementations
 * are not constructed or invoked, so the traversal cannot resolve a production binding or perform
 * an adapter/tool side effect.</p>
 */
public enum ExecutionPolicy {
    /** Existing trusted/embedded one-shot behavior, retained for source and binary compatibility. */
    STANDARD,

    /** Server-enforced transient Play evidence: structural traversal with every behavior passed through. */
    TEST_PASSTHROUGH
}
