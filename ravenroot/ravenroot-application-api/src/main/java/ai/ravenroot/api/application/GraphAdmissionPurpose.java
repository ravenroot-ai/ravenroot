package ai.ravenroot.api.application;

/** The operation for which an immutable GraphML document is being admitted. */
public enum GraphAdmissionPurpose {
    /** One-shot Test or Run execution. */
    EXECUTION,
    /** Process-local long-lived deployment. */
    LOCAL_DEPLOYMENT,
    /** Process-local inbound-source session. */
    SOURCE_SESSION
}
