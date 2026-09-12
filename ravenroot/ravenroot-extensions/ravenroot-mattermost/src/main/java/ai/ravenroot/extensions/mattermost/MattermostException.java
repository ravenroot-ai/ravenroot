package ai.ravenroot.extensions.mattermost;

/** Stable, payload-free failures exposed by the Mattermost boundary. */
final class MattermostException extends RuntimeException {
    enum Code {
        CONFIGURATION, INVALID_INPUT, FORBIDDEN, CAPACITY, AUTHENTICATION_FAILED,
        TRANSPORT, INDETERMINATE, RESPONSE_INVALID, DURABILITY_UNAVAILABLE, CANCELLED
    }
    private final Code code;
    MattermostException(Code code) { super(code.name()); this.code = code; }
    Code code() { return code; }
}
