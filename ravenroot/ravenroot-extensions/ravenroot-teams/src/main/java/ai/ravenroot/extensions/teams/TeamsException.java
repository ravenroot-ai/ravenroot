package ai.ravenroot.extensions.teams;

/** Closed, content-free Teams extension failure. */
public final class TeamsException extends RuntimeException
        implements ai.ravenroot.api.execution.RetryClassified {
    private static final long serialVersionUID = 1L;

    /** Stable categories that never include provider or message content. */
    public enum Code {
        /** Operator configuration is missing or invalid. */
        CONFIGURATION,
        /** The local request does not match the bounded contract. */
        INVALID_INPUT,
        /** Managed credentials are unavailable or Teams rejected them. */
        AUTHENTICATION_FAILED,
        /** The configured authority does not permit the operation. */
        FORBIDDEN,
        /** Local or managed admission capacity is unavailable. */
        CAPACITY,
        /** A local or provider rate limit rejected the operation. */
        RATE_LIMITED,
        /** Teams deterministically rejected the operation. */
        REMOTE_REJECTED,
        /** A bounded provider response did not match the expected contract. */
        RESPONSE_INVALID,
        /** The request failed before managed dispatch. */
        TRANSPORT,
        /** The effect outcome cannot be determined safely. */
        INDETERMINATE,
        /** The caller cancelled the operation. */
        CANCELLED,
        /** Durable ingress custody is unavailable. */
        DURABILITY_UNAVAILABLE
    }

    /** Content-free failure category. */
    private final Code code;

    TeamsException(Code code) {
        super("Teams operation failed: " + java.util.Objects.requireNonNull(code).name());
        this.code = code;
    }

    /**
     * Returns the stable content-free failure category.
     *
     * @return failure category
     */
    public Code code() { return code; }

    @Override public ai.ravenroot.api.persistence.Retryability retryability() {
        return code == Code.INDETERMINATE
                ? ai.ravenroot.api.persistence.Retryability.INDETERMINATE
                : ai.ravenroot.api.persistence.Retryability.DETERMINISTIC_REJECT;
    }
}
