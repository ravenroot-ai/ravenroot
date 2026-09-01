package ai.ravenroot.extensions.storage;

/** Stable, sanitized object-storage failure. */
public final class StorageException extends RuntimeException {
    public enum Code {
        CONFIGURATION, INVALID_INPUT, CAPACITY_UNAVAILABLE, RATE_LIMITED,
        DESTINATION_REFUSED, CREDENTIAL_UNAVAILABLE, TLS_REFUSED,
        REQUEST_TOO_LARGE, RESPONSE_TOO_LARGE, DEADLINE_EXCEEDED,
        TRANSPORT_UNAVAILABLE, REDIRECT_REFUSED, NOT_FOUND, REMOTE_REJECTED,
        RESPONSE_INVALID, AMBIGUOUS
    }

    private final Code code;

    private StorageException(Code code) {
        super("Object storage failed: " + code.name(), null, false, false);
        this.code = code;
    }

    public Code code() { return code; }
    static StorageException of(Code code) { return new StorageException(code); }
}
