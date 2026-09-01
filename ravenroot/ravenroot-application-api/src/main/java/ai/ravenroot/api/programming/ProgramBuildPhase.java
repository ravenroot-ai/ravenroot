package ai.ravenroot.api.programming;

/** Observable server-owned phase of a content-addressed program build. */
public enum ProgramBuildPhase {
    /** Registers canonical source and payload identities. */
REGISTER,
    /** Validates source without executing user logic. */
VALIDATE,
    /** Executes the bounded qualification smoke test. */
SMOKE_TEST,
    /** Evaluates automatic approval policy. */
APPROVE_BY_POLICY,
    /** Activates an approved artifact. */
ACTIVATE,
    /** Marks the artifact ready for admitted execution. */
READY,
    /** Waits for the required independent approval. */
APPROVAL_REQUIRED,
    /** Records a terminal qualification failure. */
FAILED,
    /** Records that the artifact has been retired. */
RETIRED
}
