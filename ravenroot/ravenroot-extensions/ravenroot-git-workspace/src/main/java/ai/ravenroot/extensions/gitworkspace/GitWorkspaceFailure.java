package ai.ravenroot.extensions.gitworkspace;

/** A stable, redacted refusal from the confined Git workspace package. */
public final class GitWorkspaceFailure extends RuntimeException {
    public enum Code {
        INVALID_INPUT,
        PROFILE_UNAVAILABLE,
        AUTHORITY_REFUSED,
        STATE_CORRUPT,
        GIT_UNAVAILABLE,
        GIT_FAILED,
        OUTPUT_LIMIT_EXCEEDED,
        RESOURCE_LIMIT_EXCEEDED,
        DEADLINE_EXCEEDED,
        CANCELLED,
        SATURATED
    }

    private final Code code;

    GitWorkspaceFailure(Code code) {
        super(code.name(), null, false, false);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    static GitWorkspaceFailure of(Code code) {
        return new GitWorkspaceFailure(code);
    }
}
