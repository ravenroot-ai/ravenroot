package ai.ravenroot.extensions.github;

/** Stable, content-free failure for the GitHub automation bundle. */
public class GithubException extends RuntimeException {
    public enum Code {
        CONFIGURATION, INVALID_INPUT, PROFILE_UNAVAILABLE, AUTHENTICATION_FAILED, FORBIDDEN,
        CAPACITY, RATE_LIMITED, TRANSPORT, RESPONSE_INVALID, DURABILITY_UNAVAILABLE,
        CAS_LOST, STALE_HEAD, AMBIGUOUS, CANCELLED
    }

    private final Code code;

    public GithubException(Code code) {
        super("GitHub operation failed: " + code.name().toLowerCase(java.util.Locale.ROOT));
        this.code = java.util.Objects.requireNonNull(code);
    }

    public Code code() { return code; }
}
