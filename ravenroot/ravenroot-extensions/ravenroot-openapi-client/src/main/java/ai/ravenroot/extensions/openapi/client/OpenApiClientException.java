package ai.ravenroot.extensions.openapi.client;

/** Stable, sanitized failures from the profiled OpenAPI client. */
public final class OpenApiClientException extends RuntimeException {
    public enum Code {
        CONFIGURATION, INVALID_INPUT, CAPACITY_UNAVAILABLE, DESTINATION_REFUSED,
        CREDENTIAL_UNAVAILABLE, TLS_REFUSED, REQUEST_TOO_LARGE, RESPONSE_TOO_LARGE,
        DEADLINE_EXCEEDED, TRANSPORT_UNAVAILABLE, REDIRECT_REFUSED, RESPONSE_INVALID,
        AMBIGUOUS
    }

    private final Code code;

    OpenApiClientException(Code code) {
        super("OpenAPI client failed: " + code.name(), null, false, false);
        this.code = code;
    }

    public Code code() { return code; }
}
