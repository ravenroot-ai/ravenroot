package ai.ravenroot.api.audit;

/**
 * The seven categories SEC-13 names: accesses, decisions, versions, tools, approvals,
 * administration and recovery.
 *
 * <p>Not every category has a producer wired into the trail yet. {@link #TOOL} and {@link #RECOVERY}
 * are declared so the port never has to change shape when a producer for them is added later, but
 * nothing constructs one: {@code AuthorizationAction.TOOL_INVOKE} is a reserved, unavailable action
 * with no call site, and no lease- or fencing-recovery event exists to audit. Wiring a real producer
 * for those two is future work, not something this port should pretend to have solved by shipping an
 * event nobody emits.</p>
 *
 * <p>{@link #ADMINISTRATION} <strong>does</strong> have producers, and this Javadoc previously claimed
 * otherwise. {@code AuditTrail.redact} writes an administration tombstone naming the range it redacted,
 * and an {@code AUDIT_ADMIN} authorization decision is categorised here too. The tombstone is not
 * incidental: it is the record {@code verify()} requires before it will accept that a redacted record
 * was redacted legitimately, so this category is load-bearing rather than aspirational.</p>
 */
public enum AuditCategory {
    /** A read or observation of a protected resource (SEC-03 {@code *_READ} / {@code *_LIST} actions). */
    ACCESS,

    /** A policy decision made by the reference monitor (SEC-03 {@code AuthorizationService.decide}). */
    DECISION,

    /** An artifact or graph version lifecycle transition (create, validate, test, activate, retire). */
    VERSION,

    /** A tool invocation. No in-tree producer exists yet; see the class Javadoc. */
    TOOL,

    /** An approval or dual-control gate crossing (e.g. {@code ARTIFACT_APPROVE}). */
    APPROVAL,

    /** A change to the platform's own configuration or to the audit trail itself (e.g. retention). */
    ADMINISTRATION,

    /** Recovery from a fault: a fencing takeover, a lease reclaim, a crash-window resync. No in-tree
     * producer exists yet; see the class Javadoc. */
    RECOVERY,

    /**
     * An operator command that changes the course of running work: cancelling a traversal or draining
     * the server (API-02). Distinct from {@link #ADMINISTRATION}, which covers the platform's own
     * configuration and the audit trail itself, and from {@link #RECOVERY}, which is the platform
     * recovering from a fault rather than an operator directing work in progress. Its first producer is
     * {@code AuthorizedRavenrootApplication#cancelExecution}/{@code #drain}, added in the same commit as
     * this category -- see {@link #TOOL} and {@link #RECOVERY} for what a category declared ahead of its
     * producer looks like; this one is not that.
     */
    CONTROL
}
