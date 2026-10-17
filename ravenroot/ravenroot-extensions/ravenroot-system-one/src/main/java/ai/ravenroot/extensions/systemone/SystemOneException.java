package ai.ravenroot.extensions.systemone;

/** Sanitized failure vocabulary for {@code system-one-evaluate}. */
public final class SystemOneException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        PROFILE_UNKNOWN,
        CONFIGURATION_INVALID,
        SPECIFICATION_INVALID,
        CAPABILITY_UNSUPPORTED,
        STATE_INVALID,
        REQUEST_TOO_LARGE,
        CREDENTIAL_UNAVAILABLE,
        DESTINATION_REFUSED,
        ENDPOINT_REJECTED,
        RESPONSE_UNREADABLE,
        RESPONSE_MISMATCH,
        RESPONSE_TOO_LARGE,
        DEADLINE_EXCEEDED,
        CAPACITY_UNAVAILABLE,
        TRANSPORT_UNAVAILABLE
    }

    private final Code code;
    private final String hint;

    public SystemOneException(Code code) {
        this(code, "");
    }

    public SystemOneException(Code code, String hint) {
        super(hint == null || hint.isBlank() ? code.name() : code.name() + ": " + hint);
        this.code = java.util.Objects.requireNonNull(code, "code");
        this.hint = hint == null ? "" : hint;
    }

    public Code code() { return code; }
    public String hint() { return hint; }
}
