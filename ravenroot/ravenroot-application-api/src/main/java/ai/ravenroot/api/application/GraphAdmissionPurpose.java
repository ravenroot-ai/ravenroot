package ai.ravenroot.api.application;

/** The operation for which an immutable GraphML document is being admitted. */
public enum GraphAdmissionPurpose {
    EXECUTION,
    LOCAL_DEPLOYMENT,
    SOURCE_SESSION
}
