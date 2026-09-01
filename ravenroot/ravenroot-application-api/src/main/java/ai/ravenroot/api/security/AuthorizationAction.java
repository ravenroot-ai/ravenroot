package ai.ravenroot.api.security;

/** Application actions evaluated by the single policy decision point. */
public enum AuthorizationAction {
    /** Read the application status endpoint. */
    STATUS_READ("ravenroot.read", true),
    /** Observe runtime health and operational state. */
    RUNTIME_OBSERVE("ravenroot.observe", true),
    /** Read trusted node catalog metadata. */
    CATALOG_READ("ravenroot.read", true),
    /** List programmable artifacts. */
    ARTIFACT_LIST("ravenroot.artifact.read", true),
    /** Submit a new programmable artifact. */
    ARTIFACT_CREATE("ravenroot.artifact.manage", true),
    /** Validate a programmable artifact. */
    ARTIFACT_VALIDATE("ravenroot.artifact.manage", true),
    /** Run an artifact test. */
    ARTIFACT_TEST("ravenroot.artifact.manage", true),
    /** Approve an artifact for activation. */
    ARTIFACT_APPROVE("ravenroot.artifact.approve", true),
    /** Activate an approved artifact. */
    ARTIFACT_ACTIVATE("ravenroot.artifact.activate", true),
    /** Retire an active artifact. */
    ARTIFACT_RETIRE("ravenroot.artifact.retire", true),
    /** Read graph metadata. */
    GRAPH_READ("ravenroot.graph.inspect", true),
    /** Read a browser-safe embed graph projection. */
    EMBED_GRAPH_READ("ravenroot.embed.graph.read", true),
    /** Create a browser embed session. */
    EMBED_SESSION_CREATE("ravenroot.embed.session.create", true),

    /**
     * Provisioning and revoking an embed registration.
     *
     * <p>Its own scope rather than a reuse of {@code EMBED_SESSION_CREATE}, because the two are
     * granted to different parties: a workload holds session-create so it can start a viewer for a
     * registration someone else authorized, while this action decides which snapshot any viewer may
     * ever see. A deployment that issued one token for both would have given every embedding
     * workload the ability to repoint the embed at a different graph.</p>
     */
    EMBED_REGISTRATION_ADMIN("ravenroot.embed.registration.admin", true),
    /** Start an application execution. */
    EXECUTION_START("ravenroot.execute", true),
    /** Inspect executions and their outcomes. */
    EXECUTION_READ("ravenroot.observe", true),

    /** SEC-13: reading, exporting from and administering (e.g. redacting) the security audit trail. */
    /** Read security audit records. */
    AUDIT_READ("ravenroot.audit.read", true),
    /** Export security audit records. */
    AUDIT_EXPORT("ravenroot.audit.export", true),
    /** Administer the security audit trail. */
    AUDIT_ADMIN("ravenroot.audit.admin", true),

    /**
     * API-02: cancel, server drain, pause and resume share this action because they are all operator
     * controls over execution, while retaining their distinct operation semantics.
     */
    EXECUTION_CONTROL("ravenroot.execution.control", true),

    /** Reserved categories whose use cases are not exposed by the current application API. */
    /** Reserved graph-mutation permission, not exposed by this API version. */
    GRAPH_WRITE("ravenroot.graph.write", false),
    /** Reserved tool-invocation permission, not exposed by this API version. */
    TOOL_INVOKE("ravenroot.tool.invoke", false),
    /** Reserved administrative permission, not exposed by this API version. */
    ADMIN("ravenroot.admin", false);

    private final String requiredScope;
    private final boolean available;

    AuthorizationAction(String requiredScope, boolean available) {
        this.requiredScope = requiredScope;
        this.available = available;
    }

    /** Returns the OAuth-style scope checked for this action.
     * @return required scope name
     */
    public String requiredScope() {
        return requiredScope;
    }

    /** Reports whether the corresponding application operation is currently exposed.
     * @return {@code true} for an implemented public use case
     */
    public boolean available() {
        return available;
    }
}
